import XCTest
@testable import AnthraciteKit

final class StubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: String], Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: GitHubError.transport("no stub"))
            return
        }
        do {
            let (status, headers, data) = try handler(request)
            let response = HTTPURLResponse(
                url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class GitHubTests: XCTestCase {
    private var client: GitHubClient!
    private let repository = RepositoryRef(owner: "anthracite-labs", name: "Anthracite-Remote-Android")
    private var requests: [URLRequest] = []

    override func setUp() {
        super.setUp()
        requests = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        client = GitHubClient(token: "github_pat_1234567890", session: URLSession(configuration: configuration))
    }

    private func respond(_ status: Int, json: String, headers: [String: String] = [:]) {
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            return (status, headers, Data(json.utf8))
        }
    }

    func testRepositoryParsing() {
        XCTAssertEqual(RepositoryRef.parse("owner/repo")?.slug, "owner/repo")
        XCTAssertNil(RepositoryRef.parse("owner"))
        XCTAssertNil(RepositoryRef.parse("owner/repo/extra"))
        XCTAssertNil(RepositoryRef.parse("own er/repo"))
    }

    func testIssueListParsesAndSeparatesPullRequests() async throws {
        respond(200, json: """
        [{"number":1,"title":"Crash on connect","state":"open","user":{"login":"alice"},
          "updated_at":"2026-10-23T10:00:00Z","comments":2},
         {"number":2,"title":"Fix it","state":"open","user":{"login":"bob"},
          "updated_at":"2026-10-22T10:00:00Z","comments":0,"pull_request":{"url":"x"}}]
        """)
        let issues = try await client.listIssues(repository, state: "open", limit: 1000)
        XCTAssertEqual(issues.count, 2)
        XCTAssertFalse(issues[0].isPullRequest)
        XCTAssertTrue(issues[1].isPullRequest)
        XCTAssertEqual(issues[0].commentCount, 2)
        XCTAssertEqual(requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer github_pat_1234567890")
        XCTAssertTrue(requests.first?.url?.absoluteString.contains("per_page=100") ?? false)
    }

    func testIssueDetailCommentsAndMutations() async throws {
        respond(200, json: """
        {"number":7,"title":"Detail","state":"open","user":{"login":"carol"},"body":"hello","comments":1}
        """)
        let detail = try await client.issue(repository, number: 7)
        XCTAssertEqual(detail.body, "hello")

        respond(200, json: """
        [{"id":11,"body":"first","user":{"login":"dave"},"created_at":"2026-10-23T09:00:00Z"}]
        """)
        let comments = try await client.comments(repository, number: 7)
        XCTAssertEqual(comments.first?.body, "first")

        respond(201, json: "{\"number\":42}")
        let number = try await client.createIssue(repository, title: " New ", body: "body")
        XCTAssertEqual(number, 42)
        XCTAssertEqual(requests.last?.httpMethod, "POST")
        let sent = try JSONSerialization.jsonObject(with: requests.last!.httpBodyStreamData()) as? [String: Any]
        XCTAssertEqual(sent?["title"] as? String, "New")

        respond(200, json: "{}")
        try await client.setIssueState(repository, number: 7, state: "closed")
        XCTAssertEqual(requests.last?.httpMethod, "PATCH")

        await XCTAssertThrowsErrorAsync(try await self.client.setIssueState(self.repository, number: 7, state: "merged"))
        await XCTAssertThrowsErrorAsync(try await self.client.createIssue(self.repository, title: "   ", body: ""))
    }

    func testMergeAlwaysPinsTheReviewedCommit() async throws {
        let sha = String(repeating: "a", count: 40)
        respond(200, json: "{\"merged\":true,\"sha\":\"deadbeef\"}")
        let merged = try await client.merge(repository, number: 3, headSHA: sha, method: "squash")
        XCTAssertEqual(merged, "deadbeef")
        let body = try JSONSerialization.jsonObject(with: requests.last!.httpBodyStreamData()) as? [String: Any]
        XCTAssertEqual(body?["sha"] as? String, sha)
        XCTAssertEqual(body?["merge_method"] as? String, "squash")
        XCTAssertEqual(requests.last?.httpMethod, "PUT")

        await XCTAssertThrowsErrorAsync(try await self.client.merge(self.repository, number: 3, headSHA: "abc"))
        await XCTAssertThrowsErrorAsync(try await self.client.merge(self.repository, number: -1, headSHA: sha))
    }

    func testBlockedMergeIsReportedAsAnError() async {
        respond(200, json: "{\"merged\":false,\"message\":\"Required status check is failing\"}")
        do {
            _ = try await client.merge(repository, number: 3, headSHA: String(repeating: "b", count: 40))
            XCTFail("a blocked merge must not look successful")
        } catch let error as GitHubError {
            guard case .mergeBlocked(let message) = error else { return XCTFail("unexpected \(error)") }
            XCTAssertTrue(message.contains("Required status"))
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testErrorMapping() async {
        respond(401, json: "{}")
        await XCTAssertThrowsErrorAsync(try await self.client.listIssues(self.repository)) { error in
            XCTAssertEqual(error as? GitHubError, .unauthorized)
        }
        respond(403, json: "{}", headers: ["X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1700000000"])
        await XCTAssertThrowsErrorAsync(try await self.client.listIssues(self.repository)) { error in
            XCTAssertEqual(error as? GitHubError, .rateLimited(resetAt: "1700000000"))
        }
        respond(404, json: "{}")
        await XCTAssertThrowsErrorAsync(try await self.client.issue(self.repository, number: 5)) { error in
            XCTAssertEqual(error as? GitHubError, .notFound)
        }
        respond(500, json: "{}")
        await XCTAssertThrowsErrorAsync(try await self.client.issue(self.repository, number: 5)) { error in
            XCTAssertEqual(error as? GitHubError, .unexpectedStatus(500))
        }
    }

    func testInvalidTokensAreRefusedBeforeAnyRequest() async {
        let bad = GitHubClient(token: "short")
        await XCTAssertThrowsErrorAsync(try await bad.listIssues(self.repository)) { error in
            XCTAssertEqual(error as? GitHubError, .invalidToken)
        }
        XCTAssertTrue(requests.isEmpty)
    }

    func testChecksParsing() async throws {
        respond(200, json: """
        {"check_runs":[{"name":"Compile","status":"completed","conclusion":"success"},
                       {"name":"Matrix","status":"in_progress","conclusion":null}]}
        """)
        let checks = try await client.checks(repository, ref: "abc123")
        XCTAssertEqual(checks.count, 2)
        XCTAssertEqual(checks[0].conclusion, "success")
        XCTAssertNil(checks[1].conclusion)
        await XCTAssertThrowsErrorAsync(try await self.client.checks(self.repository, ref: "bad ref!"))
    }
}

extension URLRequest {
    /// URLSession moves a body supplied through a stream into `httpBodyStream`;
    /// the stub needs the bytes back.
    func httpBodyStreamData() -> Data {
        if let body = httpBody { return body }
        guard let stream = httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(contentsOf: buffer.prefix(read))
        }
        return data
    }

    /// Answers each request from `pages`, keyed by the `page` query parameter (the
    /// first request has none). Unknown pages answer an empty array with no `Link`.
    private func respondByPage(_ pages: [Int: (String, [String: String])]) {
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            let components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)
            let page = components?.queryItems?.first { $0.name == "page" }?.value.flatMap(Int.init) ?? 1
            guard let (json, headers) = pages[page] else {
                return (200, [:], Data("[]".utf8))
            }
            return (200, headers, Data(json.utf8))
        }
    }

    private func nextLink(_ page: Int) -> [String: String] {
        ["Link": "<https://api.github.com/repos/anthracite-labs/Anthracite-Remote-Android/issues?per_page=2&page=\(page)>; rel=\"next\", <https://api.github.com/x?page=1>; rel=\"first\""]
    }

    private func issueJSON(_ numbers: [Int]) -> String {
        let items = numbers.map {
            "{\"number\":\($0),\"title\":\"Issue \($0)\",\"state\":\"open\",\"user\":{\"login\":\"u\"}}"
        }
        return "[" + items.joined(separator: ",") + "]"
    }

    // MARK: - Pagination

    func testIssueListingFollowsLinkHeadersAcrossPages() async throws {
        respondByPage([
            1: (issueJSON([1, 2]), nextLink(2)),
            2: (issueJSON([3, 4]), ["Link": "<https://api.github.com/x?page=3>; rel=\"next\""]),
            3: (issueJSON([5]), [:]),
        ])
        let issues = try await client.listIssues(repository, limit: 100)

        XCTAssertEqual(issues.map(\.number), [1, 2, 3, 4, 5])
        XCTAssertEqual(requests.count, 3)
        // Every page keeps the pinning headers.
        for request in requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer github_pat_1234567890")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-GitHub-Api-Version"), "2022-11-28")
        }
    }

    func testAnEmptyPageEndsTheListing() async throws {
        respondByPage([
            1: ("[]", nextLink(2)),
            2: ("[]", [:]),
        ])
        XCTAssertEqual(try await client.listIssues(repository, limit: 10), [])
        XCTAssertEqual(requests.count, 2)
    }

    func testAForeignOrMalformedLinkIsNotFollowed() async throws {
        for link in [
            "<https://evil.example.com/repos/x/y/issues?page=2>; rel=\"next\"",
            "<http://api.github.com/repos/x/y/issues?page=2>; rel=\"next\"",
            "<https://api.github.com@evil.example.com/x?page=2>; rel=\"next\"",
            "<https://api.github.com:8443/x?page=2>; rel=\"next\"",
            "not a link at all",
            "<https://api.github.com/x?page=2>; rel=\"last\"",
        ] {
            requests = []
            respondByPage([1: (issueJSON([1]), ["Link": link])])
            let issues = try await client.listIssues(repository, limit: 100)
            XCTAssertEqual(issues.map(\.number), [1], "followed \(link)")
            XCTAssertEqual(requests.count, 1, "followed \(link)")
        }
    }

    func testDuplicateEntriesAcrossPagesAreReturnedOnce() async throws {
        respondByPage([
            1: (issueJSON([1, 2]), nextLink(2)),
            2: (issueJSON([2, 3]), [:]),
        ])
        XCTAssertEqual(try await client.listIssues(repository, limit: 100).map(\.number), [1, 2, 3])
    }

    func testAPageLimitIsReportedNotSilentlyTruncated() async {
        // Every page claims another one is waiting: a loop with no end.
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            return (200, ["Link": "<https://api.github.com/loop?page=1>; rel=\"next\""], Data((self?.issueJSON([1]) ?? "[]").utf8))
        }
        do {
            _ = try await client.listIssues(repository, limit: 100)
            XCTFail("an endless listing must be reported")
        } catch let error as GitHubError {
            guard case .incompleteCollection = error else {
                return XCTFail("unexpected error \(error)")
            }
        } catch {
            XCTFail("unexpected error \(error)")
        }
        XCTAssertEqual(requests.count, GitHubClient.maximumPages)
    }

    func testRateLimitingOnALaterPageIsReported() async {
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            let page = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "page" }?.value
            if page == "2" {
                return (403, ["X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1700000000"], Data("{}".utf8))
            }
            return (200, ["Link": "<https://api.github.com/x?page=2>; rel=\"next\""], Data((self?.issueJSON([1]) ?? "[]").utf8))
        }
        do {
            _ = try await client.listIssues(repository, limit: 100)
            XCTFail("a rate limit must be reported")
        } catch let error as GitHubError {
            XCTAssertEqual(error, .rateLimited(resetAt: "1700000000"))
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    func testAnUnauthorizedLaterPageIsReported() async {
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            let page = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "page" }?.value
            if page == "2" { return (401, [:], Data()) }
            return (200, ["Link": "<https://api.github.com/x?page=2>; rel=\"next\""], Data((self?.issueJSON([1]) ?? "[]").utf8))
        }
        do {
            _ = try await client.listIssues(repository, limit: 100)
            XCTFail("an unauthorized response must be reported")
        } catch let error as GitHubError {
            XCTAssertEqual(error, .unauthorized)
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    func testDisablingTheConnectionIsNotReportedAsComplete() async throws {
        // A page fails at the transport level: the listing must throw, not return
        // the first page as though it were everything.
        StubURLProtocol.handler = { [weak self] request in
            self?.requests.append(request)
            let page = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "page" }?.value
            if page == "2" { throw GitHubError.transport("connection reset") }
            return (200, ["Link": "<https://api.github.com/x?page=2>; rel=\"next\""], Data((self?.issueJSON([1]) ?? "[]").utf8))
        }
        do {
            _ = try await client.listIssues(repository, limit: 100)
            XCTFail("an incomplete listing must not be returned")
        } catch let error as GitHubError {
            XCTAssertEqual(error, .transport("connection reset"))
        }
    }

}

/// Awaits a throwing expression and asserts it threw. `XCTAssertThrowsError`
/// cannot be used with `await` directly.
func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ handler: (Error) -> Void = { _ in }
) async {
    do {
        _ = try await expression()
        XCTFail("expected an error", file: file, line: line)
    } catch {
        handler(error)
    }
}