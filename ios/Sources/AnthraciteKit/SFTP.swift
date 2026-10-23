import Foundation
import NIOCore
import NIOSSH

/// SFTP version 3 over the host's own `sftp-server`. Only what a file browser
/// needs, each operation bounded: an 8 MB transfer cap, a 5000-entry listing cap,
/// and a symlink is deleted as a link rather than followed.
public struct RemoteEntry: Equatable, Sendable {
    public let name: String
    public let path: String
    public let isDirectory: Bool
    public let isSymlink: Bool
    public let size: UInt64
    public let modified: UInt32
    public let permissions: UInt32
}

public enum SFTPError: Error, Equatable, Sendable {
    case notConnected
    case protocolViolation(String)
    case server(code: UInt32, message: String)
    case tooLarge
    case tooManyEntries
    case invalidPath
    case timedOut
}

// MARK: - Wire constants

enum SFTP {
    static let version = 3
    static let packetInit: UInt8 = 1
    static let packetVersion: UInt8 = 2
    static let packetOpen: UInt8 = 3
    static let packetClose: UInt8 = 4
    static let packetRead: UInt8 = 5
    static let packetWrite: UInt8 = 6
    static let packetLstat: UInt8 = 7
    static let packetOpendir: UInt8 = 11
    static let packetReaddir: UInt8 = 12
    static let packetRemove: UInt8 = 13
    static let packetMkdir: UInt8 = 14
    static let packetRmdir: UInt8 = 15
    static let packetStat: UInt8 = 17
    static let packetRename: UInt8 = 18
    static let replyStatus: UInt8 = 101
    static let replyHandle: UInt8 = 102
    static let replyData: UInt8 = 103
    static let replyName: UInt8 = 104
    static let replyAttrs: UInt8 = 105

    static let flagRead: UInt32 = 0x0000_0001
    static let flagWrite: UInt32 = 0x0000_0002
    static let flagCreat: UInt32 = 0x0000_0008
    static let flagTrunc: UInt32 = 0x0000_0010
    static let flagExcl: UInt32 = 0x0000_0020

    static let statusOK: UInt32 = 0
    static let statusEOF: UInt32 = 1

    static let attributeSize: UInt32 = 0x0000_0001
    static let attributePermissions: UInt32 = 0x0000_0004
    static let attributeTimes: UInt32 = 0x0000_0008
}

struct SFTPReader {
    private let data: Data
    private var offset = 0

    init(_ data: Data) { self.data = data }

    var isAtEnd: Bool { offset >= data.count }

    mutating func readByte() throws -> UInt8 {
        guard offset < data.count else { throw SFTPError.protocolViolation("truncated byte") }
        let value = data[data.startIndex + offset]
        offset += 1
        return value
    }

    mutating func readUInt32() throws -> UInt32 {
        guard offset + 4 <= data.count else { throw SFTPError.protocolViolation("truncated uint32") }
        var value: UInt32 = 0
        for index in 0..<4 {
            value = (value << 8) | UInt32(data[data.startIndex + offset + index])
        }
        offset += 4
        return value
    }

    mutating func readUInt64() throws -> UInt64 {
        let high = try readUInt32()
        let low = try readUInt32()
        return (UInt64(high) << 32) | UInt64(low)
    }

    mutating func readBytes() throws -> Data {
        let length = try readUInt32()
        guard length < 1 << 22, offset + Int(length) <= data.count else {
            throw SFTPError.protocolViolation("bad string length")
        }
        let start = data.startIndex + offset
        let slice = data.subdata(in: start..<(start + Int(length)))
        offset += Int(length)
        return slice
    }

    mutating func readString() throws -> String {
        String(decoding: try readBytes(), as: UTF8.self)
    }
}

struct SFTPWriter {
    private(set) var data = Data()

    mutating func write(byte value: UInt8) { data.append(value) }

