package dev.anthracite.remote.github

import dev.anthracite.remote.json.JsonException
import dev.anthracite.remote.json.JsonValue
import dev.anthracite.remote.json.MiniJson
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP abstraction so every GitHub call can be unit-tested against a
 * local mock server without Android or network access.
 */
internal data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

internal data class HttpResponse(
    val status: Int,
    private val headerFields: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headerFields.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    fun text(): String = String(body, Charsets.UTF_8)
}

internal interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * HTTPS-only transport.
 *
 * Redirects are not followed: a GitHub API endpoint must never be able to move
 * an authenticated request to another host, and the token must never be sent
 * anywhere except `api.github.com` / `github.com` paths this client asks for.
 * Response bodies are bounded so a hostile or broken peer cannot exhaust memory.
 */
internal class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val url = URL(request.url)
        require(url.protocol == "https") { "GitHub requests must use HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            return HttpResponse(status, connection.headerFields.orEmpty(), responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw IllegalStateException("GitHub response exceeded $limit bytes")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val USER_AGENT = "Anthracite-Remote"
    }
}

// ---------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------

internal data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

internal sealed class TokenPoll {
    data class Success(val token: String, val scopes: List<String>) : TokenPoll()
    data class Pending(val intervalSeconds: Int) : TokenPoll()
    data class SlowDown(val intervalSeconds: Int) : TokenPoll()
    data class Denied(val message: String) : TokenPoll()
    data class Expired(val message: String) : TokenPoll()
    data class Failed(val message: String) : TokenPoll()
}

internal data class GitHubIdentity(val login: String, val name: String?, val scopes: List<String>)

/**
 * GitHub authorization.
 *
 * Two explicit options, neither of which ships a secret in the app:
 * 1. OAuth **device flow**. It needs only a public client id, which is a build
 *    configuration value, never a client secret. GitHub's web flow requires a
 *    `client_secret` even with PKCE, so it is not usable from a mobile binary.
 * 2. A user-supplied fine-grained personal access token, entered by the user and
 *    stored in the Android Keystore. It is never written to a host or a log.
 *
 * The app adds no Anthracite cloud service in either case.
 */
