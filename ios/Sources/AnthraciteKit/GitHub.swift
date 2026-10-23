import Foundation

public struct RepositoryRef: Equatable, Sendable, Codable {
    public let owner: String
    public let name: String

    public init(owner: String, name: String) {
        self.owner = owner
        self.name = name
    }

    public var slug: String { "\(owner)/\(name)" }

    public static func parse(_ text: String) -> RepositoryRef? {
        let parts = text.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let owner = String(parts[0])
        let name = String(parts[1])
        let pattern = "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$"
        let repoPattern = "^[A-Za-z0-9._-]{1,100}$"
        guard owner.range(of: pattern, options: .regularExpression) != nil,
              name.range(of: repoPattern, options: .regularExpression) != nil else { return nil }
        return RepositoryRef(owner: owner, name: name)
    }
}

public struct IssueSummary: Equatable, Sendable, Identifiable {
    public let number: Int
    public let title: String
    public let state: String
    public let author: String
    public let updatedAt: String
    public let commentCount: Int
    public let isPullRequest: Bool
    public var id: Int { number }
}

public struct IssueDetail: Equatable, Sendable {
    public let number: Int
    public let title: String
    public let state: String
    public let author: String
    public let body: String
    public let commentCount: Int
}

public struct IssueComment: Equatable, Sendable, Identifiable {
    public let id: Int
    public let author: String
    public let body: String
    public let createdAt: String
}

public struct PullRequestSummary: Equatable, Sendable, Identifiable {
    public let number: Int
    public let title: String
    public let state: String
    public let headSHA: String
    public let headRef: String
    public let baseRef: String
    public let draft: Bool
    public var id: Int { number }
}

public struct CheckRunSummary: Equatable, Sendable {
    public let name: String
    public let status: String
    public let conclusion: String?
}

public enum GitHubError: Error, Equatable {
    case invalidToken
    case unauthorized
    case forbidden(String)
    case notFound
    case rateLimited(resetAt: String)
    case unexpectedStatus(Int)
    case malformedResponse
    case invalidHeadSHA
    case mergeBlocked(String)
    /// The collection spans more pages than this client will walk. Reported rather
    /// than returning a prefix as if it were the whole list.
    case incompleteCollection(String)
    case transport(String)
}

/// A GitHub REST client that only ever sends the token it was given to
/// `api.github.com`. Errors never echo the token.
public final class GitHubClient {
    public static let baseURL = URL(string: "https://api.github.com")!

    private let token: String
    private let session: URLSession

    public init(token: String, session: URLSession = .shared) {
        self.token = token
        self.session = session
    }

    // MARK: - Issues

    public func listIssues(
        _ repository: RepositoryRef,
        state: String = "open",
        limit: Int = 30
    ) async throws -> [IssueSummary] {
        let allowed = ["open", "closed", "all"]
        guard allowed.contains(state) else { throw GitHubError.malformedResponse }
        let capped = max(1, min(limit, 100))
        // Pages of up to 100: a limit above that, or a first page that does not
        // fill it, keeps walking while the server offers a next page.
        let array = try await collect(
            path: "/repos/\(repository.slug)/issues?state=\(state)&per_page=\(capped)",
            limit: capped,
            key: { $0["number"].map { "issue-\($0)" } },
            fetch: { path, url in try await self.fetchPage(path: path, url: url, key: nil) }
        )
        return array.compactMap { item in
            guard let number = item["number"] as? Int,
                  let title = item["title"] as? String,
                  let state = item["state"] as? String else { return nil }
            let user = (item["user"] as? [String: Any])?["login"] as? String ?? ""
            let pullRequest = item["pull_request"] != nil
            return IssueSummary(
                number: number,
                title: title,
                state: state,
                author: user,
                updatedAt: item["updated_at"] as? String ?? "",
                commentCount: item["comments"] as? Int ?? 0,
                isPullRequest: pullRequest
            )
        }
    }