    mutating func write(uint32 value: UInt32) {
        data.append(UInt8((value >> 24) & 0xff))
        data.append(UInt8((value >> 16) & 0xff))
        data.append(UInt8((value >> 8) & 0xff))
        data.append(UInt8(value & 0xff))
    }

    mutating func write(uint64 value: UInt64) {
        write(uint32: UInt32((value >> 32) & 0xffff_ffff))
        write(uint32: UInt32(value & 0xffff_ffff))
    }

    mutating func write(string value: String) {
        let bytes = Data(value.utf8)
        write(bytes: bytes)
    }

    mutating func write(bytes value: Data) {
        write(uint32: UInt32(value.count))
        data.append(value)
    }

    /// uint32 length, then the body.
    func framed() -> Data {
        var out = Data()
        let length = UInt32(data.count)
        out.append(UInt8((length >> 24) & 0xff))
        out.append(UInt8((length >> 16) & 0xff))
        out.append(UInt8((length >> 8) & 0xff))
        out.append(UInt8(length & 0xff))
        out.append(data)
        return out
    }
}

/// Splits the channel's byte stream into complete SFTP packets.
///
/// A frame whose length is impossible is a **fatal protocol error**, not a pause:
/// silently dropping the buffer left every pending request waiting for a reply
/// that could never arrive.
struct SFTPPacketDecoder {
    private var buffer = Data()

    mutating func append(_ data: Data) {
        buffer.append(data)
    }

    /// The next complete packet, or nil if more bytes are needed.
    /// Throws when the stream cannot be a well-formed SFTP packet stream.
    mutating func next() throws -> Data? {
        guard buffer.count >= 4 else { return nil }
        let length = Int(buffer[buffer.startIndex]) << 24 | Int(buffer[buffer.startIndex + 1]) << 16
            | Int(buffer[buffer.startIndex + 2]) << 8 | Int(buffer[buffer.startIndex + 3])
        guard length > 0, length < 1 << 22 else {
            buffer.removeAll()
            throw SFTPError.protocolViolation("invalid SFTP packet length")
        }
        guard buffer.count >= length + 4 else { return nil }
        let body = buffer.subdata(in: 4..<(4 + length))
        buffer.removeSubrange(0..<(4 + length))
        return body
    }
}

final class SFTPChannelCollector: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private let lock = NSLock()
    private var decoder = SFTPPacketDecoder()
    private var continuation: AsyncThrowingStream<Data, Error>.Continuation?

    private(set) lazy var packets: AsyncThrowingStream<Data, Error> = {
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
        lock.lock()
        decoder.append(Data(bytes))
        var ready: [Data] = []
        var failure: Error?
        do {
            while let packet = try decoder.next() { ready.append(packet) }
        } catch {
            failure = error
        }
        let continuation = self.continuation
        if failure != nil { self.continuation = nil }
        lock.unlock()
        for packet in ready { continuation?.yield(packet) }
        if let failure {
            // Fatal: every pending request must learn that this connection can
            // no longer answer, instead of waiting forever.
            continuation?.finish(throwing: failure)
            context.close(promise: nil)
        }
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
        continuation?.finish(throwing: SFTPError.protocolViolation(String(describing: error)))
        context.close(promise: nil)
    }
}

/// The byte transport the client speaks over: an SSH channel in the app, an
/// in-memory peer in the tests.
///
/// This is the client's only seam, and it earns its place: the states that decide
/// whether this client hangs cannot be produced on demand by a real `sftp-server`.
/// A server that never greets, one that greets late, one that announces a version
/// this client does not speak, and one that hangs up mid-handshake are all
/// reachable through this protocol, and none of them is reachable reliably over a
/// socket.
protocol SFTPTransport: Sendable {
    /// The framed packets the peer sent, in order.
    var packets: AsyncThrowingStream<Data, Error> { get }
    func write(_ data: Data) async throws
    func close() async
}

