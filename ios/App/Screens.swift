import SwiftUI
import AnthraciteKit

@main
struct AnthraciteRemoteApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(.accentColor)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            switch model.connectionState {
            case .connected(_):
                WorkspaceView()
            case .connecting(let name):
                VStack(spacing: 16) {
                    ProgressView()
                    Text("Connecting to \(name)…")
                        .font(.headline)
                    Text("The host key is checked before a single credential is sent.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
            case .disconnected:
                ConnectionsView()
            }
        }
        .sheet(item: $model.pendingHostKey) { pending in
            HostKeySheet(pending: pending)
                .environmentObject(model)
                .interactiveDismissDisabled()
        }
        .alert(item: $model.banner) { banner in
            Alert(
                title: Text(banner.isError ? "Something needs attention" : "Note"),
                message: Text(banner.text),
                dismissButton: .default(Text("OK"))
            )
        }
    }
}

// MARK: - Connections

struct ConnectionsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var editing: ServerProfile?
    @State private var showingNew = false

    var body: some View {
        NavigationStack {
            List {
                if let error = model.profileStoreError {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }
                Section("Servers") {
                    if model.profiles.isEmpty {
                        ContentUnavailableView(
                            "No servers yet",
                            systemImage: "server.rack",
                            description: Text("Add the Linux machine you already reach over SSH. Anthracite never asks for a cloud account.")
                        )
                    }
                    ForEach(model.profiles) { profile in
                        Button {
                            Task { await model.prepareConnection(profile) }
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(profile.name).font(.headline)
                                Text("\(profile.username)@\(profile.host):\(profile.port)")
                                    .font(.footnote.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                model.delete(profile)
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                            Button {
                                editing = profile
                            } label: {
                                Label("Edit", systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                    }
                }
                Section {
                    Button {
                        showingNew = true
                    } label: {
                        Label("Add a server", systemImage: "plus")
                    }
                }
            }
            .navigationTitle("Anthracite")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if model.busy { ProgressView() }
                }
            }
            .sheet(isPresented: $showingNew) {
                ProfileEditor(profile: nil)
                    .environmentObject(model)
            }
            .sheet(item: $editing) { profile in
                ProfileEditor(profile: profile)
                    .environmentObject(model)
            }
        }
    }
}

struct ProfileEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var profile: ServerProfile
    @State private var secret = ""
    @State private var validation: String?
    private let isNew: Bool

    init(profile: ServerProfile?) {
        if let profile {
            _profile = State(initialValue: profile)
            isNew = false
        } else {
            _profile = State(initialValue: ServerProfile(name: "", host: "", username: "", authKind: .password))
            isNew = true
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Name", text: $profile.name)
                        .textContentType(.nickname)
                    TextField("Host name or IP address", text: $profile.host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Port", value: $profile.port, format: .number)
                        .keyboardType(.numberPad)
                    TextField("SSH user", text: $profile.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Authentication") {
                    Picker("Method", selection: $profile.authKind) {
                        Text("Password").tag(ServerAuthKind.password)
                        Text("Ed25519 key").tag(ServerAuthKind.privateKey)
                    }
                    .pickerStyle(.segmented)
                    if profile.authKind == .password {
                        SecureField("Password", text: $secret)
                    } else {
                        Text("Paste an unencrypted OpenSSH Ed25519 private key. Encrypted keys are not supported yet.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        TextEditor(text: $secret)
                            .frame(minHeight: 120)
                            .font(.footnote.monospaced())
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    Text("The secret is stored in the iOS Keychain, marked this-device-only. It never leaves the phone.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let validation {
                    Section {
                        Text(validation).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(isNew ? "New server" : "Edit server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let problem = profile.validate() {
                            validation = problem
                            return
                        }
                        if profile.name.trimmingCharacters(in: .whitespaces).isEmpty {
                            profile.name = profile.host
                        }
                        // An empty field keeps the existing secret: leaving it
                        // blank must not silently erase a working credential.
                        model.save(profile: profile, secret: secret.isEmpty ? nil : secret)
                        dismiss()
                    }
                }
            }
        }
    }
}

struct HostKeySheet: View {
    @EnvironmentObject private var model: AppModel
    let pending: AppModel.PendingHostKey
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Verify the host key")
                    .font(.title2.bold())
                Text("Compare this fingerprint with the one the server itself reports, for example with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the machine or on a screen you already trust.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(pending.key.fingerprint)
                    .font(.system(.body, design: .monospaced))
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                    .textSelection(.enabled)
                    .accessibilityLabel("Host key fingerprint \(pending.key.fingerprint)")
                Button {
                    UIPasteboard.general.string = pending.key.fingerprint
                    copied = true
                } label: {
                    Label(copied ? "Fingerprint copied" : "Copy fingerprint", systemImage: "doc.on.doc")
                }
                Text("\(pending.profile.username)@\(pending.profile.host):\(pending.profile.port) — \(pending.key.algorithm)")
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    Task { await model.approvePendingHostKey() }
                } label: {
                    Text("I verified this fingerprint").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button(role: .destructive) {
                    model.rejectPendingHostKey()
                } label: {
                    Text("Cancel connection").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .padding()
        }
    }
}

// MARK: - Workspace

struct WorkspaceView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            TerminalTabView()
                .tabItem { Label("Terminal", systemImage: "terminal") }
            FilesScreen()
                .tabItem { Label("Files", systemImage: "folder") }
            GitScreen()
                .tabItem { Label("Git", systemImage: "point.topleft.down.curvedto.point.bottomright.up") }
            GitHubScreen()
                .tabItem { Label("GitHub", systemImage: "chevron.left.forwardslash.chevron.right") }
            DeployScreen()
                .tabItem { Label("Deploy", systemImage: "shippingbox") }
            ProvisionScreen()
                .tabItem { Label("Set up", systemImage: "wrench.and.screwdriver") }
        }
    }
}