internal class GitHubAuth(
    private val transport: HttpTransport,
    private val clientId: String?,
) {
    val deviceFlowConfigured: Boolean get() = !clientId.isNullOrBlank()

    fun requestDeviceCode(scopes: List<String> = DEFAULT_SCOPES): Result<DeviceCode> = runCatching {
        val id = clientId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Device sign-in is not configured in this build. Use a personal access token instead.",
            )
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = "$GITHUB_HOST/login/device/code",
                headers = formHeaders(),
                body = formBody(
                    "client_id" to id,
                    "scope" to scopes.joinToString(" "),
                ),
            ),
        )
        val json = json(response)
        val deviceCode = json.string("device_code") ?: throw IllegalStateException(deviceError(json, response))
        DeviceCode(
            deviceCode = deviceCode,
            userCode = json.string("user_code").orEmpty(),
            verificationUri = json.string("verification_uri") ?: "https://github.com/login/device",
            expiresInSeconds = json.int("expires_in") ?: 900,
            intervalSeconds = json.int("interval") ?: 5,
        )
    }

    fun poll(deviceCode: DeviceCode, scopes: List<String> = DEFAULT_SCOPES): TokenPoll {
        val id = clientId?.takeIf { it.isNotBlank() } ?: return TokenPoll.Failed("Device sign-in is not configured")
        val response = runCatching {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = "$GITHUB_HOST/login/oauth/access_token",
                    headers = formHeaders(),
                    body = formBody(
                        "client_id" to id,
                        "device_code" to deviceCode.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                ),
            )
        }.getOrElse { return TokenPoll.Failed(it.message ?: "Network error") }
        val json = runCatching { json(response) }.getOrElse {
            return TokenPoll.Failed("GitHub returned an unreadable response (HTTP ${response.status})")
        }
        json.string("access_token")?.let { token ->
            return TokenPoll.Success(token, parseScopes(response.header("x-oauth-scopes"), scopes))
        }
        return when (json.string("error")) {
            "authorization_pending" -> TokenPoll.Pending(deviceCode.intervalSeconds)
            "slow_down" -> TokenPoll.SlowDown(deviceCode.intervalSeconds + 5)
            "expired_token" -> TokenPoll.Expired("The sign-in code expired. Start again.")
            "access_denied" -> TokenPoll.Denied("Sign-in was cancelled on GitHub.")
            else -> TokenPoll.Failed(json.string("error_description") ?: deviceError(json, response))
        }
    }

    /** Validates a token and reports the account and the scopes GitHub actually granted. */
    fun identity(token: String): Result<GitHubIdentity> = runCatching {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "$API_HOST/user",
                headers = authHeaders(token),
            ),
        )
        if (response.status == 401) throw IllegalStateException("GitHub rejected the token (revoked or invalid)")
        if (response.status !in 200..299) throw IllegalStateException("GitHub returned HTTP ${response.status}")
        val json = json(response)
        GitHubIdentity(
            login = json.string("login") ?: throw IllegalStateException("GitHub response had no login"),
            name = json.string("name"),
            scopes = parseScopes(response.header("x-oauth-scopes"), emptyList()),
        )
    }

    /**
     * Token revocation is handled where it is actually possible: the app detects
     * a rejected token (HTTP 401) and drops its local copy, and the UI points the
     * user at GitHub's own session page. OAuth revocation on GitHub's API needs
     * the application's client secret, which a mobile binary must never contain,
     * so no half-working revocation request is attempted here.
     */
    fun revokeGuidanceUrl(): String = "$GITHUB_HOST/settings/applications"

    private fun json(response: HttpResponse): JsonValue.Obj = try {
        MiniJson.parse(response.text()) as? JsonValue.Obj
            ?: throw IllegalStateException("GitHub returned a non-object response")
    } catch (error: JsonException) {
        throw IllegalStateException("GitHub returned malformed JSON (HTTP ${response.status})", error)
    }

    private fun deviceError(json: JsonValue.Obj, response: HttpResponse): String =
        json.string("error_description") ?: json.string("error") ?: "GitHub returned HTTP ${response.status}"

    private fun parseScopes(header: String?, fallback: List<String>): List<String> =
        header?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fallback

    private fun formHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
    )

    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to API_VERSION,
    )

    private fun formBody(vararg fields: Pair<String, String>): ByteArray =
        fields.joinToString("&") { (name, value) ->
            java.net.URLEncoder.encode(name, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
        }.toByteArray()

    companion object {
        const val GITHUB_HOST = "https://github.com"
        const val API_HOST = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        val DEFAULT_SCOPES = listOf("repo", "read:user")
    }
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

internal sealed class GitHubResult<out T> {
    data class Ok<T>(val value: T) : GitHubResult<T>()
    data class Unauthorized(val message: String) : GitHubResult<Nothing>()
    data class Forbidden(val message: String, val rateLimited: Boolean, val retryAfterSeconds: Long?) :
        GitHubResult<Nothing>()
    data class NotFound(val message: String) : GitHubResult<Nothing>()
    data class Invalid(val message: String) : GitHubResult<Nothing>()
    data class Failed(val message: String) : GitHubResult<Nothing>()
}

internal data class Paged<T>(val items: List<T>, val nextPage: Int?)

internal data class RepoSummary(
    val owner: String,
    val name: String,
    val private: Boolean,
    val defaultBranch: String,
    val openIssues: Int,
    val description: String?,
) {
    val fullName: String get() = "$owner/$name"
}

internal data class IssueSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val comments: Int,
    val isPullRequest: Boolean,
    val updatedAt: String?,
)

internal data class PullSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val draft: Boolean,
    val mergeable: Boolean?,
)

internal data class PullDetail(
    val summary: PullSummary,
    val body: String,
    val changedFiles: Int,
    val additions: Int,
    val deletions: Int,
    val headSha: String,
)

internal data class CheckSummary(val name: String, val status: String, val conclusion: String?, val url: String?)