    public func issue(_ repository: RepositoryRef, number: Int) async throws -> IssueDetail {
        try validate(number: number)
        let data = try await request(path: "/repos/\(repository.slug)/issues/\(number)")
        guard let item = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let title = item["title"] as? String,
              let state = item["state"] as? String else { throw GitHubError.malformedResponse }
        return IssueDetail(
            number: number,
            title: title,
            state: state,
            author: (item["user"] as? [String: Any])?["login"] as? String ?? "",
            body: item["body"] as? String ?? "",
            commentCount: item["comments"] as? Int ?? 0
        )
    }

    public func comments(_ repository: RepositoryRef, number: Int) async throws -> [IssueComment] {
        try validate(number: number)
        let array = try await collect(
            path: "/repos/\(repository.slug)/issues/\(number)/comments?per_page=100",
            limit: Int.max,
            key: { $0["id"].map { "comment-\($0)" } },
            fetch: { path, url in try await self.fetchPage(path: path, url: url, key: nil) }
        )
        return array.compactMap { item in
            guard let id = item["id"] as? Int, let body = item["body"] as? String else { return nil }
            return IssueComment(
                id: id,
                author: (item["user"] as? [String: Any])?["login"] as? String ?? "",
                body: body,
                createdAt: item["created_at"] as? String ?? ""
            )
        }
    }

    public func createIssue(_ repository: RepositoryRef, title: String, body: String) async throws -> Int {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 256, body.count <= 65536 else {
            throw GitHubError.malformedResponse
        }
        let data = try await request(
            path: "/repos/\(repository.slug)/issues",
            method: "POST",
            json: ["title": trimmed, "body": body]
        )
        guard let item = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let number = item["number"] as? Int else { throw GitHubError.malformedResponse }
        return number
    }

    public func comment(_ repository: RepositoryRef, number: Int, body: String) async throws {
        try validate(number: number)
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, body.count <= 65536 else {
            throw GitHubError.malformedResponse
        }
        _ = try await request(
            path: "/repos/\(repository.slug)/issues/\(number)/comments",
            method: "POST",
            json: ["body": body]
        )
    }

    /// Closing and reopening are the issue state transitions GitHub supports.
    public func setIssueState(_ repository: RepositoryRef, number: Int, state: String) async throws {
        try validate(number: number)
        guard ["open", "closed"].contains(state) else { throw GitHubError.malformedResponse }
        _ = try await request(
            path: "/repos/\(repository.slug)/issues/\(number)",
            method: "PATCH",
            json: ["state": state]
        )
    }

    // MARK: - Pull requests

    public func pullRequests(_ repository: RepositoryRef, state: String = "open") async throws -> [PullRequestSummary] {
        let array = try await collect(
            path: "/repos/\(repository.slug)/pulls?state=\(state)&per_page=100",
            limit: Int.max,
            key: { $0["number"].map { "pr-\($0)" } },
            fetch: { path, url in try await self.fetchPage(path: path, url: url, key: nil) }
        )
        return array.compactMap { item in
            guard let number = item["number"] as? Int,
                  let title = item["title"] as? String,
                  let head = item["head"] as? [String: Any],
                  let sha = head["sha"] as? String,
                  let base = item["base"] as? [String: Any] else { return nil }
            return PullRequestSummary(
                number: number,
                title: title,
                state: item["state"] as? String ?? "",
                headSHA: sha,
                headRef: head["ref"] as? String ?? "",
                baseRef: base["ref"] as? String ?? "",
                draft: item["draft"] as? Bool ?? false
            )
        }
    }

    public func checks(_ repository: RepositoryRef, ref: String) async throws -> [CheckRunSummary] {
        let pattern = "^[A-Za-z0-9._/-]{1,200}$"
        guard ref.range(of: pattern, options: .regularExpression) != nil else {
            throw GitHubError.malformedResponse
        }
        let runs = try await collect(
            path: "/repos/\(repository.slug)/commits/\(ref)/check-runs?per_page=100",
            limit: Int.max,
            key: { $0["id"].map { "run-\($0)" } },
            fetch: { path, url in try await self.fetchPage(path: path, url: url, key: "check_runs") }
        )
        return runs.compactMap { item in
            guard let name = item["name"] as? String else { return nil }
            return CheckRunSummary(
                name: name,
                status: item["status"] as? String ?? "",
                conclusion: item["conclusion"] as? String
            )
        }
    }

