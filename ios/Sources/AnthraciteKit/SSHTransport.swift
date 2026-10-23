import Foundation
import NIOCore
import NIOPosix
import NIOFoundationCompat
import NIOSSH
#if canImport(CryptoKit)
import CryptoKit
#endif

public enum SSHError: Error, Equatable {
    case connectionFailed(String)
    case authenticationFailed
    case hostKeyChanged(String)
    case hostKeyNotApproved(String)
    case channelFailed(String)
    case timeout
    case invalidPrivateKey(String)
}

public enum SSHAuth: Sendable {
    case password(String)
    case ed25519PrivateKey(String)
}

public struct SSHConfiguration: Sendable {
    public var host: String
    public var port: Int
    public var username: String
    public var auth: SSHAuth

    public init(host: String, port: Int, username: String, auth: SSHAuth) {
        self.host = host
        self.port = port
        self.username = username
        self.auth = auth
    }
}

/// Output of a remote command.
public struct CommandResult: Sendable {
    public let stdout: String
    public let stderr: String
    public let exitCode: Int

    public init(stdout: String, stderr: String, exitCode: Int) {
        self.stdout = stdout
        self.stderr = stderr
        self.exitCode = exitCode
    }

    public var succeeded: Bool { exitCode == 0 }
}

// MARK: - Keyscan

/// Reads the server's host key the way `ssh-keyscan` does: version exchange,
/// key-exchange initiation, then the key from the reply.
///
/// This exists because the fingerprint is the only part of host-key pinning a
/// user can verify out of band. The scan is unauthenticated by design: its only
/// output is a key that the user must approve, and that the real connection is
/// then required to present again.
public enum HostKeyScanner {
    public static func scan(host: String, port: Int, timeout: TimeAmount = .seconds(15)) async throws -> HostKey {
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        let collector = ScannerHandler(host: host)
        do {
            let channel = try await ClientBootstrap(group: group)
                .channelInitializer { channel in
                    channel.pipeline.addHandler(collector)
                }
                .connect(host: host, port: port)
                .get()
            try await collector.run(channel: channel, timeout: timeout)
            try await channel.close().get()
            try await group.shutdownGracefully()
            guard let key = collector.key else { throw SSHError.connectionFailed("no host key") }
            return key
        } catch let error as SSHError {
            try? await group.shutdownGracefully()
            throw error
        } catch {
            try? await group.shutdownGracefully()
            throw SSHError.connectionFailed(String(describing: error))
        }
    }
}

