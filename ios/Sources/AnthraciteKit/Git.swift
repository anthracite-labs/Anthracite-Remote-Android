import Foundation

/// Host-side Git through the user's own `git`. Nothing here reimplements Git:
/// the app quotes a path, runs a command, and parses the machine-readable output.
public enum Git {
    public static func quote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Absolute POSIX path, no control characters, no NUL.
    public static func isValidPath(_ path: String) -> Bool {
        guard path.hasPrefix("/"), path.count <= 4096, path != "/" else { return false }
        if path.contains("\u{0}") { return false }
        guard !path.unicodeScalars.contains(where: { $0.value < 0x20 }) else { return false }
        // A repository path is a place, not a traversal: `..` components would
        // let one tap change which repository a command runs in.
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        return !components.contains { $0 == ".." || $0 == "." }
    }

    public static func isValidRef(_ ref: String) -> Bool {
        guard !ref.isEmpty, ref.count <= 255, !ref.contains(".."), !ref.contains(" ") else { return false }
        if ref.hasPrefix("-") || ref.hasPrefix("/") || ref.hasSuffix("/") { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_./@{}~^")
        return ref.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    public static func statusCommand(at path: String) -> String? {
        guard isValidPath(path) else { return nil }
        return "cd -- \(quote(path)) && git -c core.quotepath=false status --porcelain=v2 --branch --untracked-files=all"
    }

    public static func diffCommand(at path: String, file: String? = nil) -> String? {
        guard isValidPath(path) else { return nil }
        var command = "cd -- \(quote(path)) && git -c core.quotepath=false diff --no-color"
        if let file {
            guard !file.isEmpty, !file.hasPrefix("/"), !file.contains(".."), !file.contains("\u{0}") else { return nil }
            command += " -- \(quote(file))"
        }
        return command
    }

    public static func logCommand(at path: String, limit: Int = 50) -> String? {
        guard isValidPath(path) else { return nil }
        let capped = max(1, min(limit, 200))
        return "cd -- \(quote(path)) && git -c core.quotepath=false log -n \(capped) --format=%H%x09%an%x09%aI%x09%s"
    }

    /// The commit message arrives on stdin: an arbitrary message is bounded by
    /// the channel, never spliced into a command string.
    public static func commitCommand(at path: String) -> String? {
        guard isValidPath(path) else { return nil }
        return "cd -- \(quote(path)) && git commit --file -"
    }

    public static func stageCommand(at path: String, file: String) -> String? {
        guard isValidPath(path), validRelative(file) else { return nil }
        return "cd -- \(quote(path)) && git add -- \(quote(file))"
    }

    public static func unstageCommand(at path: String, file: String) -> String? {
        guard isValidPath(path), validRelative(file) else { return nil }
        return "cd -- \(quote(path)) && git restore --staged -- \(quote(file))"
    }

    public static func pullCommand(at path: String) -> String? {
        guard isValidPath(path) else { return nil }
        return "cd -- \(quote(path)) && git pull --ff-only"
    }

    public static func fetchCommand(at path: String) -> String? {
        guard isValidPath(path) else { return nil }
        return "cd -- \(quote(path)) && git fetch --prune"
    }

    public static func pushCommand(at path: String, remote: String, branch: String, forceWithLease: Bool) -> String? {
        guard isValidPath(path), isValidRef(remote), isValidRef(branch) else { return nil }
        let lease = forceWithLease ? " --force-with-lease" : ""
        return "cd -- \(quote(path)) && git push\(lease) -- \(quote(remote)) \(quote(branch))"
    }

    public static func checkoutCommand(at path: String, branch: String) -> String? {
        guard isValidPath(path), isValidRef(branch) else { return nil }
        return "cd -- \(quote(path)) && git switch -- \(quote(branch))"
    }

    private static func validRelative(_ file: String) -> Bool {
        guard !file.isEmpty, !file.hasPrefix("/"), !file.contains(".."), !file.contains("\u{0}") else { return false }
        return !file.unicodeScalars.contains { $0.value < 0x20 }
    }

    // MARK: - Parsing

    public struct StatusEntry: Equatable, Sendable {
        public let path: String
        public let kind: String
        /// Two-letter porcelain code, e.g. "M.", "??".
        public let code: String
    }

    public struct Status: Equatable, Sendable {
        public var branch = ""
        public var upstream = ""
        public var ahead = 0
        public var behind = 0
        public var entries: [StatusEntry] = []
    }

    public static func parseStatus(_ output: String) -> Status {
        var status = Status()
        for line in output.split(separator: "\n", omittingEmptySubsequences: true) {
            let text = String(line)
            if text.hasPrefix("# branch.head ") {
                status.branch = String(text.dropFirst("# branch.head ".count))
            } else if text.hasPrefix("# branch.upstream ") {
                status.upstream = String(text.dropFirst("# branch.upstream ".count))
            } else if text.hasPrefix("# branch.ab ") {
                let parts = text.dropFirst("# branch.ab ".count).split(separator: " ")
                for part in parts {
                    if part.hasPrefix("+") { status.ahead = Int(part.dropFirst()) ?? 0 }
                    if part.hasPrefix("-") { status.behind = Int(part.dropFirst()) ?? 0 }
                }
            } else if text.hasPrefix("1 ") || text.hasPrefix("2 ") {
                let fields = text.split(separator: " ", omittingEmptySubsequences: false)
                guard fields.count >= 9 else { continue }
                let code = String(fields[1])
                let path = fields.dropFirst(8).joined(separator: " ")
                status.entries.append(StatusEntry(path: path, kind: "tracked", code: code))
            } else if text.hasPrefix("? ") {
                status.entries.append(StatusEntry(path: String(text.dropFirst(2)), kind: "untracked", code: "??"))
            } else if text.hasPrefix("! ") {
                status.entries.append(StatusEntry(path: String(text.dropFirst(2)), kind: "ignored", code: "!!"))
            } else if text.hasPrefix("u ") {
                let fields = text.split(separator: " ", omittingEmptySubsequences: false)
                guard fields.count >= 11 else { continue }
                let code = String(fields[1])
                let path = fields.dropFirst(10).joined(separator: " ")
                status.entries.append(StatusEntry(path: path, kind: "conflict", code: code))
            }
        }
        return status
    }

    public struct LogEntry: Equatable, Sendable {
        public let hash: String
        public let author: String
        public let date: String
        public let subject: String
    }

    public static func parseLog(_ output: String) -> [LogEntry] {
        var entries: [LogEntry] = []
        for line in output.split(separator: "\n", omittingEmptySubsequences: true) {
            let fields = line.split(separator: "\t", maxSplits: 3, omittingEmptySubsequences: false)
            guard fields.count == 4 else { continue }
            let hash = String(fields[0])
            guard hash.count == 40, hash.allSatisfy({ $0.isHexDigit }) else { continue }
            entries.append(
                LogEntry(hash: hash, author: String(fields[1]), date: String(fields[2]), subject: String(fields[3]))
            )
        }
        return entries
    }
}