struct TerminalTabView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingSessions = false
    @State private var sessions: [TmuxSession] = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                tabStrip
                if let tab = model.activeTab {
                    TerminalSurface(tab: tab, model: model)
                        .id(tab.id)
                        .overlay(alignment: .bottomTrailing) {
                            if tab.exited {
                                Text("This channel ended")
                                    .font(.caption)
                                    .padding(8)
                                    .background(.thinMaterial, in: Capsule())
                                    .padding()
                            }
                        }
                    keyBar(for: tab)
                } else {
                    ContentUnavailableView(
                        "No terminal",
                        systemImage: "terminal",
                        description: Text("Open a shell or attach to a tmux session on the host.")
                    )
                }
            }
            .navigationTitle(model.activeTab?.title ?? "Terminal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        Task {
                            do {
                                sessions = try await model.tmuxSessions()
                                showingSessions = true
                            } catch {
                                model.banner = AppModel.Banner(
                                    text: "The host's tmux sessions could not be read: \(error)",
                                    isError: true
                                )
                            }
                        }
                    } label: {
                        Label("Sessions", systemImage: "rectangle.stack")
                    }
                    Button {
                        Task { await model.openTab(command: nil, title: "Shell") }
                    } label: {
                        Label("New tab", systemImage: "plus")
                    }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Disconnect") {
                        Task { await model.disconnect() }
                    }
                }
            }
            .sheet(isPresented: $showingSessions) {
                TmuxSessionsSheet(sessions: sessions)
                    .environmentObject(model)
            }
        }
    }

    private var tabStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.tabs) { tab in
                    Button {
                        model.activeTabID = tab.id
                    } label: {
                        HStack(spacing: 6) {
                            Text(tab.title).lineLimit(1)
                            Button {
                                model.close(tab)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Close \(tab.title)")
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(
                            model.activeTabID == tab.id ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.1),
                            in: Capsule()
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(.bar)
    }

    private func keyBar(for tab: TerminalTab) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                keyButton("Ctrl-C") { model.send(key: .character("c"), modifiers: [.ctrl], to: tab) }
                keyButton("Ctrl-D") { model.send(key: .character("d"), modifiers: [.ctrl], to: tab) }
                keyButton("Esc") { model.send(key: .escape, modifiers: [], to: tab) }
                keyButton("Tab") { model.send(key: .tab, modifiers: [], to: tab) }
                keyButton("↑") { model.send(key: .arrowUp, modifiers: [], to: tab) }
                keyButton("↓") { model.send(key: .arrowDown, modifiers: [], to: tab) }
                keyButton("←") { model.send(key: .arrowLeft, modifiers: [], to: tab) }
                keyButton("→") { model.send(key: .arrowRight, modifiers: [], to: tab) }
                if tab.tmux != nil {
                    keyButton("Detach") { model.detach(tab) }
                }
                Button {
                    Task { await model.openTab(command: "tmux list-sessions", title: "tmux") }
                } label: {
                    Label("List tmux", systemImage: "list.bullet")
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(.bar)
    }

    private func keyButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.callout.monospaced())
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(title)
    }
}