internal data class MergeOutcome(val merged: Boolean, val message: String, val sha: String?)

internal data class RepoRef(val owner: String, val repo: String) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "invalid repository owner" }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "invalid repository name" }
    }

    override fun toString(): String = "$owner/$repo"
}

/**
 * GitHub HTTPS API client.
 *
 * This is deliberately separate from host Git: host Git uses the SSH user's own
 * credentials and repositories, while GitHub actions use a user-authorized API
 * token that can be revoked independently. Every write is a separate, explicit
 * call that the UI must confirm with the exact target visible.
 */
internal class GitHubApi(
    private val transport: HttpTransport,
    private val tokenProvider: () -> String?,
) {
    fun listRepositories(page: Int = 1): GitHubResult<Paged<RepoSummary>> =
        request("GET", "/user/repos?per_page=30&sort=updated&affiliation=owner,collaborator,organization_member&page=${page.coerceAtLeast(1)}") { json, next ->
            val array = json.asArray() ?: return@request GitHubResult.Failed("Unexpected repository response")
            GitHubResult.Ok(
                Paged(
                    array.items.mapNotNull { item ->
                        val repo = item.asObject() ?: return@mapNotNull null
                        val owner = repo.obj("owner")?.string("login") ?: return@mapNotNull null
                        RepoSummary(
                            owner = owner,
                            name = repo.string("name") ?: return@mapNotNull null,
                            private = repo.bool("private") ?: false,
                            defaultBranch = repo.string("default_branch") ?: "main",
                            openIssues = repo.int("open_issues_count") ?: 0,
                            description = repo.string("description"),
                        )
                    },
                    next,
                ),
            )
        }

    fun listIssues(repo: RepoRef, state: String = "open", page: Int = 1): GitHubResult<Paged<IssueSummary>> {
        val safeState = if (state == "closed") "closed" else "open"
        return request(
            "GET",
            "/repos/$repo/issues?state=$safeState&per_page=30&page=${page.coerceAtLeast(1)}",
        ) { json, next ->
            val array = json.asArray() ?: return@request GitHubResult.Failed("Unexpected issue response")
            GitHubResult.Ok(
                Paged(
                    array.items.mapNotNull { item ->
                        val issue = item.asObject() ?: return@mapNotNull null
                        IssueSummary(
                            number = issue.int("number") ?: return@mapNotNull null,
                            title = issue.string("title") ?: "",
                            state = issue.string("state") ?: "open",
                            author = issue.obj("user")?.string("login") ?: "unknown",
                            comments = issue.int("comments") ?: 0,
                            isPullRequest = issue["pull_request"] != null,
                            updatedAt = issue.string("updated_at"),
                        )
                    },
                    next,
                ),
            )
        }
    }

    fun listPulls(repo: RepoRef, page: Int = 1): GitHubResult<Paged<PullSummary>> =
        request("GET", "/repos/$repo/pulls?state=open&per_page=30&page=${page.coerceAtLeast(1)}") { json, next ->
            val array = json.asArray() ?: return@request GitHubResult.Failed("Unexpected pull response")
            GitHubResult.Ok(
                Paged(
                    array.items.mapNotNull { item ->
                        pullSummary(item.asObject() ?: return@mapNotNull null)
                    },
                    next,
                ),
            )
        }

    fun pullDetail(repo: RepoRef, number: Int): GitHubResult<PullDetail> =
        request("GET", "/repos/$repo/pulls/${positive(number)}") { json, _ ->
            val pull = json.asObject() ?: return@request GitHubResult.Failed("Unexpected pull response")
            val summary = pullSummary(pull) ?: return@request GitHubResult.Failed("Unexpected pull response")
            GitHubResult.Ok(
                PullDetail(
                    summary = summary,
                    body = pull.string("body").orEmpty(),
                    changedFiles = pull.int("changed_files") ?: 0,
                    additions = pull.int("additions") ?: 0,
                    deletions = pull.int("deletions") ?: 0,
                    headSha = pull.obj("head")?.string("sha").orEmpty(),
                ),
            )
        }

    /** The unified diff GitHub renders for a pull request, as plain text. */
    fun pullDiff(repo: RepoRef, number: Int): GitHubResult<String> {
        val token = tokenProvider() ?: return GitHubResult.Unauthorized("No GitHub token is stored")
        val response = runCatching {
            transport.execute(
                HttpRequest(
                    method = "GET",
                    url = "${GitHubAuth.API_HOST}/repos/$repo/pulls/${positive(number)}",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Accept" to "application/vnd.github.v3.diff",
                        "X-GitHub-Api-Version" to GitHubAuth.API_VERSION,
                    ),
                ),
            )
        }.getOrElse { return GitHubResult.Failed(it.message ?: "Network error") }
        val failure = failureFor(response)
        if (failure != null) return failure
        return GitHubResult.Ok(response.text())
    }

    fun listChecks(repo: RepoRef, ref: String): GitHubResult<List<CheckSummary>> {
        if (!ref.matches(Regex("[A-Za-z0-9._/-]{1,200}"))) return GitHubResult.Invalid("Invalid ref")
        return request("GET", "/repos/$repo/commits/$ref/check-runs?per_page=50") { json, _ ->
            val array = json.obj("check_runs")?.asArray()
                ?: return@request GitHubResult.Failed("Unexpected check response")
            GitHubResult.Ok(
                array.items.mapNotNull { item ->
                    val check = item.asObject() ?: return@mapNotNull null
                    CheckSummary(
                        name = check.string("name") ?: "check",
                        status = check.string("status") ?: "unknown",
                        conclusion = check.string("conclusion"),
                        url = check.string("html_url"),
                    )
                },
            )
        }

    fun commentOnIssue(repo: RepoRef, number: Int, body: String): GitHubResult<String> {
        val safeBody = sanitizeBody(body) ?: return GitHubResult.Invalid("Comment is empty")
        return request(
            "POST",
            "/repos/$repo/issues/${positive(number)}/comments",
            jsonBody(mapOf("body" to safeBody)),
        ) { json, _ ->
            GitHubResult.Ok(json.string("html_url") ?: "Comment created")
        }
    }

    fun submitReview(repo: RepoRef, number: Int, event: ReviewEvent, body: String): GitHubResult<String> {
        val safeBody = sanitizeBody(body)
        if (event != ReviewEvent.APPROVE && safeBody == null) {
            return GitHubResult.Invalid("A review comment is required for this action")
        }
        val payload = buildMap {
            put("event", event.apiValue)
            if (safeBody != null) put("body", safeBody)
        }
        return request(
            "POST",
            "/repos/$repo/pulls/${positive(number)}/reviews",
            jsonBody(payload),
        ) { json, _ -> GitHubResult.Ok(json.string("state") ?: event.apiValue) }
    }

    fun mergePull(repo: RepoRef, number: Int, method: MergeMethod, expectedHeadSha: String?): GitHubResult<MergeOutcome> {
        val payload = buildMap {
            put("merge_method", method.apiValue)
            if (expectedHeadSha != null && expectedHeadSha.matches(Regex("[0-9a-f]{7,40}"))) {
                put("sha", expectedHeadSha)
            }
        }
        return request("PUT", "/repos/$repo/pulls/${positive(number)}/merge", jsonBody(payload)) { json, _ ->
            GitHubResult.Ok(
                MergeOutcome(
                    merged = json.bool("merged") ?: false,
                    message = json.string("message").orEmpty(),
                    sha = json.string("sha"),
                ),
            )
        }
    }

    private inline fun <T> request(
        method: String,
        path: String,
        body: ByteArray? = null,
        crossinline parse: (JsonValue, Int?) -> GitHubResult<T>,
    ): GitHubResult<T> {
        val token = tokenProvider() ?: return GitHubResult.Unauthorized("No GitHub token is stored")
        val response = runCatching {
            transport.execute(
                HttpRequest(
                    method = method,
                    url = GitHubAuth.API_HOST + path,
                    headers = buildMap {
                        put("Authorization", "Bearer $token")
                        put("Accept", "application/vnd.github+json")
                        put("X-GitHub-Api-Version", GitHubAuth.API_VERSION)
                        if (body != null) put("Content-Type", "application/json")
                    },
                    body = body,
                ),
            )
        }.getOrElse { return GitHubResult.Failed(it.message ?: "Network error") }
        failureFor(response)?.let { return it }
        val parsed = runCatching { MiniJson.parse(response.text()) }
            .getOrElse { return GitHubResult.Failed("GitHub returned malformed JSON (HTTP ${response.status})") }
        return try {
            parse(parsed, nextPage(response))
        } catch (error: Exception) {
            GitHubResult.Failed(error.message ?: "Could not read the GitHub response")
        }
    }

    private fun failureFor(response: HttpResponse): GitHubResult<Nothing>? {
        if (response.status in 200..299) return null
        val message = runCatching {
            (MiniJson.parse(response.text()) as? JsonValue.Obj)?.string("message")
        }.getOrNull()
        return when (response.status) {
            401 -> GitHubResult.Unauthorized(message ?: "GitHub rejected the token; sign in again")
            403, 429 -> {
                val remaining = response.header("x-ratelimit-remaining")
                val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
                val retryAfter = response.header("retry-after")?.toLongOrNull()
                val rateLimited = remaining == "0" || retryAfter != null
                val retry = retryAfter ?: reset?.let { epoch ->
                    (epoch - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                }
                GitHubResult.Forbidden(
                    message ?: if (rateLimited) "GitHub rate limit reached" else "GitHub denied this request",
                    rateLimited,
                    retry,
                )
            }
            404 -> GitHubResult.NotFound(message ?: "Not found, or the token cannot see it")
            422 -> GitHubResult.Invalid(message ?: "GitHub rejected the request")
            else -> GitHubResult.Failed(message ?: "GitHub returned HTTP ${response.status}")
        }
    }

    private fun nextPage(response: HttpResponse): Int? {
        val link = response.header("link") ?: return null
        for (part in link.split(',')) {
            if (!part.contains("rel=\"next\"")) continue
            val page = Regex("[?&]page=([0-9]{1,6})").find(part)?.groupValues?.getOrNull(1)
            return page?.toIntOrNull()
        }
        return null
    }

    private fun pullSummary(pull: JsonValue.Obj): PullSummary? {
        val number = pull.int("number") ?: return null
        return PullSummary(
            number = number,
            title = pull.string("title") ?: "",
            state = pull.string("state") ?: "open",
            author = pull.obj("user")?.string("login") ?: "unknown",
            headRef = pull.obj("head")?.string("ref") ?: "",
            baseRef = pull.obj("base")?.string("ref") ?: "",
            draft = pull.bool("draft") ?: false,
            mergeable = pull.bool("mergeable"),
        )
    }

    private fun positive(number: Int): Int {
        require(number > 0) { "invalid issue or pull number" }
        return number
    }

    private fun sanitizeBody(body: String): String? {
        val trimmed = body.replace("\u0000", "").trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_BODY_CHARS)
    }

    private fun jsonBody(fields: Map<String, String>): ByteArray {
        val builder = StringBuilder("{")
        var first = true
        for ((key, value) in fields) {
            if (!first) builder.append(',')
            first = false
            builder.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
        }
        builder.append('}')
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char.code < 0x20) builder.append(' ') else builder.append(char)
            }
        }
        return builder.toString()
    }

    internal enum class ReviewEvent(val apiValue: String, val label: String) {
        COMMENT("COMMENT", "Comment"),
        APPROVE("APPROVE", "Approve"),
        REQUEST_CHANGES("REQUEST_CHANGES", "Request changes"),
    }

    internal enum class MergeMethod(val apiValue: String, val label: String) {
        MERGE("merge", "Create a merge commit"),
        SQUASH("squash", "Squash and merge"),
        REBASE("rebase", "Rebase and merge"),
    }

    private companion object {
        const val MAX_BODY_CHARS = 20_000
    }
}
