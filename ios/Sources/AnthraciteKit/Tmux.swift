import Foundation

/// A tmux session as the host reports it. tmux owns durable work; the app only
/// ever attaches, detaches or stops a session it can identify by *both* name and
/// id, so a recycled name cannot silently point at someone else's work.
public struct TmuxSession: Equatable, Sendable {
    public let name: String
    public let id: String
    public let windows: Int
    public let attached: Bool
    public let created: Int

    public init(name: String, id: String, windows: Int, attached: Bool, created: Int) {
        self.name = name
        self.id = id
        self.windows = windows
        self.attached = attached
        self.created = created
    }
}

public enum Tmux {
    /// A tab-separated format is unambiguous even for names containing `|`.
    public static let listFormat =
        "#{session_name}\t#{session_id}\t#{session_windows}\t#{session_attached}\t#{session_created}"

    public static func parseSessions(_ output: String) -> [TmuxSession] {
        var sessions: [TmuxSession] = []
        for line in output.split(separator: "\n", omittingEmptySubsequences: true) {
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count >= 5 else { continue }
            let name = String(fields[0])
            let id = String(fields[1])
            guard let windows = Int(fields[2]),
                  let attached = Int(fields[3]),
                  let created = Int(fields[4]),
                  isValidName(name),
                  id.hasPrefix("$"),
                  id.dropFirst().allSatisfy({ $0.isNumber }) else { continue }
            sessions.append(
                TmuxSession(name: name, id: id, windows: windows, attached: attached != 0, created: created)
            )
        }
        return sessions
    }

    /// Names with control characters or a line break would corrupt the listing;
    /// everything else is quoted rather than rejected.
    public static func isValidName(_ name: String) -> Bool {
        guard !name.isEmpty, name.count <= 64 else { return false }
        return !name.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    public static func isValidId(_ id: String) -> Bool {
        guard id.hasPrefix("$"), id.count > 1, id.count <= 12 else { return false }
        return id.dropFirst().allSatisfy { $0.isNumber }
    }

    /// POSIX single-quote quoting: the only way to pass an arbitrary name to a
    /// shell without interpolation.
    public static func quoted(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    public static func listCommand() -> String {
        "tmux list-sessions -F \(quoted(listFormat))"
    }

    /// Attaching uses the id when it is known, so a renamed session still maps
    /// to the same work.
    public static func attachCommand(_ session: TmuxSession) -> String? {
        guard isValidId(session.id), isValidName(session.name) else { return nil }
        return "tmux attach-session -t \(quoted(session.id))"
    }

    public static func hasSessionCommand(_ session: TmuxSession) -> String? {
        guard isValidId(session.id) else { return nil }
        return "tmux has-session -t \(quoted(session.id))"
    }

    /// Stopping is verified with `has-session` afterwards: a successful kill
    /// syscall is not the same as a session that is gone.
    public static func stopCommand(_ session: TmuxSession) -> String? {
        guard isValidId(session.id) else { return nil }
        return "tmux kill-session -t \(quoted(session.id))"
    }

    public static func newSessionCommand(name: String) -> String? {
        guard isValidName(name) else { return nil }
        return "tmux new-session -d -s \(quoted(name))"
    }

    /// The one place that answers "is this the session the user meant?".
    public static func identityMatches(_ listed: [TmuxSession], name: String, id: String) -> Bool {
        listed.contains { $0.name == name && $0.id == id }
    }
}