struct TmuxSessionsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let sessions: [TmuxSession]

    var body: some View {
        NavigationStack {
            List(sessions) { session in
                VStack(alignment: .leading, spacing: 4) {
                    Text(session.name).font(.headline)
                    Text("\(session.id) · \(session.windows) windows · \(session.attached ? "attached" : "detached")")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    HStack {
                        Button("Attach") {
                            Task {
                                dismiss()
                                await model.attach(tmuxSession: session)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        Button("Stop session", role: .destructive) {
                            Task { await model.stop(tmuxSession: session) }
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.vertical, 4)
            }
            .overlay {
                if sessions.isEmpty {
                    ContentUnavailableView(
                        "No tmux sessions",
                        systemImage: "rectangle.stack.badge.minus",
                        description: Text("Create one from a terminal tab. Work started there survives a dropped connection, but not a reboot.")
                    )
                }
            }
            .navigationTitle("Host sessions")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

extension TmuxSession: Identifiable {}

// MARK: - Files

struct FilesScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var path = ""
    @State private var entries: [RemoteEntry] = []
    @State private var message: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List(entries, id: \.path) { entry in
                Button {
                    if entry.isDirectory {
                        path = entry.path
                        Task { await load() }
                    } else {
                        message = "\(entry.name) · \(entry.size) bytes"
                    }
                } label: {
                    HStack {
                        Image(systemName: entry.isDirectory ? "folder" : (entry.isSymlink ? "link" : "doc"))
                        VStack(alignment: .leading) {
                            Text(entry.name).lineLimit(1)
                            if !entry.isDirectory {
                                Text(ByteCountFormatter.string(fromByteCount: Int64(entry.size), countStyle: .file))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .overlay {
                if entries.isEmpty && !busy {
                    ContentUnavailableView(
                        "Browse the host",
                        systemImage: "folder",
                        description: Text("Start at your home directory. Paths stay absolute and cannot climb above the directory you chose.")
                    )
                }
            }
            .navigationTitle(path.isEmpty ? "Files" : (path as NSString).lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        guard let parent = RemotePath.parent(path) else { return }
                        path = parent
                        Task { await load() }
                    } label: {
                        Label("Up", systemImage: "chevron.up")
                    }
                    .disabled(path.isEmpty || path == "/")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await load() }
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                }
            }
            .task {
                if path.isEmpty {
                    do { path = try await model.exec("printf '%s' \"$HOME\"").stdout.trimmingCharacters(in: .whitespacesAndNewlines) }
                    catch { message = model.describe(error) }
                }
                await load()
            }
            .alert("Files", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") { message = nil }
            } message: {
                Text(message ?? "")
            }
        }
    }

    private func load() async {
        guard !path.isEmpty else { return }
        busy = true
        defer { busy = false }
        do {
            let sftp = try await model.openSFTP()
            entries = try await sftp.listDirectory(path)
            await sftp.close()
        } catch {
            entries = []
            message = model.describe(error)
        }
    }
}

// MARK: - Git

struct GitScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var path = ""
    @State private var status: Git.Status?
    @State private var message = ""
    @State private var output: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Repository") {
                    TextField("Absolute path on the host", text: $path)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Refresh") { Task { await refresh() } }
                        .disabled(busy || path.isEmpty)
                }
                if let status {
                    Section("Branch") {
                        Text(status.branch.isEmpty ? "detached" : status.branch)
                        if !status.upstream.isEmpty {
                            Text("upstream \(status.upstream) · +\(status.ahead) −\(status.behind)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Section("Changes") {
                        if status.entries.isEmpty { Text("Working tree clean").foregroundStyle(.secondary) }
                        ForEach(status.entries, id: \.path) { entry in
                            HStack {
                                Text(entry.code).font(.footnote.monospaced()).foregroundStyle(.secondary)
                                Text(entry.path).lineLimit(1)
                            }
                            .swipeActions {
                                Button("Stage") { Task { await run(Git.stageCommand(at: path, file: entry.path)) } }
                                    .tint(.green)
                                Button("Unstage") { Task { await run(Git.unstageCommand(at: path, file: entry.path)) } }
                                    .tint(.orange)
                            }
                        }
                    }
                    Section("Commit") {
                        TextField("Message", text: $message, axis: .vertical)
                        Button("Commit staged changes") { Task { await commit() } }
                            .disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    Section("Remote") {
                        Button("Fetch") { Task { await run(Git.fetchCommand(at: path)) } }
                        Button("Pull (fast-forward only)") { Task { await run(Git.pullCommand(at: path)) } }
                        Button("Push") {
                            Task { await run(Git.pushCommand(at: path, remote: "origin", branch: status.branch, forceWithLease: false)) }
                        }
                        .disabled(status.branch.isEmpty)
                    }
                }
                if let output {
                    Section("Last output") {
                        Text(output).font(.footnote.monospaced()).lineLimit(30)
                    }
                }
            }
            .navigationTitle("Git")
        }
    }

    private func refresh() async {
        guard let command = Git.statusCommand(at: path) else {
            output = "Enter an absolute repository path."
            return
        }
        busy = true
        defer { busy = false }
        let result = await model.execShell(command)
        status = Git.parseStatus(result.stdout)
        output = result.stderr.isEmpty ? nil : result.stderr
    }

    private func run(_ command: String?) async {
        guard let command else { return }
        let result = await model.execShell(command)
        output = (result.stdout + "\n" + result.stderr).trimmingCharacters(in: .whitespacesAndNewlines)
        await refresh()
    }

    private func commit() async {
        guard let connection = model.connection, let command = Git.commitCommand(at: path) else { return }
        do {
            let result = try await connection.exec(command, input: Data(message.utf8))
            output = result.stderr.isEmpty ? result.stdout : result.stderr
            message = ""
            await refresh()
            _ = connection
        } catch {
            output = model.describe(error)
        }
    }
}

// MARK: - GitHub

struct GitHubScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var token = ""
    @State private var repository = ""
    @State private var issues: [IssueSummary] = []
    @State private var selected: IssueSummary?
    @State private var error: String?
    @State private var busy = false
    /// How many issues to load. The client pages until this many arrive, so the
    /// default screen is the first 30 and Load More explicitly asks for the next
    /// 30 instead of implying the first page is the whole list.
    @State private var limit = 30

    var body: some View {
        NavigationStack {
            Form {
                Section("Access") {
                    SecureField("Fine-grained token", text: $token)
                    TextField("owner/repo", text: $repository)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Save and list issues") { Task { await saveAndLoad() } }
                        .disabled(token.isEmpty || repository.isEmpty || busy)
                    Text("The token is stored in the Keychain. Anthracite only calls api.github.com with it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
                Section("Open issues") {
                    if issues.isEmpty && !busy {
                        Text("Nothing loaded yet.").foregroundStyle(.secondary)
                    } else {
                        Text("Showing \(issues.count) open issues.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(issues) { issue in
                        Button {
                            selected = issue
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("#\(issue.number) \(issue.title)").lineLimit(2)
                                Text("\(issue.author) · \(issue.commentCount) comments")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    if !issues.isEmpty && !busy && issues.count >= limit && limit < Self.maxIssues {
                        Button("Load more") {
                            limit = min(limit + 30, Self.maxIssues)
                            Task { await saveAndLoad(save: false) }
                        }
                    }
                }
            }
            .navigationTitle("GitHub")
            .task {
                token = model.loadSecret("github.token") ?? ""
                repository = model.loadSecret("github.repository") ?? ""
                if !token.isEmpty && !repository.isEmpty { await saveAndLoad(save: false) }
            }
            .sheet(item: $selected) { issue in
                IssueDetailSheet(issue: issue, token: token, repository: repository)
                    .environmentObject(model)
            }
        }
    }

    private static var maxIssues: Int { 300 }

    private func saveAndLoad(save: Bool = true) async {
        error = nil
        guard let ref = RepositoryRef.parse(repository) else {
            error = "Use the owner/repo form, for example anthracite-labs/Anthracite-Remote-Android."
            return
        }
        if save {
            limit = 30
            model.saveSecret(token, key: "github.token")
            model.saveSecret(repository, key: "github.repository")
        }
        busy = true
        defer { busy = false }
        let client = GitHubClient(token: token)
        do {
            issues = try await client.listIssues(ref, state: "open", limit: limit)
        } catch {
            issues = []
            self.error = "\(error)"
        }
    }
}

struct IssueDetailSheet: View {
    @EnvironmentObject private var model: AppModel
    let issue: IssueSummary
    let token: String
    let repository: String
    @State private var detail: IssueDetail?
    @State private var comments: [IssueComment] = []
    @State private var draft = ""
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(issue.title).font(.title3.bold())
                    if let detail {
                        Text(detail.body.isEmpty ? "No description." : detail.body)
                            .font(.callout)
                        Text("State: \(detail.state)").font(.footnote).foregroundStyle(.secondary)
                    }
                    Divider()
                    ForEach(comments) { comment in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(comment.author).font(.caption.bold())
                            Text(comment.body).font(.callout)
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
                    }
                    if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                }
                .padding()
            }
            .safeAreaInset(edge: .bottom) {
                HStack {
                    TextField("Comment", text: $draft, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                    Button("Send") { Task { await comment() } }
                        .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding()
                .background(.bar)
            }
            .navigationTitle("#\(issue.number)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                let client = GitHubClient(token: token)
                guard let ref = RepositoryRef.parse(repository) else { return }
                do {
                    detail = try await client.issue(ref, number: issue.number)
                    comments = try await client.comments(ref, number: issue.number)
                } catch {
                    self.error = "\(error)"
                }
            }
        }
    }

    private func comment() async {
        guard let ref = RepositoryRef.parse(repository) else { return }
        let client = GitHubClient(token: token)
        do {
            try await client.comment(ref, number: issue.number, body: draft)
            draft = ""
            comments = try await client.comments(ref, number: issue.number)
            error = nil
        } catch {
            self.error = "\(error)"
        }
    }
}

// MARK: - Deploy

struct DeployScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var runtime: ContainerRuntime = .docker
    @State private var containers = ""
    @State private var logs = ""
    @State private var busy = false
    @State private var selectedName = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Container runtime") {
                    Picker("Runtime", selection: $runtime) {
                        ForEach(ContainerRuntime.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Button("List containers") { Task { await list() } }
                        .disabled(busy)
                    Text("Access to the Docker or Podman socket is equivalent to root on this host. Anthracite never forwards the socket to the phone.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if !containers.isEmpty {
                    Section("Containers") {
                        Text(containers).font(.footnote.monospaced()).textSelection(.enabled)
                    }
                }
                Section("Selected container") {
                    TextField("Name", text: $selectedName)
                        .textInputAutocapitalization(.never)
                    Button("Show logs") { Task { await showLogs() } }
                    Button("Remove (only if Anthracite created it)", role: .destructive) { Task { await remove() } }
                        .disabled(selectedName.isEmpty)
                }
                if !logs.isEmpty {
                    Section("Logs") {
                        ScrollView { Text(logs).font(.footnote.monospaced()) }.frame(maxHeight: 260)
                    }
                }
            }
            .navigationTitle("Deployments")
        }
    }

    private func list() async {
        busy = true
        defer { busy = false }
        let result = await model.execShell("\(runtime.rawValue) ps --format '{{.Names}}\\t{{.Image}}\\t{{.Status}}'")
        containers = result.succeeded ? result.stdout : result.stderr
    }

    private func showLogs() async {
        guard let command = try? Deploy.containerLogsCommand(runtime: runtime, name: selectedName, tail: 200) else { return }
        let result = await model.execShell(command)
        logs = result.succeeded ? result.stdout : result.stderr
    }

    private func remove() async {
        guard let connection = model.connection,
              let check = try? Deploy.inspectLabelCommand(runtime: runtime, name: selectedName),
              let remove = try? Deploy.containerRemoveCommand(runtime: runtime, name: selectedName) else { return }
        do {
            let label = try await connection.exec(check)
            guard label.succeeded, label.stdout.trimmingCharacters(in: .whitespacesAndNewlines) == "1" else {
                logs = "This container was not created by Anthracite, so it was left alone."
                return
            }
            let result = try await connection.exec(remove)
            logs = result.succeeded ? "Removed \(selectedName)." : result.stderr
            await list()
        } catch {
            logs = model.describe(error)
        }
    }
}

// MARK: - Provisioning

struct ProvisionScreen: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var flow = ProvisioningViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("Host") {
                    if let facts = flow.facts {
                        Text("\(facts.osPrettyName) (\(facts.osId) \(facts.osVersion), \(facts.arch))")
                        Text("Manager: \(facts.manager?.display ?? "none detected")")
                        Text("User: \(facts.isRoot ? "root" : "normal user") · sudo: \(facts.sudoNonInteractive ? "passwordless" : "not available")")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text(facts.home.isEmpty ? "Home: unknown" : "Home: \(facts.home)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Probe the host to see what it can install.").foregroundStyle(.secondary)
                    }
                    Button("Probe host") { Task { await flow.refreshFacts() } }
                        .disabled(flow.busy)
                }
                if flow.facts != nil {
                    Section("Tools") {
                        ForEach(ProvisionCatalog.tools, id: \.id) { tool in
                            Toggle(tool.label, isOn: Binding(
                                get: { flow.selected.contains(tool.id) },
                                set: { isOn in
                                    if isOn { flow.selected.insert(tool.id) } else { flow.selected.remove(tool.id) }
                                    Task { await flow.buildPlan() }
                                }
                            ))
                        }
                    }
                    Section("Manual installers") {
                        ForEach(ProvisionCatalog.manualInstallers, id: \.id) { installer in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(installer.label).font(.headline)
                                Text(installer.reason).font(.footnote).foregroundStyle(.secondary)
                                Text(installer.instructions).font(.footnote)
                            }
                        }
                    }
                    if let plan = flow.plan, let verdict = flow.verdict {
                        Section("Plan") {
                            if plan.steps.isEmpty {
                                Text("Nothing to install in this selection.").foregroundStyle(.secondary)
                            }
                            ForEach(ProvisionPlanner.previewCommands(plan), id: \.self) { command in
                                Text(command).font(.footnote.monospaced()).textSelection(.enabled)
                            }
                            Text(verdict.explanation).font(.footnote)
                            ForEach(plan.warnings, id: \.self) { warning in
                                Text(warning).font(.caption).foregroundStyle(.secondary)
                            }
                            if case .allowedUnverified = verdict.decision {
                                Toggle("I reviewed these commands and accept the risk", isOn: $flow.acknowledge)
                                    .onChange(of: flow.acknowledge) { _, _ in Task { await flow.buildPlan() } }
                            }
                        }
                        Section("Run") {
                            Button("Install with the reviewed runner") { Task { await flow.start() } }
                                .disabled(!verdict.canLaunch || flow.busy)
                            if let status = flow.status {
                                Text("State: \(status.effectiveState.rawValue) · step \(status.step) · pid \(status.pid ?? "—")")
                                if status.effectiveState == .unknown {
                                    Text("The host cannot prove this process is still its own, so it is reported as unknown rather than running.")
                                        .font(.footnote)
                                        .foregroundStyle(.orange)
                                }
                                Text(status.logTail).font(.caption.monospaced()).lineLimit(12)
                            }
                            HStack {
                                Button("Refresh status") { Task { await flow.refreshStatus() } }
                                Button("Request stop", role: .destructive) { Task { await flow.cancel() } }
                                    .disabled(flow.status == nil)
                            }
                            if let outcome = flow.cancelOutcome {
                                Text(outcome.explanation).font(.footnote)
                            }
                        }
                    }
                }
                if let message = flow.message {
                    Section { Text(message).font(.footnote) }
                }
            }
            .navigationTitle("Set up tools")
            .onAppear {
                flow.attach(model: model)
            }
        }
    }
}
