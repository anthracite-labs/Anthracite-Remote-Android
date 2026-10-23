package dev.anthracite.remote.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub HTTP behaviour without the network.
 *
 * The transport is the only seam, so mapping status codes to results, following
 * pagination, refusing malformed JSON and pinning a merge to the reviewed head
 * SHA are all testable here. This is where a wrong status mapping would
 * otherwise reach the user as "the GitHub request failed".
 */
class GitHubApiTest {
    private class FakeTransport(
        private val handler: (HttpRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun response(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        HttpResponse(status, headers, body.toByteArray(Charsets.UTF_8))

    private fun api(
        transport: HttpTransport,
        token: String? = "ghp_test",
    ) = GitHubApi(transport) { token }

    private val repo = RepoRef("anthracite-labs", "Anthracite-Remote-Android")

    @Test
    fun `a repository page is parsed and the next page is read from the link header`() {
        val body = """
            [{"name":"one","owner":{"login":"me"},"private":true,"default_branch":"main",
              "open_issues_count":3,"description":null}]
        """.trimIndent()
        val transport = FakeTransport {
            response(
                200,
                body,
                mapOf("link" to listOf("<https://api.github.com/user/repos?page=2>; rel=\"next\"")),
            )
        }

        val result = api(transport).listRepositories()

        val page = (result as GitHubResult.Ok).value
        assertEquals(1, page.items.size)
        assertEquals("me/one", page.items[0].fullName)
        assertEquals("main", page.items[0].defaultBranch)
        assertEquals(2, page.nextPage)
        assertTrue(transport.requests.single().headers["Authorization"].orEmpty().startsWith("Bearer "))
    }

    @Test
    fun `a missing token never reaches the network`() {
        val transport = FakeTransport { error("the transport must not be called without a token") }
        val result = api(transport, token = null).listRepositories()
        assertTrue(result is GitHubResult.Unauthorized)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `401 clears the caller's token and says so`() {
        val result = api(FakeTransport { response(401, """{"message":"Bad credentials"}""") }).listRepositories()
        val unauthorized = result as GitHubResult.Unauthorized
        assertEquals("Bad credentials", unauthorized.message)
    }

    @Test
    fun `403 with an exhausted rate limit reports a retry delay`() {
        val transport = FakeTransport {
            response(
                403,
                """{"message":"API rate limit exceeded"}""",
                mapOf("x-ratelimit-remaining" to listOf("0"), "retry-after" to listOf("30")),
            )
        }
        val forbidden = api(transport).listRepositories() as GitHubResult.Forbidden
        assertTrue(forbidden.rateLimited)
        assertEquals(30L, forbidden.retryAfterSeconds)
    }

    @Test
    fun `404 and 422 keep GitHub's own message`() {
        assertTrue(
            (api(FakeTransport { response(404, """{"message":"Not Found"}""") }).pullDetail(repo, 1)
                as GitHubResult.NotFound).message.contains("Not Found"),
        )
        assertTrue(
            (api(FakeTransport { response(422, """{"message":"Validation Failed"}""") }).commentOnIssue(repo, 1, "hi")
                as GitHubResult.Invalid).message.contains("Validation Failed"),
        )
    }

    @Test
    fun `malformed JSON is a failure, not an empty success`() {
        val result = api(FakeTransport { response(200, "not json at all") }).listRepositories()
        assertTrue(result is GitHubResult.Failed)
        assertTrue((result as GitHubResult.Failed).message.contains("malformed"))
    }

    @Test
    fun `an unexpected JSON shape is a failure, not a crash`() {
        val result = api(FakeTransport { response(200, """{"message":"not a list"}""") }).listRepositories()
        assertTrue(result is GitHubResult.Failed)
    }

    @Test
    fun `merge sends the reviewed head SHA and the chosen method`() {
        val transport = FakeTransport {
            response(200, """{"merged":true,"message":"Pull Request successfully merged","sha":"abc123"}""")
        }
        val reviewed = "deadbeefcafe".padEnd(40, '0')
        val outcome = (api(transport).mergePull(repo, 7, MergeMethod.SQUASH, reviewed)
            as GitHubResult.Ok).value

        assertTrue(outcome.merged)
        assertEquals("abc123", outcome.sha)
        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertTrue(request.url.endsWith("/repos/anthracite-labs/Anthracite-Remote-Android/pulls/7/merge"))
        val body = String(request.body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue("body must pin the reviewed head: $body", body.contains("\"sha\":\"$reviewed\""))
        assertTrue(body.contains("\"merge_method\":\"squash\""))
    }

    @Test
    fun `missing and malformed reviewed heads cannot submit a merge`() {
        val transport = FakeTransport { error("merge endpoint must not be called") }
        for (head in listOf(null, "", "not a sha", "deadbeefcafe", "a".repeat(41), "g".repeat(40))) {
            assertTrue("head $head must be rejected", api(transport).mergePull(repo, 7, MergeMethod.MERGE, head) is GitHubResult.Invalid)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a review that requires a comment is refused before any request`() {
        val transport = FakeTransport { error("no request should be made") }
        val result = api(transport).submitReview(repo, 3, ReviewEvent.REQUEST_CHANGES, "   ")
        assertTrue(result is GitHubResult.Invalid)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `an approval without a comment is allowed`() {
        val transport = FakeTransport { response(200, """{"state":"APPROVED"}""") }
        val result = api(transport).submitReview(repo, 3, ReviewEvent.APPROVE, "")
        assertEquals("APPROVED", (result as GitHubResult.Ok).value)
        val body = String(transport.requests.single().body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue(body.contains("\"event\":\"APPROVE\""))
        assertFalse(body.contains("\"body\""))
    }

    @Test
    fun `comments are bounded and escaping survives quotes and newlines`() {
        val transport = FakeTransport { response(201, """{"html_url":"https://github.com/x/y/pull/1#c"}""") }
        api(transport).commentOnIssue(repo, 1, "line one\nline \"two\"")
        val body = String(transport.requests.single().body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue(body.contains("\\n"))
        assertTrue(body.contains("\\\"two\\\""))

        val empty = api(FakeTransport { error("no request") }).commentOnIssue(repo, 1, "  \n  ")
        assertTrue(empty is GitHubResult.Invalid)
    }

    @Test
    fun `check runs are parsed and an invalid ref never reaches the network`() {
        val transport = FakeTransport {
            response(
                200,
                """{"check_runs":[{"name":"build","status":"completed","conclusion":"success",
                   "html_url":"https://example.invalid/1"}]}""",
            )
        }
        val checks = (api(transport).listChecks(repo, "main") as GitHubResult.Ok).value
        assertEquals("build", checks.single().name)
        assertEquals("success", checks.single().conclusion)

        val rejected = api(FakeTransport { error("no request") }).listChecks(repo, "main\nrm -rf /")
        assertTrue(rejected is GitHubResult.Invalid)
    }

    @Test
    fun `pull detail exposes the head SHA used to pin merges`() {
        val body = """
            {"number":9,"title":"t","state":"open","user":{"login":"me"},
             "head":{"ref":"f","sha":"0123abcd"},"base":{"ref":"main"},"draft":false,
             "mergeable":true,"body":"b","changed_files":2,"additions":5,"deletions":1}
        """.trimIndent()
        val detail = (api(FakeTransport { response(200, body) }).pullDetail(repo, 9)
            as GitHubResult.Ok).value
        assertEquals("0123abcd", detail.headSha)
        assertEquals(2, detail.changedFiles)
    }

    @Test
    fun `a diff is returned as text and respects the API version header`() {
        val transport = FakeTransport { response(200, "diff --git a/x b/x\n") }
        val diff = (api(transport).pullDiff(repo, 4) as GitHubResult.Ok).value
        assertTrue(diff.startsWith("diff --git"))
        assertEquals(
            GitHubAuth.API_VERSION,
            transport.requests.single().headers["X-GitHub-Api-Version"],
        )
    }

    @Test
    fun `a repository reference cannot be turned into another path`() {
        for (bad in listOf("../etc", "a/b", "a b", "a?b", "")) {
            var rejected = false
            try {
                RepoRef(bad, "repo")
            } catch (expected: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("owner '$bad' should have been rejected", rejected)
        }
        assertEquals("a/b", RepoRef("a", "b").toString())
    }

    // ------------------------------------------------------------------ issues

    @Test
    fun `issues are listed with their state, author and pull-request marker`() {
        val body = """
            [{"number":3,"title":"Broken","state":"open","user":{"login":"ada"},"comments":2,
              "updated_at":"2026-10-22T10:00:00Z"},
             {"number":4,"title":"A pull","state":"open","user":{"login":"bob"},"comments":0,
              "pull_request":{"url":"x"}}]
        """.trimIndent()
        val transport = FakeTransport {
            response(
                200,
                body,
                mapOf("link" to listOf("<https://api.github.com/repos/a/b/issues?page=2>; rel=\"next\"")),
            )
        }
        val page = (api(transport).listIssues(repo, "closed") as GitHubResult.Ok).value
        assertEquals(2, page.items.size)
        assertEquals("Broken", page.items[0].title)
        assertEquals("ada", page.items[0].author)
        assertFalse(page.items[0].isPullRequest)
        assertTrue(page.items[1].isPullRequest)
        assertEquals(2, page.nextPage)
        // The state filter is a fixed vocabulary, never a caller-supplied fragment.
        assertTrue(transport.requests.single().url.endsWith("/issues?state=open&per_page=30&page=1"))
    }

    @Test
    fun `an issue detail carries the body, labels and state`() {
        val body = """
            {"number":12,"title":"Crash on start","state":"open","user":{"login":"ada"},
             "body":"Steps to reproduce","comments":1,
             "labels":[{"name":"bug"},{"name":"android"}],"updated_at":"2026-10-22T10:00:00Z"}
        """.trimIndent()
        val detail = (api(FakeTransport { response(200, body) }).issueDetail(repo, 12)
            as GitHubResult.Ok).value
        assertEquals(12, detail.number)
        assertEquals("Crash on start", detail.title)
        assertEquals(listOf("bug", "android"), detail.labels)
        assertEquals("Steps to reproduce", detail.body)
        assertFalse(detail.isPullRequest)
    }

    @Test
    fun `issue comments are paged and parsed`() {
        val body = """
            [{"user":{"login":"ada"},"body":"first","created_at":"2026-10-20T09:00:00Z"},
             {"user":{"login":"bob"},"body":"second","created_at":"2026-10-21T09:00:00Z"}]
        """.trimIndent()
        val page = (api(FakeTransport { response(200, body) }).listIssueComments(repo, 3)
            as GitHubResult.Ok).value
        assertEquals(listOf("ada", "bob"), page.items.map { it.author })
        assertEquals("second", page.items[1].body)
        assertEquals(null, page.nextPage)
    }

    @Test
    fun `closing an issue patches only that issue's state`() {
        val transport = FakeTransport {
            response(200, """{"number":3,"state":"closed"}""")
        }
        val state = (api(transport).setIssueState(repo, 3, IssueState.CLOSED) as GitHubResult.Ok).value
        assertEquals("closed", state)
        val sent = transport.requests.single()
        assertEquals("PATCH", sent.method)
        assertTrue(sent.url.endsWith("/repos/$repo/issues/3"))
        assertEquals("""{"state":"closed"}""", String(sent.body ?: ByteArray(0), Charsets.UTF_8))
    }

    @Test
    fun `a new issue needs a single-line title and never leaves an empty body`() {
        val transport = FakeTransport { response(201, """{"number":31}""") }
        val client = api(transport)

        val created = client.createIssue(repo, "Broken build", "Details")
        assertEquals(31, (created as GitHubResult.Ok).value)
        val sent = transport.requests.single()
        assertEquals("POST", sent.method)
        assertTrue(sent.url.endsWith("/repos/$repo/issues"))
        val body = String(sent.body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue(body.contains(""""title":"Broken build""""))
        assertTrue(body.contains(""""body":"Details""""))

        for (badTitle in listOf("", "   ", "line one\nline two", "tab\ttitle")) {
            val rejected = client.createIssue(repo, badTitle, "x")
            assertTrue("title '$badTitle' should have been rejected", rejected is GitHubResult.Invalid)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an issue number of zero is refused before any request`() {
        val transport = FakeTransport { error("the transport must not be called") }
        api(transport).issueDetail(repo, 0)
    }
}
