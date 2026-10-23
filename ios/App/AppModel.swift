import Foundation
import SwiftUI
import AnthraciteKit

/// One terminal tab. Tabs are independent SSH channels; a tmux attachment is a
/// detach when closed, never a promise that host work stopped.
@MainActor
final class TerminalTab: Identifiable, ObservableObject {
    let id = UUID()
    let title: String
    let screen: TerminalScreen
    let session: ShellSession
    let tmux: TmuxSession?
    @Published var revision = 0
    @Published var exited = false
    var task: Task<Void, Never>?

    init(title: String, screen: TerminalScreen, session: ShellSession, tmux: TmuxSession?) {
        self.title = title
        self.screen = screen
        self.session = session
        self.tmux = tmux
    }

    var keyModes: KeyModes {
        let snapshot = screen.snapshot()
        return KeyModes(
            applicationCursorKeys: snapshot.applicationCursorKeys,
            bracketedPaste: snapshot.bracketedPaste
        )
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var profiles: [ServerProfile] = []
    @Published var profileStoreError: String?
    @Published var connectionState: ConnectionState = .disconnected
    @Published var pendingHostKey: PendingHostKey?
    @Published var tabs: [TerminalTab] = []
    @Published var activeTabID: UUID?
    @Published var banner: Banner?
    @Published var busy = false

    enum ConnectionState: Equatable {
        case disconnected
        case connecting(String)
        case connected(String)
    }

    struct PendingHostKey: Identifiable, Equatable {
        let id = UUID()
        let profile: ServerProfile
        let key: HostKey
    }

    struct Banner: Identifiable, Equatable {
        let id = UUID()
        let text: String
        let isError: Bool
    }

    private let secretStore: SecretStore
    private let profileStore: ProfileStore
    private var hostKeys = HostKeyStore()
    private(set) var connection: SSHConnection?

    var activeTab: TerminalTab? {
        tabs.first { $0.id == activeTabID } ?? tabs.last
    }

    init(secretStore: SecretStore = AppModel.defaultStore()) {
        self.secretStore = secretStore
        self.profileStore = ProfileStore(store: secretStore)
        reload()
    }

    private nonisolated static func defaultStore() -> SecretStore {
        #if canImport(Security)
        if ProcessInfo.processInfo.arguments.contains("-useMemoryStore") {
            return MemorySecretStore()
        }
        return KeychainSecretStore()
        #else
        return MemorySecretStore()
        #endif
    }

    func reload() {
        do {
            profiles = try profileStore.profiles()
            hostKeys = try profileStore.hostKeys()
            profileStoreError = nil
        } catch {
            profiles = []
            profileStoreError = "The saved connections could not be read. Nothing was replaced; restore the Keychain entry or forget it to start over."
        }
    }

    func save(profile: ServerProfile, secret: String?) {
        do {
            try profileStore.save(profile)
            if let secret { try profileStore.saveSecret(secret, for: profile) }
            reload()
        } catch {
            banner = Banner(text: "The connection could not be saved: \(error)", isError: true)
        }
    }

    func delete(_ profile: ServerProfile) {
        do {
            try profileStore.delete(profile.id)
            reload()
        } catch {
            banner = Banner(text: "The connection could not be removed: \(error)", isError: true)
        }
    }

    // MARK: - Connecting

    /// The fingerprint must be compared out of band on first use, and a changed
    /// key stops here until the user says otherwise.
    func prepareConnection(_ profile: ServerProfile) async {
        busy = true
        defer { busy = false }
        do {
            let scanned = try await HostKeyScanner.scan(host: profile.host, port: profile.port)
            switch hostKeys.decide(host: profile.host, port: profile.port, presented: scanned) {
            case .approved:
                try await connect(profile, pin: hostKeys.pin(host: profile.host, port: profile.port)!)
            case .unknownNeedsApproval(let key):
                pendingHostKey = PendingHostKey(profile: profile, key: key)
            case .changed(_, let presented):
                pendingHostKey = PendingHostKey(profile: profile, key: presented)
                banner = Banner(
                    text: "The server's host key has changed. If you did not change it yourself, stop: this can be an interception attempt.",
                    isError: true
                )
            }
        } catch {
            connectionState = .disconnected
            banner = Banner(text: describe(error), isError: true)
        }
    }

    func approvePendingHostKey() async {
        guard let pending = pendingHostKey else { return }
        do {
            hostKeys.approve(host: pending.profile.host, port: pending.profile.port, key: pending.key)
            try profileStore.save(hostKeys: hostKeys)
            pendingHostKey = nil
            try await connect(pending.profile, pin: hostKeys.pin(host: pending.profile.host, port: pending.profile.port)!)
        } catch {
            banner = Banner(text: describe(error), isError: true)
        }
    }

    func rejectPendingHostKey() {
        pendingHostKey = nil
        banner = Banner(text: "The connection was not started. The unknown host key was not saved.", isError: false)
    }

    private func connect(_ profile: ServerProfile, pin: HostKeyPin) async throws {
        connectionState = .connecting(profile.name)
        let secret = try profileStore.secret(for: profile)
        guard let secret, !secret.isEmpty else {
            throw SSHError.authenticationFailed
        }
        let auth: SSHAuth = profile.authKind == .password
            ? .password(secret)
            : .ed25519PrivateKey(secret)
        let connection = try await SSHConnection.connect(
            SSHConfiguration(host: profile.host, port: profile.port, username: profile.username, auth: auth),
            pin: pin
        )
        self.connection = connection
        connectionState = .connected(profile.name)
        await openTab(command: nil, title: profile.name)
    }

    func disconnect() async {
        for tab in tabs {
            tab.task?.cancel()
            await tab.session.close()
        }
        tabs = []
        activeTabID = nil
        await connection?.close()
        connection = nil
        connectionState = .disconnected
    }

    // MARK: - Terminal

    /// Opening a tmux attachment and opening a plain shell are different acts, so
    /// the caller chooses; closing the tab is a detach for the former.
    func openTab(command: String?, title: String = "Shell") async {
        guard let connection else { return }
        let screen = TerminalScreen(columns: 80, rows: 24)
        do {
            let session = try await connection.openShell(columns: screen.columns, rows: screen.rows, environment: ["LANG": "C.UTF-8"])
            let tab = TerminalTab(title: title, screen: screen, session: session, tmux: nil)
            screen.onReply = { [weak session] data in
                Task { try? await session?.write(data) }
            }
            screen.onBlockedClipboardWrite = { [weak self] in
                Task { @MainActor in
                    self?.banner = Banner(text: "A program tried to write the clipboard. The app blocked it; copy explicitly if you want the text.", isError: false)
                }
            }
            screen.onTitle = { [weak tab] title in
                Task { @MainActor in tab?.objectWillChange.send() }
            }
            tabs.append(tab)
            activeTabID = tab.id
            startReading(tab)
            if let command {
                try await session.write(Data((command + "\r").utf8))
            }
        } catch {
            banner = Banner(text: describe(error), isError: true)
        }
    }

    private func startReading(_ tab: TerminalTab) {
        tab.task = Task { [weak self] in
            do {
                for try await chunk in tab.session.output {
                    tab.screen.feed(chunk)
                    tab.revision += 1
                }
            } catch {
                await MainActor.run { self?.banner = Banner(text: "The terminal channel ended: \(error)", isError: true) }
            }
            await MainActor.run { tab.exited = true }
        }
        _ = self
    }

    func send(_ data: Data, to tab: TerminalTab) {
        Task { try? await tab.session.write(data) }
    }

    func send(key: TerminalKey, modifiers: KeyModifiers, to tab: TerminalTab) {
        send(TerminalKeys.sequence(for: key, modifiers: modifiers, modes: tab.keyModes), to: tab)
    }

    func resize(_ tab: TerminalTab, columns: Int, rows: Int) {
        guard columns != tab.screen.columns || rows != tab.screen.rows else { return }
        tab.screen.resize(columns: columns, rows: rows)
        Task { try? await tab.session.resize(columns: columns, rows: rows) }
        tab.revision += 1
    }

    func close(_ tab: TerminalTab) {
        tab.task?.cancel()
        Task { await tab.session.close() }
        tabs.removeAll { $0.id == tab.id }
        if activeTabID == tab.id { activeTabID = tabs.last?.id }
    }

    /// Detaching leaves the host session untouched, and says so.
    func detach(_ tab: TerminalTab) {
        Task {
            await tab.session.detach()
            banner = Banner(text: "Detached. The tmux session keeps running on the host.", isError: false)
        }
    }

    /// Stopping is verified: `has-session` decides, not a return code alone.
    func stop(tmuxSession: TmuxSession) async {
        guard let connection else { return }
        do {
            let listed = try await tmuxSessions()
            guard Tmux.identityMatches(listed, name: tmuxSession.name, id: tmuxSession.id) else {
                banner = Banner(text: "That session is no longer listed, so nothing was stopped.", isError: false)
                return
            }
            if let command = Tmux.stopCommand(tmuxSession) {
                _ = try await connection.exec(command)
            }
            if let check = Tmux.hasSessionCommand(tmuxSession) {
                let result = try await connection.exec(check)
                banner = Banner(
                    text: result.exitCode == 0
                        ? "The session is still running. Nothing is claimed stopped."
                        : "The session is gone; the host confirmed it with has-session.",
                    isError: result.exitCode == 0
                )
            }
        } catch {
            banner = Banner(text: describe(error), isError: true)
        }
    }

    func tmuxSessions() async throws -> [TmuxSession] {
        guard let connection else { return [] }
        let result = try await connection.exec(Tmux.listCommand())
        if result.exitCode != 0 && result.stderr.contains("no server running") { return [] }
        return Tmux.parseSessions(result.stdout)
    }

    // MARK: - Remote commands

    func exec(_ command: String) async throws -> CommandResult {
        guard let connection else { throw SSHError.connectionFailed("Not connected.") }
        return try await connection.exec(command)
    }

    func runScript(_ script: String, arguments: [String]) async throws -> CommandResult {
        guard let connection else { throw SSHError.connectionFailed("Not connected.") }
        return try await connection.runStreamedScript(script, arguments: arguments, timeout: .seconds(180))
    }

    func openSFTP() async throws -> SFTPClient {
        guard let connection else { throw SSHError.connectionFailed("Not connected.") }
        return try await connection.openSFTP()
    }

    func saveSecret(_ value: String, key: String) {
        try? secretStore.set(Data(value.utf8), for: key)
    }

    func loadSecret(_ key: String) -> String? {
        guard let data = try? secretStore.value(for: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func execShell(_ command: String) async -> CommandResult {
        do { return try await exec(command) } catch {
            banner = Banner(text: describe(error), isError: true)
            return CommandResult(stdout: "", stderr: "\(error)", exitCode: 1)
        }
    }

    func describe(_ error: Error) -> String {
        switch error {
        case SSHError.hostKeyChanged(let text): return text
        case SSHError.authenticationFailed: return "The server rejected the credentials."
        case SSHError.timeout: return "The host did not answer in time."
        default: return String(describing: error)
        }
    }
}