final class ChannelSFTPTransport: SFTPTransport, @unchecked Sendable {
    private let channel: Channel
    private let collector: SFTPChannelCollector

    init(channel: Channel, collector: SFTPChannelCollector) {
        self.channel = channel
        self.collector = collector
    }

    var packets: AsyncThrowingStream<Data, Error> { collector.packets }

    func write(_ data: Data) async throws {
        var buffer = channel.allocator.buffer(capacity: data.count)
        buffer.writeBytes(data)
        try await channel.writeAndFlush(
            NIOAny(SSHChannelData(type: .channel, data: .byteBuffer(buffer)))
        ).get()
    }

    func close() async {
        try? await channel.close().get()
    }
}

public actor SFTPClient {
    public static let maxTransferBytes = 8 * 1024 * 1024
    public static let maxEntries = 5000
    private static let chunkSize = 32768

    /// Request id reserved for the version exchange. Real requests start at 1.
    private static let handshakeId: UInt32 = 0

    /// A request that never gets a reply must fail, not hang the UI forever.
    static let requestTimeout: Duration = .seconds(30)

    /// The version exchange is bounded for the same reason. A server that never
    /// greets is indistinguishable from one that is merely slow, and waiting
    /// forever hides both from the user.
    static let handshakeTimeout: Duration = .seconds(20)

    private let transport: any SFTPTransport
    private let requestTimeout: Duration
    private let handshakeTimeout: Duration
    private var nextRequestId: UInt32 = 1
    private var pending: [UInt32: CheckedContinuation<Data, Error>] = [:]
    private var timers: [UInt32: Task<Void, Never>] = [:]
    private var readTask: Task<Void, Never>?
    private var state = State.idle

    private enum State {
        case idle
        /// One handshake in progress, shared by every caller that arrives while it
        /// runs. Without this, a second caller sent a second INIT and overwrote the
        /// first caller's continuation, which then never resumed: the first caller
        /// waited forever on a reply that had already been delivered to somebody
        /// else.
        case starting(Task<Void, Error>)
        case open
        /// The connection can no longer be trusted: either the handshake failed or
        /// the stream ended. Every later call fails with this error instead of
        /// retrying on a byte stream that is out of step with the peer.
        case failed(SFTPError)
    }

    init(
        transport: any SFTPTransport,
        requestTimeout: Duration = SFTPClient.requestTimeout,
        handshakeTimeout: Duration = SFTPClient.handshakeTimeout
    ) {
        self.transport = transport
        self.requestTimeout = requestTimeout
        self.handshakeTimeout = handshakeTimeout
    }

    deinit {
        readTask?.cancel()
        for timer in timers.values { timer.cancel() }
        // A caller still waiting on this client must learn that it is gone rather
        // than wait for a timeout that no longer has an owner.
        for continuation in pending.values {
            continuation.resume(throwing: SFTPError.notConnected)
        }
    }

    // MARK: Transport

    /// The version exchange, run once no matter how many callers arrive at the
    /// same moment.
    private func start() async throws {
        switch state {
        case .open:
            return
        case .failed(let error):
            throw error
        case .starting(let task):
            // The same task every caller waits on, so only one INIT is ever sent.
            return try await task.value
        case .idle:
            let task = Task { try await self.performHandshake() }
            state = .starting(task)
            do {
                try await task.value
                // Only now, and only if the connection did not die while the
                // handshake ran: resurrecting a dead channel here would make every
                // later request wait out its full timeout.
                if case .starting = state { state = .open }
            } catch {
                let failure = error as? SFTPError ?? .notConnected
                state = .failed(failure)
                failAll(failure)
                await transport.close()
                throw error
            }
        }
    }

    private func performHandshake() async throws {
        startPump()
        var writer = SFTPWriter()
        writer.write(byte: SFTP.packetInit)
        writer.write(uint32: UInt32(SFTP.version))
        let reply = try await roundTrip(
            id: Self.handshakeId,
            framed: writer.framed(),
            timeout: handshakeTimeout
        )
        var reader = SFTPReader(reply)
        guard (try reader.readUInt32()) & 0xff == UInt32(SFTP.packetVersion) else {
            throw SFTPError.protocolViolation("expected VERSION")
        }
        let version = try reader.readUInt32()
        guard version == UInt32(SFTP.version) else {
            throw SFTPError.protocolViolation("the host speaks SFTP version \(version); this client needs 3")
        }
    }

    private func startPump() {
        guard readTask == nil else { return }
        // Read before the first write, so a reply that arrives immediately is
        // already being collected.
        let stream = transport.packets
        readTask = Task { [weak self] in
            do {
                for try await packet in stream { await self?.dispatch(packet) }
            } catch {
                await self?.connectionLost(error)
                return
            }
            await self?.connectionLost(SFTPError.notConnected)
        }
    }

    /// The packet stream ended or failed: nothing can answer any more, so every
    /// waiting caller is released now rather than at its timeout.
    private func connectionLost(_ error: Error) {
        let failure = error as? SFTPError ?? .notConnected
        if case .failed = state {
            // Already terminal: keep the first cause.
        } else {
            state = .failed(failure)
        }
        failAll(failure)
    }

    /// A packet body is `byte type` followed by `uint32 id` — not a 32-bit type.
    /// Reading four bytes here consumed three bytes of the id, so the version reply
    /// never matched and no request could ever be answered.
    private func dispatch(_ packet: Data) {
        var reader = SFTPReader(packet)
        guard let type = try? reader.readByte() else { return }
        if type == SFTP.packetVersion {
            take(Self.handshakeId)?.resume(returning: packet)
            return
        }
        guard let id = try? reader.readUInt32() else { return }
        // A reply for a request that already timed out or was cancelled finds no
        // entry and is dropped. The stream stays framed, so the reply after it
        // still matches its own request.
        take(id)?.resume(returning: packet)
    }

    /// Removes a request's bookkeeping and hands back its continuation, so exactly
    /// one of "reply arrived", "timed out", "cancelled" and "connection lost" can
    /// complete it. Every path goes through here; a continuation is never resumed
    /// twice and never left behind.
    private func take(_ id: UInt32) -> CheckedContinuation<Data, Error>? {
        timers.removeValue(forKey: id)?.cancel()
        return pending.removeValue(forKey: id)
    }

    private func expire(_ id: UInt32) {
        take(id)?.resume(throwing: SFTPError.timedOut)
    }

    private func fail(_ id: UInt32, _ error: Error) {
        take(id)?.resume(throwing: error)
    }

    private func abandon(_ id: UInt32) {
        take(id)?.resume(throwing: CancellationError())
    }

    private func failAll(_ error: Error) {
        let waiting = pending
        for id in waiting.keys { _ = take(id) }
        for continuation in waiting.values { continuation.resume(throwing: error) }
    }

    /// One request/response round trip: registered, sent, and bounded.
    ///
    /// The entry is registered *before* the frame is written, so a reply that
    /// arrives on the next tick cannot find its id missing. A write that fails
    /// resumes the same entry. Cancellation resumes it too, which is what lets the
    /// caller return instead of staying suspended until the timeout.
    private func roundTrip(id: UInt32, framed: Data, timeout: Duration) async throws -> Data {
        let transport = self.transport
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                pending[id] = continuation
                timers[id] = Task { [weak self] in
                    // A cancelled timer has no work left: the waiter was resolved
                    // by the cancellation handler or by a reply.
                    do { try await Task.sleep(for: timeout) } catch { return }
                    await self?.expire(id)
                }
                Task { [weak self] in
                    do { try await transport.write(framed) } catch { await self?.fail(id, error) }
                }
            }
        } onCancel: {
            Task { [weak self] in await self?.abandon(id) }
        }
    }

    /// One request/response round trip. Every operation below is built from this,
    /// so request ids can never collide. The reply type is returned unexamined:
    /// EOF is a normal reply for reads, and only the caller knows that.
    private func call(_ type: UInt8, _ build: (inout SFTPWriter) -> Void) async throws -> (UInt8, SFTPReader) {
        try await start()
        let id = nextRequestId
        nextRequestId = nextRequestId >= UInt32.max - 8 ? 1 : nextRequestId + 1
        var writer = SFTPWriter()
        writer.write(byte: type)
        writer.write(uint32: id)
        build(&writer)
        let packet = try await roundTrip(id: id, framed: writer.framed(), timeout: requestTimeout)
        var reader = SFTPReader(packet)
        let replyType = try reader.readByte()
        _ = try reader.readUInt32()
        return (replyType, reader)
    }

    /// Reads a STATUS reply. Throws unless it is `SSH_FX_OK`.
    private func expectOK(_ type: UInt8, _ reader: SFTPReader) throws {
        guard type == SFTP.replyStatus else {
            throw SFTPError.protocolViolation("expected STATUS")
        }
        var status = reader
        let code = try status.readUInt32()
        if code == SFTP.statusOK { return }
        let message = (try? status.readString()) ?? ""
        throw SFTPError.server(code: code, message: message)
    }

    private func readHandle(_ type: UInt8, _ reader: SFTPReader) throws -> String {
        guard type == SFTP.replyHandle else { throw SFTPError.protocolViolation("expected HANDLE") }
        var reader = reader
        return try reader.readString()
    }

    // MARK: Attributes

    struct Attributes {
        var size: UInt64 = 0
        var permissions: UInt32 = 0
        var modified: UInt32 = 0
        var isDirectory: Bool { (permissions & 0o170000) == 0o040000 }
        var isSymlink: Bool { (permissions & 0o170000) == 0o120000 }
    }

    private func readAttributes(_ reader: inout SFTPReader) throws -> Attributes {
        var attributes = Attributes()
        let flags = try reader.readUInt32()
        if flags & SFTP.attributeSize != 0 { attributes.size = try reader.readUInt64() }
        if flags & 0x0000_0002 != 0 {
            _ = try reader.readUInt32()
            _ = try reader.readUInt32()
        }
        if flags & SFTP.attributePermissions != 0 { attributes.permissions = try reader.readUInt32() }
        if flags & SFTP.attributeTimes != 0 {
            _ = try reader.readUInt32()
            attributes.modified = try reader.readUInt32()
        }
        if flags & 0x8000_0000 != 0 {
            let count = try reader.readUInt32()
            for _ in 0..<min(count, 32) {
                _ = try reader.readString()
                _ = try reader.readString()
            }
        }
        return attributes
    }

    // MARK: Operations

    public func listDirectory(_ path: String) async throws -> [RemoteEntry] {
        guard RemotePath.isValidAbsolute(path) else { throw SFTPError.invalidPath }
        let opened = try await call(SFTP.packetOpendir) { writer in writer.write(string: path) }
        let handle = try readHandle(opened.0, opened.1)
        var entries: [RemoteEntry] = []
        defer {
            Task { [weak self] in
                _ = try? await self?.call(SFTP.packetClose) { writer in writer.write(string: handle) }
            }
        }
        while true {
            var (type, reader) = try await call(SFTP.packetReaddir) { writer in writer.write(string: handle) }
            if type == SFTP.replyStatus {
                let code = try reader.readUInt32()
                if code == SFTP.statusEOF { break }
                let message = (try? reader.readString()) ?? ""
                throw SFTPError.server(code: code, message: message)
            }
            guard type == SFTP.replyName else { throw SFTPError.protocolViolation("expected NAME") }
            let count = try reader.readUInt32()
            for _ in 0..<min(count, UInt32(Self.maxEntries)) {
                let name = try reader.readString()
                _ = try reader.readString() // long name
                let attributes = try readAttributes(&reader)
                if name == "." || name == ".." { continue }
                guard let full = RemotePath.join(path, name) else { continue }
                entries.append(
                    RemoteEntry(
                        name: name,
                        path: full,
                        isDirectory: attributes.isDirectory,
                        isSymlink: attributes.isSymlink,
                        size: attributes.size,
                        modified: attributes.modified,
                        permissions: attributes.permissions
                    )
                )
                if entries.count >= Self.maxEntries { throw SFTPError.tooManyEntries }
            }
        }
        return entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    public func stat(_ path: String, follow: Bool = true) async throws -> RemoteEntry {
        guard RemotePath.isValidAbsolute(path) else { throw SFTPError.invalidPath }
        let (type, reader) = try await call(follow ? SFTP.packetStat : SFTP.packetLstat) { writer in
            writer.write(string: path)
        }
        guard type == SFTP.replyAttrs else {
            var status = reader
            let code = try status.readUInt32()
            throw SFTPError.server(code: code, message: (try? status.readString()) ?? "")
        }
        var attributesReader = reader
        let attributes = try readAttributes(&attributesReader)
        return RemoteEntry(
            name: (path as NSString).lastPathComponent,
            path: path,
            isDirectory: attributes.isDirectory,
            isSymlink: attributes.isSymlink,
            size: attributes.size,
            modified: attributes.modified,
            permissions: attributes.permissions
        )
    }

    public func readFile(_ path: String, maximumBytes: Int = SFTPClient.maxTransferBytes) async throws -> Data {
        guard RemotePath.isValidAbsolute(path) else { throw SFTPError.invalidPath }
        let cap = min(max(1, maximumBytes), Self.maxTransferBytes)
        let opened = try await call(SFTP.packetOpen) { writer in
            writer.write(string: path)
            writer.write(uint32: SFTP.flagRead)
            writer.write(uint32: 0)
        }
        let handle = try readHandle(opened.0, opened.1)
        var data = Data()
        defer {
            Task { [weak self] in
                _ = try? await self?.call(SFTP.packetClose) { writer in writer.write(string: handle) }
            }
        }
        // Read until the server says EOF, or until the answer is provably bigger
        // than the cap. Stopping at "cap bytes arrived" is not the same thing: a
        // file of exactly cap bytes and a truncated prefix of a larger file look
        // identical at that point, and returning the prefix as a whole file is
        // silent data loss.
        var reachedEOF = false
        while true {
            var (type, reader) = try await call(SFTP.packetRead) { writer in
                writer.write(string: handle)
                writer.write(uint64: UInt64(data.count))
                writer.write(uint32: UInt32(Self.chunkSize))
            }
            if type == SFTP.replyStatus {
                let code = try reader.readUInt32()
                if code == SFTP.statusEOF {
                    reachedEOF = true
                    break
                }
                let message = (try? reader.readString()) ?? ""
                throw SFTPError.server(code: code, message: message)
            }
            guard type == SFTP.replyData else { throw SFTPError.protocolViolation("expected DATA") }
            let payload = try reader.readBytes()
            if payload.isEmpty {
                // A zero-length DATA reply is not an EOF marker: only STATUS(EOF)
                // proves the file ended.
                throw SFTPError.protocolViolation("empty DATA reply without EOF")
            }
            data.append(payload)
            if data.count > cap { throw SFTPError.tooLarge }
        }
        guard reachedEOF, data.count <= cap else { throw SFTPError.protocolViolation("read ended without EOF") }
        return data
    }

    /// Writes to a temporary name and renames it. A final name never points at a
    /// half-written file.
    ///
    /// The temporary name is random per attempt. A fixed suffix meant one
    /// interrupted upload left `<file>.anthracite-part` behind, and every later
    /// attempt then failed on the exclusive create until someone removed it by
    /// hand. Every failure path removes the temporary name before rethrowing.
    public func writeFile(_ path: String, data: Data, mode: UInt32 = 0o600) async throws {
        guard RemotePath.isValidAbsolute(path) else { throw SFTPError.invalidPath }
        guard data.count <= Self.maxTransferBytes else { throw SFTPError.tooLarge }
        let temporary = path + ".anthracite-part-" + SFTPClient.newPartToken()
        let opened = try await call(SFTP.packetOpen) { writer in
            writer.write(string: temporary)
            writer.write(uint32: SFTP.flagWrite | SFTP.flagCreat | SFTP.flagTrunc | SFTP.flagExcl)
            writer.write(uint32: SFTP.attributePermissions)
            writer.write(uint32: mode)
        }
        let handle = try readHandle(opened.0, opened.1)
        var offset = 0
        var failure: Error?
        while offset < data.count {
            let end = min(offset + Self.chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            do {
                let (type, reader) = try await call(SFTP.packetWrite) { writer in
                    writer.write(string: handle)
                    writer.write(uint64: UInt64(offset))
                    writer.write(bytes: chunk)
                }
                try expectOK(type, reader)
            } catch {
                failure = error
                break
            }
            offset = end
        }
        let closed: (UInt8, SFTPReader)?
        do {
            closed = try await call(SFTP.packetClose) { writer in writer.write(string: handle) }
        } catch {
            await removeQuietly(temporary)
            throw error
        }
        if let failure {
            await removeQuietly(temporary)
            throw failure
        }
        if let closed, (try? expectOK(closed.0, closed.1)) == nil {
            // The server did not confirm the close, so the bytes on disk are not
            // proven complete: do not rename them into place.
            await removeQuietly(temporary)
            throw SFTPError.protocolViolation("the server did not confirm the uploaded file")
        }
        do {
            let renamed = try await call(SFTP.packetRename) { writer in
                writer.write(string: temporary)
                writer.write(string: path)
            }
            try expectOK(renamed.0, renamed.1)
        } catch {
            await removeQuietly(temporary)
            throw error
        }
    }

    /// Best-effort removal of a temporary name this call created.
    private func removeQuietly(_ path: String) async {
        _ = try? await call(SFTP.packetRemove) { writer in writer.write(string: path) }
    }

    /// A random token for the temporary upload name.
    static func newPartToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 8)
        for index in bytes.indices { bytes[index] = UInt8.random(in: 0...255) }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    public func mkdir(_ path: String, mode: UInt32 = 0o755) async throws {
        guard RemotePath.isValidAbsolute(path) else { throw SFTPError.invalidPath }
        let (type, reader) = try await call(SFTP.packetMkdir) { writer in
            writer.write(string: path)
            writer.write(uint32: SFTP.attributePermissions)
            writer.write(uint32: mode)
        }
        try expectOK(type, reader)
    }

    /// A symlink is removed as a link: `lstat` decides, so the target is safe.
    public func remove(_ path: String) async throws {
        guard RemotePath.isValidAbsolute(path), path != "/" else { throw SFTPError.invalidPath }
        let entry = try await stat(path, follow: false)
        let (type, reader) = try await call(entry.isDirectory ? SFTP.packetRmdir : SFTP.packetRemove) { writer in
            writer.write(string: path)
        }
        try expectOK(type, reader)
    }

    public func rename(_ from: String, to: String) async throws {
        guard RemotePath.isValidAbsolute(from), RemotePath.isValidAbsolute(to) else {
            throw SFTPError.invalidPath
        }
        let (type, reader) = try await call(SFTP.packetRename) { writer in
            writer.write(string: from)
            writer.write(string: to)
        }
        try expectOK(type, reader)
    }

    public func close() async {
        // Release every caller first: a request in flight would otherwise wait out
        // its timeout on a connection that is being torn down.
        failAll(SFTPError.notConnected)
        state = .failed(.notConnected)
        readTask?.cancel()
        await transport.close()
    }
}