private final class ScannerHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer
    typealias OutboundOut = ByteBuffer

    private let host: String
    private var buffer = Data()
    private var sentVersion = false
    private var sentExchange = false
    private var continuation: CheckedContinuation<Void, Error>?
    private var finished = false
    private var channel: Channel?
    private(set) var key: HostKey?

    init(host: String) {
        self.host = host
    }

    func run(channel: Channel, timeout: TimeAmount) async throws {
        self.channel = channel
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(1, timeout.nanoseconds)))
            self?.finish(.failure(SSHError.timeout))
        }
        defer { timeoutTask.cancel() }
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            if self.key != nil { self.finish(.success(())) }
        }
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var incoming = unwrapInboundIn(data)
        if let bytes = incoming.readBytes(length: incoming.readableBytes) {
            buffer.append(contentsOf: bytes)
        }
        process(context: context)
    }

    func channelInactive(context: ChannelHandlerContext) {
        finish(.failure(SSHError.connectionFailed("connection closed before the key exchange")))
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        finish(.failure(SSHError.connectionFailed(String(describing: error))))
        context.close(promise: nil)
    }

    private func finish(_ result: Result<Void, Error>) {
        guard !finished else { return }
        finished = true
        guard let continuation else { return }
        self.continuation = nil
        switch result {
        case .success:
            continuation.resume()
        case .failure(let error):
            continuation.resume(throwing: error)
        }
    }

    private func process(context: ChannelHandlerContext) {
        if !sentVersion {
            guard let range = buffer.range(of: Data("\r\n".utf8)) else {
                if buffer.count > 4096 { finish(.failure(SSHError.connectionFailed("no identification line"))) }
                return
            }
            let line = buffer.subdata(in: 0..<range.lowerBound)
            guard HostKeyWire.identification(line) != nil else {
                finish(.failure(SSHError.connectionFailed("not an SSH server")))
                return
            }
            buffer.removeSubrange(0..<range.upperBound)
            write(Data("SSH-2.0-AnthraciteScan_1.0\r\n".utf8), context: context)
            sentVersion = true
        }
        // Server identification may be preceded by comment lines; the packet
        // stream starts at the first uint32 length.
        while buffer.count >= 4 {
            let length = Int(buffer[buffer.startIndex]) << 24 | Int(buffer[buffer.startIndex + 1]) << 16
                | Int(buffer[buffer.startIndex + 2]) << 8 | Int(buffer[buffer.startIndex + 3])
            guard length > 0, length < 1 << 20 else {
                finish(.failure(SSHError.connectionFailed("invalid packet length")))
                return
            }
            guard buffer.count >= length + 4 else { return }
            let packet = buffer.subdata(in: 4..<(4 + length))
            buffer.removeSubrange(0..<(4 + length))
            let paddingLength = Int(packet[packet.startIndex])
            guard paddingLength > 0, paddingLength + 1 <= packet.count else {
                finish(.failure(SSHError.connectionFailed("invalid padding")))
                return
            }
            let payload = packet.subdata(in: (1 + paddingLength)..<packet.count)
            guard let message = payload.first else { continue }
            if message == 20 && !sentExchange {
                sendExchange(context: context)
            } else if message == 31 {
                do {
                    key = try HostKeyWire.hostKeyFromKeyExchangeReply(payload.dropFirst())
                    finish(.success(()))
                } catch {
                    finish(.failure(SSHError.connectionFailed("unsupported host key")))
                }
                return
            }
        }
    }

    private func sendExchange(context: ChannelHandlerContext) {
        sentExchange = true
        var builder = Data()
        builder.append(30) // SSH_MSG_KEX_ECDH_INIT
        var publicKey = Data()
        #if canImport(CryptoKit)
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        publicKey = ephemeral.publicKey.rawRepresentation
        #endif
        builder.append(uint32: UInt32(publicKey.count))
        builder.append(publicKey)
        write(builder, context: context)
    }

    private func write(_ payload: Data, context: ChannelHandlerContext) {
        var packet = Data()
        let blockSize = 8
        var padding = blockSize - ((payload.count + 1 + 4) % blockSize)
        if padding < 4 { padding += blockSize }
        let length = payload.count + 1 + padding
        packet.append(uint32: UInt32(length))
        packet.append(UInt8(padding))
        packet.append(payload)
        packet.append(Data(repeating: 0, count: padding))
        var buffer = context.channel.allocator.buffer(capacity: packet.count)
        buffer.writeBytes(packet)
        context.writeAndFlush(wrapOutboundOut(buffer), promise: nil)
    }
}

extension Data {
    mutating func append(uint32 value: UInt32) {
        append(UInt8((value >> 24) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8(value & 0xff))
    }
}

// MARK: - OpenSSH private keys

public enum OpenSSHKey {
    /// Parses an unencrypted `openssh-key-v1` Ed25519 private key. Encrypted keys
    /// and non-Ed25519 keys are refused with an explanation rather than a guess.
    public static func ed25519(from text: String) throws -> Data {
        let lines = text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        var base64 = ""
        var inside = false
        for line in lines {
            if line.hasPrefix("-----BEGIN OPENSSH PRIVATE KEY-----") { inside = true; continue }
            if line.hasPrefix("-----END OPENSSH PRIVATE KEY-----") { inside = false; continue }
            if inside { base64 += line }
        }
        guard !base64.isEmpty, let blob = Data(base64Encoded: base64) else {
            throw SSHError.invalidPrivateKey("not an OpenSSH private key")
        }
        let magic = Data("openssh-key-v1\u{0}".utf8)
        guard blob.starts(with: magic) else {
            throw SSHError.invalidPrivateKey("not an openssh-key-v1 file")
        }
        var offset = magic.count
        guard let cipher = HostKeyWire.readString(blob, &offset), cipher == Data("none".utf8) else {
            throw SSHError.invalidPrivateKey("encrypted private keys are not supported")
        }
        guard let kdf = HostKeyWire.readString(blob, &offset), kdf == Data("none".utf8) else {
            throw SSHError.invalidPrivateKey("encrypted private keys are not supported")
        }
        guard HostKeyWire.readString(blob, &offset) != nil else {
            throw SSHError.invalidPrivateKey("malformed key options")
        }
        guard let count = HostKeyWire.readUInt32(blob, &offset), count == 1 else {
            throw SSHError.invalidPrivateKey("exactly one key is required")
        }
        guard HostKeyWire.readString(blob, &offset) != nil else {
            throw SSHError.invalidPrivateKey("missing public key")
        }
        guard let privateBlob = HostKeyWire.readString(blob, &offset) else {
            throw SSHError.invalidPrivateKey("missing private key")
        }
        var inner = 0
        guard HostKeyWire.readUInt32(privateBlob, &inner) == HostKeyWire.readUInt32(privateBlob, &inner) else {
            throw SSHError.invalidPrivateKey("check bytes do not match")
        }
        guard let type = HostKeyWire.readString(privateBlob, &inner), type == Data("ssh-ed25519".utf8) else {
            throw SSHError.invalidPrivateKey("only ssh-ed25519 keys are supported")
        }
        guard HostKeyWire.readString(privateBlob, &inner) != nil else {
            throw SSHError.invalidPrivateKey("missing public half")
        }
        guard let secret = HostKeyWire.readString(privateBlob, &inner), secret.count == 64 else {
            throw SSHError.invalidPrivateKey("unexpected key length")
        }
        return secret.prefix(32)
    }
}

// MARK: - Connection

final class PinDelegate: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let expected: HostKey
    private let onMismatch: (HostKey) -> Void