    /// A merge always names the commit the user reviewed. GitHub would otherwise
    /// merge whatever the branch points at now, which is not what was approved.
    public func merge(
        _ repository: RepositoryRef,
        number: Int,
        headSHA: String,
        method: String = "merge"
    ) async throws -> String {
        try validate(number: number)
        guard headSHA.range(of: "^[a-f0-9]{40}$", options: .regularExpression) != nil else {
            throw GitHubError.invalidHeadSHA
        }
        guard ["merge", "squash", "rebase"].contains(method) else { throw GitHubError.malformedResponse }
        let data = try await request(
            path: "/repos/\(repository.slug)/pulls/\(number)/merge",
            method: "PUT",
            json: ["sha": headSHA, "merge_method": method]
        )
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GitHubError.malformedResponse
        }
        let merged = object["merged"] as? Bool ?? false
        if !merged {
            throw GitHubError.mergeBlocked(object["message"] as? String ?? "The merge was not performed.")
        }
        return object["sha"] as? String ?? ""
    }

    // MARK: - Transport

    private func validate(number: Int) throws {
        guard number > 0 && number <= 2_000_000_000 else { throw GitHubError.malformedResponse }
    }

    private func request(
        path: String,
        method: String = "GET",
        json: [String: Any]? = nil
    ) async throws -> Data {
        try await requestWithResponse(path: path, method: method, json: json).0
    }

    /// The same request, keeping the response so `Link` headers can be read.
    ///
    /// A continuation URL is absolute and is used as it is; `continuation(from:)` has already
    /// established that it is https on `api.github.com`. Appending it to the base
    /// URL instead would have produced a path that no longer identifies the page.
    private func requestWithResponse(
        path: String = "",
        absoluteURL: URL? = nil,
        method: String = "GET",
        json: [String: Any]? = nil
    ) async throws -> (Data, HTTPURLResponse) {
        guard token.range(of: "^[A-Za-z0-9_]{8,255}$", options: .regularExpression) != nil else {
            throw GitHubError.invalidToken
        }
        let url: URL
        if let absoluteURL {
            url = absoluteURL
        } else {
            guard var components = URLComponents(
                url: Self.baseURL.appendingPathComponent(path),
                resolvingAgainstBaseURL: false
            ) else {
                throw GitHubError.malformedResponse
            }
            components.percentEncodedPath = path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? path
            if let query = path.split(separator: "?", maxSplits: 1).dropFirst().first {
                components.percentEncodedQuery = String(query)
            }
            guard let built = components.url else { throw GitHubError.malformedResponse }
            url = built
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        request.setValue("Anthracite-Remote-iOS", forHTTPHeaderField: "User-Agent")
        if let json {
            guard JSONSerialization.isValidJSONObject(json) else { throw GitHubError.malformedResponse }
            request.httpBody = try JSONSerialization.data(withJSONObject: json)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw GitHubError.malformedResponse }
            switch http.statusCode {
            case 200...299:
                return (data, http)
            case 401:
                throw GitHubError.unauthorized
            case 403, 429:
                if let reset = http.value(forHTTPHeaderField: "X-RateLimit-Reset"),
                   http.value(forHTTPHeaderField: "X-RateLimit-Remaining") == "0" {
                    throw GitHubError.rateLimited(resetAt: reset)
                }
                throw GitHubError.forbidden("GitHub refused this request for the token's permissions.")
            case 404:
                throw GitHubError.notFound
            default:
                throw GitHubError.unexpectedStatus(http.statusCode)
            }
        } catch let error as GitHubError {
            throw error
        } catch is CancellationError {
            // Cancellation is not a transport failure: the caller asked to stop and
            // must be able to tell that apart from a broken connection.
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            throw GitHubError.transport(error.localizedDescription)
        }
    }

    // MARK: - Pagination

    /// How many pages one listing may walk before it is reported as incomplete.
    static let maximumPages = 10

    /// What the server said about the page after this one.
    private enum Continuation {
        /// The listing ends here.
        case end
        /// A validated continuation URL.
        case next(URL)
        /// The server offered a continuation this client will not follow.
        case refused(String)
    }

    /// One page of a listing and what follows it.
    private struct Page {
        let items: [[String: Any]]
        let continuation: Continuation
    }

    /// Walks pages until the listing ends, [limit] items have arrived, or the page
    /// limit is reached.
    ///
    /// Duplicates are dropped by [key]: the same item can appear on two pages when
    /// the underlying list changes between requests. Reaching the page limit while
    /// more pages are still offered is reported as `incompleteCollection` — a
    /// prefix is never returned as if it were the whole list.
    private func collect(
        path: String,
        limit: Int,
        key: ([String: Any]) -> String?,
        fetch: (String, URL?) async throws -> Page
    ) async throws -> [[String: Any]] {
        var items: [[String: Any]] = []
        var seen = Set<String>()
        var visited = Set<URL>()
        var url: URL?
        var pages = 0
        while true {
            let page = try await fetch(path, url)
            pages += 1
            for item in page.items {
                let identifier = key(item) ?? UUID().uuidString
                if seen.insert(identifier).inserted { items.append(item) }
                if items.count >= limit { return items }
            }
            switch page.continuation {
            case .end:
                return items
            case .refused(let detail):
                // The server says there is more, and this client will not follow
                // where it points. Returning what arrived would present a prefix as
                // the whole list.
                throw GitHubError.incompleteCollection(detail)
            case .next(let next):
                guard pages < Self.maximumPages else {
                    throw GitHubError.incompleteCollection(
                        "This list has more than \(Self.maximumPages) pages; it was not shown rather than shown incomplete."
                    )
                }
                // A server that keeps pointing at a page already read is a loop, not
                // a listing. Stop instead of walking it until the page limit.
                guard visited.insert(next).inserted else {
                    throw GitHubError.incompleteCollection(
                        "The server pointed back at a page that was already read, so the list was not shown in full."
                    )
                }
                url = next
            }
        }
    }

    /// Fetches one page: the first page of [path], or an absolute continuation URL
    /// that `continuation(from:)` already validated.
    private func fetchPage(path: String, url: URL?, key: String?) async throws -> Page {
        let (data, response) = try await requestWithResponse(path: path, absoluteURL: url)
        let parsed = try JSONSerialization.jsonObject(with: data)
        let items: [[String: Any]]
        if let key {
            guard let object = parsed as? [String: Any],
                  let listed = object[key] as? [[String: Any]] else { throw GitHubError.malformedResponse }
            items = listed
        } else {
            guard let listed = parsed as? [[String: Any]] else { throw GitHubError.malformedResponse }
            items = listed
        }
        return Page(items: items, continuation: Self.continuation(from: response))
    }

    /// The `rel="next"` URL from a `Link` header, if it is safe to follow.
    ///
    /// A `Link` header is server input. Only https on `api.github.com` is accepted,
    /// with no user-info and no port override: following anything else would send
    /// the token to another host. A malformed or foreign link ends the listing
    /// instead of being trusted.
    private static func continuation(from response: HTTPURLResponse) -> Continuation {
        guard let header = response.value(forHTTPHeaderField: "Link") else { return .end }
        for part in header.split(separator: ",") {
            let sections = part.split(separator: ";")
            guard sections.dropFirst().contains(where: {
                $0.trimmingCharacters(in: .whitespaces) == "rel=\"next\""
            }) else { continue }
            let target = (sections.first ?? "").trimmingCharacters(in: .whitespaces)
            guard target.hasPrefix("<"), target.hasSuffix(">") else {
                return .refused("The server offered a continuation that could not be read, so the list was not shown in full.")
            }
            let raw = String(target.dropFirst().dropLast())
            guard let url = URL(string: raw),
                  url.scheme == "https",
                  url.host == baseURL.host,
                  url.user == nil,
                  url.password == nil,
                  url.port == nil,
                  url.path.hasPrefix("/")
            else {
                return .refused("The server offered a continuation outside this API, which was not followed; the list was not shown in full.")
            }
            return .next(url)
        }
        return .end
    }
}