    init(expected: HostKey, onMismatch: @escaping (HostKey) -> Void) {
        self.expected = expected
        self.onMismatch = onMismatch
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        guard let offered = try? NIOSSHPublicKey(openSSHPublicKey: expected.openSSH), offered == hostKey else {
            onMismatch(expected)
            validationCompletePromise.fail(SSHError.hostKeyChanged("The server presented a different host key."))
            return
        }
        validationCompletePromise.succeed(())
    }
}

final class AuthDelegate: NIOSSHClientUserAuthenticationDelegate, @unchecked Sendable {
    private let username: String
    private let auth: SSHAuth
    private var attempts = 0

    init(username: String, auth: SSHAuth) {
        self.username = username
        self.auth = auth
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard attempts == 0 else {
            nextChallengePromise.succeed(nil)
            return
        }
        attempts += 1
        let offer: NIOSSHUserAuthenticationOffer.Offer
        switch auth {
        case .password(let password):
            guard availableMethods.contains(.password) else {
                nextChallengePromise.succeed(nil)
                return
            }
            offer = .password(.init(password: password))
        case .ed25519PrivateKey(let text):
            #if canImport(CryptoKit)
            guard let seed = try? OpenSSHKey.ed25519(from: text) else {
                nextChallengePromise.fail(SSHError.invalidPrivateKey("the key could not be parsed"))
                return
            }
            guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed) else {
                nextChallengePromise.fail(SSHError.invalidPrivateKey("the key is not a valid ed25519 key"))
                return
            }
            offer = .privateKey(.init(privateKey: NIOSSHPrivateKey(ed25519Key: key)))
            #else
            nextChallengePromise.fail(SSHError.invalidPrivateKey("cryptography unavailable"))
            return
            #endif
        }
        nextChallengePromise.succeed(
            NIOSSHUserAuthenticationOffer(username: username, serviceName: "ssh-connection", offer: offer)
        )
    }
}

/// One SSH connection. Sessions are independent channels: a terminal, an exec,
/// an SFTP subsystem or a stack of them share the transport but not their state.
public final class SSHConnection {
    private let group: EventLoopGroup
    private let channel: Channel
    private let handler: NIOSSHHandler
    private let lock = NSLock()
    private var closed = false

    public let hostKey: HostKey

    private init(group: EventLoopGroup, channel: Channel, handler: NIOSSHHandler, hostKey: HostKey) {
        self.group = group
        self.channel = channel
        self.handler = handler
        self.hostKey = hostKey
    }

    /// Connects, proves the host key is the pinned one, then authenticates.
    public static func connect(
        _ configuration: SSHConfiguration,
        pin: HostKeyPin
    ) async throws -> SSHConnection {
        guard configuration.port > 0, configuration.port < 65536,
              !configuration.host.isEmpty, !configuration.username.isEmpty else {
            throw SSHError.connectionFailed("Invalid host, port or user name.")
        }
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        var mismatch: HostKey?
        let pinDelegate = PinDelegate(expected: pin.key) { presented in
            mismatch = presented
        }
        let authDelegate = AuthDelegate(username: configuration.username, auth: configuration.auth)
        do {
            let channel = try await ClientBootstrap(group: group)
                .channelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
                .connectTimeout(.seconds(20))
                .connect(host: configuration.host, port: configuration.port)
                .get()
            let handler = NIOSSHHandler(
                role: .client(.init(userAuthDelegate: authDelegate, serverAuthDelegate: pinDelegate)),
                allocator: channel.allocator,
                inboundChildChannelInitializer: nil
            )
            do {
                try await channel.pipeline.addHandler(handler).get()
            } catch let error as SSHError {
                try? await channel.close().get()
                try? await group.shutdownGracefully()
                throw error
            } catch {
                try? await channel.close().get()
                try? await group.shutdownGracefully()
                if let presented = mismatch {
                    throw SSHError.hostKeyChanged("The server presented \(presented.fingerprint).")
                }
                throw SSHError.connectionFailed(String(describing: error))
            }
            let connection = SSHConnection(
                group: group,
                channel: channel,
                handler: handler,
                hostKey: pin.key
            )
            return connection
        } catch let error as SSHError {
            try? await group.shutdownGracefully()
            throw error
        } catch {
            try? await group.shutdownGracefully()
            throw SSHError.connectionFailed(String(describing: error))
        }
    }

    public func close() async {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        lock.unlock()
        try? await channel.close().get()
        try? await group.shutdownGracefully()
    }

    // MARK: Sessions

    /// Opens a session channel. `NIOSSHHandler.createChannel` is only safe from
    /// the parent channel's event loop, so that is where it is called.
    private func newChannel(
        _ initialise: @escaping (Channel) -> EventLoopFuture<Void>
    ) async throws -> Channel {
        let promise = channel.eventLoop.makePromise(of: Channel.self)
        channel.eventLoop.execute {
            self.handler.createChannel(promise, channelType: .session) { child, _ in
                initialise(child)
            }
        }
        do {
            return try await promise.futureResult.get()
        } catch {
            throw SSHError.channelFailed(String(describing: error))
        }
    }

    /// A PTY-backed shell, the same shape Android opens: request the terminal
    /// first, then the shell, then stream bytes in both directions.
    public func openShell(
        term: String = "xterm-256color",
        columns: Int,
        rows: Int,
        environment: [String: String] = [:]
    ) async throws -> ShellSession {
        let collector = ShellCollector()
        let channel = try await newChannel { child in
            child.pipeline.addHandler(collector)
        }
        let modes = SSHTerminalModes([
            SSHTerminalModes.Opcode.VINTR: 0x03,
            SSHTerminalModes.Opcode.VERASE: 0x7F,
            SSHTerminalModes.Opcode.VKILL: 0x15,
            SSHTerminalModes.Opcode.VEOF: 0x04,
            SSHTerminalModes.Opcode.VSTART: 0x11,
            SSHTerminalModes.Opcode.VSTOP: 0x13,
            SSHTerminalModes.Opcode.ISIG: 1,
            SSHTerminalModes.Opcode.ICANON: 1,
            SSHTerminalModes.Opcode.ECHO: 1,
            SSHTerminalModes.Opcode.ECHOE: 1,
            SSHTerminalModes.Opcode.OPOST: 1,
            SSHTerminalModes.Opcode.TTY_OP_ISPEED: 38400,
            SSHTerminalModes.Opcode.TTY_OP_OSPEED: 38400,
        ])
        do {
            for (name, value) in environment.sorted(by: { $0.key < $1.key }) {
                _ = try await channel.triggerUserOutboundEvent(
                    SSHChannelRequestEvent.EnvironmentRequest(wantReply: false, name: name, value: value)
                ).get()
            }
            _ = try await channel.triggerUserOutboundEvent(
                SSHChannelRequestEvent.PseudoTerminalRequest(
                    wantReply: true,
                    term: term,
                    terminalCharacterWidth: columns,
                    terminalRowHeight: rows,
                    terminalPixelWidth: 0,
                    terminalPixelHeight: 0,
                    terminalModes: modes
                )
            ).get()
            _ = try await channel.triggerUserOutboundEvent(
                SSHChannelRequestEvent.ShellRequest(wantReply: true)
            ).get()
        } catch {
            try? await channel.close().get()
            throw SSHError.channelFailed("the host refused the pty or the shell")
        }
        return ShellSession(channel: channel, collector: collector)
    }

    /// Runs a command and collects its output. Nothing is interpolated here:
    /// callers build the command with the validated helpers.
    public func exec(_ command: String, timeout: Duration = .seconds(60)) async throws -> CommandResult {
        try await run(command: command, stdin: nil, timeout: timeout)
    }

    /// Streams a reviewed host script to `sh -s`, so the script never touches
    /// disk and nothing is read from a path the app did not create.
    public func runStreamedScript(
        _ script: String,
        arguments: [String],
        timeout: Duration = .seconds(60)
    ) async throws -> CommandResult {
        guard !script.isEmpty, script.count <= 200_000 else {
            throw SSHError.channelFailed("the script is empty or too large")
        }
        var command = "sh -s"
        for argument in arguments {
            command += " " + ShellText.quote(argument)
        }
        return try await run(command: command, stdin: Data(script.utf8), timeout: timeout)
    }

    /// Runs a command whose standard input carries data, such as a commit
    /// message. The data is bounded and is never spliced into the command.
    public func exec(_ command: String, input: Data, timeout: Duration = .seconds(120)) async throws -> CommandResult {
        guard input.count <= 1 << 20 else {
            throw SSHError.channelFailed("the input is too large")
        }
        return try await run(command: command, stdin: input, timeout: timeout)
    }

    private func run(command: String, stdin: Data?, timeout: Duration) async throws -> CommandResult {
        guard !command.isEmpty, command.count <= 16_384 else {
            throw SSHError.channelFailed("the command is empty or too long")
        }
        let collector = ExecCollector()
        let channel = try await newChannel { child in
            child.pipeline.addHandler(collector)
        }
        do {
            _ = try await channel.triggerUserOutboundEvent(
                SSHChannelRequestEvent.ExecRequest(command: command, wantReply: true)
            ).get()
            if let stdin, !stdin.isEmpty {
                var buffer = channel.allocator.buffer(capacity: stdin.count)
                buffer.writeBytes(stdin)
                try await channel.writeAndFlush(
                    NIOAny(SSHChannelData(type: .channel, data: .byteBuffer(buffer)))
                ).get()
            }
            try await channel.close(mode: .output).get()
        } catch let error as SSHError {
            try? await channel.close().get()
            throw error
        } catch {
            try? await channel.close().get()
            throw SSHError.channelFailed("the host refused the command")
        }
        let timeoutTask = Task {
            try? await Task.sleep(for: timeout)
            collector.markTimedOut()
            try? await channel.close().get()
        }
        defer { timeoutTask.cancel() }
        let result = try await collector.result()
        try? await channel.close().get()
        if collector.didTimeOut { throw SSHError.timeout }
        return result
    }

    /// An SFTP subsystem channel. The client is entirely in `SFTPClient`.
    public func openSFTP() async throws -> SFTPClient {
        let collector = SFTPChannelCollector()
        let channel = try await newChannel { child in
            child.pipeline.addHandler(collector)
        }
        do {
            _ = try await channel.triggerUserOutboundEvent(
                SSHChannelRequestEvent.SubsystemRequest(subsystem: "sftp", wantReply: true)
            ).get()
        } catch {
            try? await channel.close().get()
            throw SSHError.channelFailed("the host has no sftp subsystem")
        }
        return SFTPClient(channel: channel, collector: collector)
    }
}

// MARK: - Channel plumbing

final class ShellCollector: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private let lock = NSLock()
    private var continuation: AsyncThrowingStream<Data, Error>.Continuation?
    private var exitCode: Int?
    private var stderrBuffer = Data()

    private(set) lazy var output: AsyncThrowingStream<Data, Error> = {
        AsyncThrowingStream { continuation in
            self.lock.lock()
            self.continuation = continuation
            self.lock.unlock()
        }
    }()

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let channelData = unwrapInboundIn(data)
        guard case .byteBuffer(var buffer) = channelData.data else { return }
        let bytes = buffer.readBytes(length: buffer.readableBytes) ?? []
        if channelData.type == .stdErr {
            lock.lock(); stderrBuffer.append(contentsOf: bytes); lock.unlock()
            return
        }
        lock.lock()
        let continuation = self.continuation
        lock.unlock()
        continuation?.yield(Data(bytes))
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let status = event as? SSHChannelRequestEvent.ExitStatus {
            lock.lock(); exitCode = status.exitStatus; lock.unlock()
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelInactive(context: ChannelHandlerContext) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.finish()
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.finish(throwing: SSHError.channelFailed(String(describing: error)))
        context.close(promise: nil)
    }
}

public final class ShellSession: @unchecked Sendable {
    private let channel: Channel
    private let collector: ShellCollector

    init(channel: Channel, collector: ShellCollector) {
        self.channel = channel
        self.collector = collector
    }

    public var output: AsyncThrowingStream<Data, Error> { collector.output }

    public func write(_ data: Data) async throws {
        guard !data.isEmpty, data.count <= 1 << 20 else { return }
        var buffer = channel.allocator.buffer(capacity: data.count)
        buffer.writeBytes(data)
        try await channel.writeAndFlush(
            NIOAny(SSHChannelData(type: .channel, data: .byteBuffer(buffer)))
        ).get()
    }

    public func resize(columns: Int, rows: Int) async throws {
        guard columns > 0, columns <= 1000, rows > 0, rows <= 400 else { return }
        _ = try await channel.triggerUserOutboundEvent(
            SSHChannelRequestEvent.WindowChangeRequest(
                terminalCharacterWidth: columns,
                terminalRowHeight: rows,
                terminalPixelWidth: 0,
                terminalPixelHeight: 0
            )
        ).get()
    }

    /// Closing the channel ends a plain shell; a tmux *attachment* only detaches,
    /// which is why the UI never presents this as stopping work.
    public func close() async {
        try? await channel.close().get()
    }

    public func detach() async {
        try? await write(Data([0x02, 0x64])) // C-b d
    }
}

final class ExecCollector: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private let lock = NSLock()
    private var stdoutData = Data()
    private var stderrData = Data()
    private var exitCode: Int?
    private var continuation: CheckedContinuation<CommandResult, Error>?
    private var finished = false
    private var timedOut = false
    private let stdoutLimit = 4 << 20

    var didTimeOut: Bool {
        lock.lock(); defer { lock.unlock() }
        return timedOut
    }

    func markTimedOut() {
        lock.lock(); timedOut = true; lock.unlock()
    }

    func result() async throws -> CommandResult {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if finished {
                let result = CommandResult(
                    stdout: String(decoding: stdoutData, as: UTF8.self),
                    stderr: String(decoding: stderrData, as: UTF8.self),
                    exitCode: exitCode ?? 255
                )
                lock.unlock()
                continuation.resume(returning: result)
                return
            }
            self.continuation = continuation
            lock.unlock()
        }
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let channelData = unwrapInboundIn(data)
        guard case .byteBuffer(var buffer) = channelData.data else { return }
        let bytes = buffer.readBytes(length: buffer.readableBytes) ?? []
        lock.lock()
        if channelData.type == .stdErr {
            if stderrData.count < 64 * 1024 { stderrData.append(contentsOf: bytes) }
        } else if stdoutData.count < stdoutLimit {
            stdoutData.append(contentsOf: bytes)
        }
        lock.unlock()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let status = event as? SSHChannelRequestEvent.ExitStatus {
            lock.lock(); exitCode = status.exitStatus; lock.unlock()
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelInactive(context: ChannelHandlerContext) {
        finish()
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        lock.lock()
        clearContinuation()
        stderrData.append(contentsOf: Data(String(describing: error).utf8))
        lock.unlock()
        context.close(promise: nil)
    }

    private func finish() {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return }
        finished = true
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(
            returning: CommandResult(
                stdout: String(decoding: stdoutData, as: UTF8.self),
                stderr: String(decoding: stderrData, as: UTF8.self),
                exitCode: exitCode ?? 255
            )
        )
    }

    private func clearContinuation() {
        finished = true
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(
            returning: CommandResult(stdout: "", stderr: "channel error", exitCode: 255)
        )
    }
}


/// Quoting for values that become part of a command string.
public enum ShellText {
    public static func quote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
