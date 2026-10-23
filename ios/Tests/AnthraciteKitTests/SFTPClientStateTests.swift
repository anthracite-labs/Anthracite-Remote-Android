@testable import AnthraciteKit
import XCTest

/// The client side of a scripted SFTP conversation.
///
/// Every byte the client writes is recorded here, and every byte it receives is
/// chosen by the test: silence, delay, duplicates, a wrong version and a hang-up
/// are all reachable, which they are not over a real socket. Nothing in this file
/// touches the network.
final class TestSFTPPeer: SFTPTransport, @unchecked Sendable {
    let packets: AsyncThrowingStream<Data, Error>

    private let lock = NSLock()
    private var stream: AsyncThrowingStream<Data, Error>.Continuation?
    private var frames: [Data] = []
    private var closes = 0

    /// Called with each framed packet the client writes. Set once, before use.
    var handler: (@Sendable (TestSFTPPeer, Data) -> Void)?

    init() {
        var captured: AsyncThrowingStream<Data, Error>.Continuation?
        packets = AsyncThrowingStream { captured = $0 }
        stream = captured
    }

    /// The framed packets the client sent, in order.
    var received: [Data] {
        lock.lock()
        defer { lock.unlock() }
        return frames
    }

    /// How many version exchanges the client started.
    var handshakeCount: Int {
        received.filter { $0.count > 4 && $0[$0.startIndex + 4] == SFTP.packetInit }.count
    }

    /// Every request id the client used, in order, excluding the handshake.
    var requestIds: [UInt32] {
        received.compactMap { frame in
            guard frame.count >= 9, frame[frame.startIndex + 4] != SFTP.packetInit else { return nil }
            var reader = SFTPReader(frame.subdata(in: 5..<9))
            return try? reader.readUInt32()
        }
    }

    var closeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return closes
    }

    func write(_ data: Data) async throws {
        lock.lock()
        frames.append(data)
        lock.unlock()
        handler?(self, data)
    }

    func close() async {
        lock.lock()
        closes += 1
        let stream = self.stream
        self.stream = nil
        lock.unlock()
        // The channel is gone: the client's packet stream ends, exactly as it does
        // when `channelInactive` fires.
        stream?.finish()
    }

    /// Delivers a packet body to the client, correctly framed.
    func send(_ body: Data) {
        var framed = Data()
        let length = UInt32(body.count)
        framed.append(contentsOf: [
            UInt8((length >> 24) & 0xff),
            UInt8((length >> 16) & 0xff),
            UInt8((length >> 8) & 0xff),
            UInt8(length & 0xff),
        ])
        framed.append(body)
        deliver(framed)
    }

    /// Delivers bytes without framing, for a peer that cannot speak SFTP.
    func sendRaw(_ data: Data) {
        deliver(data)
    }

    func disconnect() {
        lock.lock()
        let stream = self.stream
        self.stream = nil
        lock.unlock()
        stream?.finish()
    }

    func failConnection(_ error: Error) {
        lock.lock()
        let stream = self.stream
        self.stream = nil
        lock.unlock()
        stream?.finish(throwing: error)
    }

    private func deliver(_ data: Data) {
        lock.lock()
        let stream = self.stream
        lock.unlock()
        stream?.yield(data)
    }
}

/// A minimal SFTP version 3 server with fault injection.
///
/// It serves what the client's transfers need — open, read, write, close, rename,
/// remove, stat — and it can be told to stay silent, answer late, announce the
/// wrong version or hang up.
actor FakeSFTPServer {
    let peer: TestSFTPPeer

    // Fault injection. Set before the operation under test; read on the actor.
    var ignoreHandshake = false
    var ignoreRequests = false
    var versionReply: UInt32 = 3
    var delayMilliseconds: UInt64 = 0
    var failWrites = false

    private var files: [String: Data] = [:]
    private var handles: [String: String] = [:]
    private var nextHandle = 1

    init(peer: TestSFTPPeer) {
        self.peer = peer
    }

    // MARK: Test surface

    func seed(_ path: String, _ text: String) {
        files[path] = Data(text.utf8)
    }

    func contents(_ path: String) -> String? {
        files[path].map { String(decoding: $0, as: UTF8.self) }
    }

    var paths: [String] {
        files.keys.sorted()
    }

    func forget(_ path: String) {
        files[path] = nil
    }

    // MARK: Server

    func handle(_ frame: Data) async {
        var reader = SFTPReader(frame)
        guard let type = try? reader.readByte() else { return }
        if type == SFTP.packetInit {
            guard !ignoreHandshake else { return }
            var writer = SFTPWriter()
            writer.write(byte: SFTP.packetVersion)
            writer.write(uint32: versionReply)
            await deliver(writer.data)
            return
        }
        guard let id = try? reader.readUInt32() else { return }
        guard !ignoreRequests else { return }
        switch type {
        case SFTP.packetOpen: await open(id: id, reader: reader)
        case SFTP.packetRead: await read(id: id, reader: reader)
        case SFTP.packetWrite: await write(id: id, reader: reader)
        case SFTP.packetClose: await respond(status(id: id))
        case SFTP.packetRename: await rename(id: id, reader: reader)
        case SFTP.packetRemove: await remove(id: id, reader: reader)
        case SFTP.packetStat, SFTP.packetLstat: await stat(id: id, reader: reader)
        default: await respond(status(id: id, code: 8, message: "unsupported"))
        }
    }

    private func open(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let path = try? reader.readString(), let flags = try? reader.readUInt32() else {
            await respond(status(id: id, code: 8, message: "bad OPEN"))
            return
        }
        // Exclusive create is the point of the upload path: a second OPEN of the
        // same temporary name must fail rather than truncate it.
        let exclusive = flags & SFTP.flagExcl != 0
        if exclusive, files[path] != nil {
            await respond(status(id: id, code: 4, message: "already exists"))
            return
        }
        if flags & (SFTP.flagCreat | SFTP.flagTrunc) != 0 || files[path] == nil {
            files[path] = Data()
        }
        let handle = "h\(nextHandle)"
        nextHandle += 1
        handles[handle] = path
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyHandle)
        writer.write(uint32: id)
        writer.write(string: handle)
        await respond(writer.data)
    }

    private func read(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let handle = try? reader.readString(),
              let offset = try? reader.readUInt64(),
              let length = try? reader.readUInt32(),
              let path = handles[handle], let data = files[path]
        else {
            await respond(status(id: id, code: 4, message: "no such handle"))
            return
        }
        if Int(offset) >= data.count {
            await respond(status(id: id, code: SFTP.statusEOF))
            return
        }
        let start = Int(offset)
        let end = min(start + Int(length), data.count)
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyData)
        writer.write(uint32: id)
        writer.write(bytes: data.subdata(in: start..<end))
        await respond(writer.data)
    }

    private func write(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let handle = try? reader.readString(),
              let offset = try? reader.readUInt64(),
              let payload = try? reader.readBytes(),
              let path = handles[handle]
        else {
            await respond(status(id: id, code: 8, message: "bad WRITE"))
            return
        }
        if failWrites {
            await respond(status(id: id, code: 4, message: "no space left"))
            return
        }
        var data = files[path] ?? Data()
        let start = Int(offset)
        if start > data.count { data.append(Data(count: start - data.count)) }
        if start >= data.count {
            data.append(payload)
        } else {
            data.replaceSubrange(start..<min(start + payload.count, data.count), with: payload)
        }
        files[path] = data
        await respond(status(id: id))
    }

    private func rename(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let from = try? reader.readString(), let to = try? reader.readString() else {
            await respond(status(id: id, code: 8, message: "bad RENAME"))
            return
        }
        if let data = files[from] {
            files[to] = data
            files[from] = nil
            await respond(status(id: id))
        } else {
            await respond(status(id: id, code: 2, message: "no such file"))
        }
    }

    private func remove(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let path = try? reader.readString() else {
            await respond(status(id: id, code: 8, message: "bad REMOVE"))
            return
        }
        files[path] = nil
        await respond(status(id: id))
    }

    private func stat(id: UInt32, reader: SFTPReader) async {
        var reader = reader
        guard let path = try? reader.readString(), let data = files[path] else {
            await respond(status(id: id, code: 2, message: "no such file"))
            return
        }
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyAttrs)
        writer.write(uint32: id)
        writer.write(uint32: SFTP.attributeSize | SFTP.attributePermissions | SFTP.attributeTimes)
        writer.write(uint64: UInt64(data.count))
        writer.write(uint32: 1000)
        writer.write(uint32: 1000)
        writer.write(uint32: 1_700_000_000)
        await respond(writer.data)
    }

    private func status(id: UInt32, code: UInt32 = SFTP.statusOK, message: String = "") -> Data {
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyStatus)
        writer.write(uint32: id)
        writer.write(uint32: code)
        writer.write(string: message)
        return writer.data
    }

    private func respond(_ body: Data) async {
        if delayMilliseconds > 0 {
            try? await Task.sleep(for: .milliseconds(Int64(delayMilliseconds)))
        }
        peer.send(body)
    }

    private func deliver(_ body: Data) async {
        if delayMilliseconds > 0 {
            try? await Task.sleep(for: .milliseconds(Int64(delayMilliseconds)))
        }
        peer.send(body)
    }
}

/// One client wired to one scripted server.
final class SFTPFixture {
    let peer: TestSFTPPeer
    let server: FakeSFTPServer
    let client: SFTPClient

    init(requestTimeout: Duration = .milliseconds(300), handshakeTimeout: Duration = .milliseconds(300)) {
        let peer = TestSFTPPeer()
        let server = FakeSFTPServer(peer: peer)
        peer.handler = { [weak server] peer, frame in
            Task { await server?.handle(frame) }
        }
        self.peer = peer
        self.server = server
        self.client = SFTPClient(
            transport: peer,
            requestTimeout: requestTimeout,
            handshakeTimeout: handshakeTimeout
        )
    }
}

/// The states that decide whether this client hangs.
///
/// `SFTPClient` was written around a read loop plus a dictionary of continuations,
/// and none of its interesting behaviour was reachable in a test: a real
/// `sftp-server` will not stay silent, answer late, greet with the wrong version
/// or hang up mid-handshake on request. Each test below drives the real client
/// through a scripted peer and asserts that it **finishes** — every wait the client
/// makes is bounded, so a test that hangs is a defect, not a slow machine.
final class SFTPClientStateTests: XCTestCase {
    // MARK: Handshake

    func testConcurrentFirstCallersShareOneHandshake() async throws {
        let fixture = SFTPFixture()
        await fixture.server.seed("/a.txt", "alpha")
        await fixture.server.seed("/b.txt", "beta")

        async let first = fixture.client.readFile("/a.txt")
        async let second = fixture.client.readFile("/b.txt")
        let (a, b) = try await (first, second)

        XCTAssertEqual(String(decoding: a, as: UTF8.self), "alpha")
        XCTAssertEqual(String(decoding: b, as: UTF8.self), "beta")
        XCTAssertEqual(fixture.peer.handshakeCount, 1, "a second caller sent a second INIT")
    }

    func testASilentServerTimesOutTheHandshakeInsteadOfHanging() async throws {
        let fixture = SFTPFixture(requestTimeout: .milliseconds(200), handshakeTimeout: .milliseconds(200))
        await fixture.server.ignoreHandshake = true
        let started = Date()

        do {
            _ = try await fixture.client.readFile("/a.txt")
            XCTFail("a silent server must not succeed")
        } catch {
            XCTAssertEqual(error as? SFTPError, .timedOut)
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)

        // Fail-closed: the handshake is not retried on a stream that is out of
        // step with the peer.
        do {
            _ = try await fixture.client.readFile("/b.txt")
            XCTFail("a failed handshake must not be retried")
        } catch {
            XCTAssertEqual(error as? SFTPError, .timedOut)
        }
        XCTAssertEqual(fixture.peer.handshakeCount, 1, "the client retried the handshake")
    }

    func testAWrongVersionFailsClosed() async throws {
        let fixture = SFTPFixture()
        await fixture.server.versionReply = 4

        do {
            _ = try await fixture.client.readFile("/a.txt")
            XCTFail("version 4 is not this client's version")
        } catch let error as SFTPError {
            guard case .protocolViolation = error else {
                return XCTFail("unexpected error \(error)")
            }
        }

        // The channel is closed and every later call fails the same way, without
        // writing another INIT onto it.
        do {
            _ = try await fixture.client.readFile("/b.txt")
            XCTFail("a corrupt handshake must fail closed")
        } catch let error as SFTPError {
            guard case .protocolViolation = error else {
                return XCTFail("unexpected error \(error)")
            }
        }
        XCTAssertEqual(fixture.peer.handshakeCount, 1)
        XCTAssertEqual(fixture.peer.closeCount, 1)
    }

    func testDisconnectDuringTheHandshakeFailsTheCaller() async throws {
        let fixture = SFTPFixture()
        fixture.peer.handler = { peer, _ in peer.disconnect() }

        do {
            _ = try await fixture.client.readFile("/a.txt")
            XCTFail("a closed channel must not succeed")
        } catch {
            XCTAssertEqual(error as? SFTPError, .notConnected)
        }
    }

    func testMalformedFrameFailsTheHandshakeAndEveryWaiter() async throws {
        let fixture = SFTPFixture()
        fixture.peer.handler = { peer, _ in
            // A length that cannot be an SFTP packet: the decoder must treat it as
            // fatal, not as "wait for more bytes".
            peer.sendRaw(Data([0xff, 0xff, 0xff, 0xff, 0x02]))
        }

        do {
            _ = try await fixture.client.readFile("/a.txt")
            XCTFail("an impossible frame must fail")
        } catch let error as SFTPError {
            guard case .protocolViolation = error else {
                return XCTFail("unexpected error \(error)")
            }
        }
    }

    // MARK: Replies

    func testADelayedReplyStillMatchesItsRequest() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(3), handshakeTimeout: .seconds(3))
        await fixture.server.seed("/slow.txt", "slow but correct")
        await fixture.server.delayMilliseconds = 150

        let data = try await fixture.client.readFile("/slow.txt")
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "slow but correct")
    }

    func testOutOfOrderRepliesAreMatchedById() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(3))
        await fixture.server.seed("/one.txt", "first")
        await fixture.server.seed("/two.txt", "second")
        await fixture.server.delayMilliseconds = 40

        async let one = fixture.client.readFile("/one.txt")
        async let two = fixture.client.readFile("/two.txt")
        let (first, second) = try await (one, two)

        XCTAssertEqual(String(decoding: first, as: UTF8.self), "first")
        XCTAssertEqual(String(decoding: second, as: UTF8.self), "second")
        XCTAssertEqual(Set(fixture.peer.requestIds).count, fixture.peer.requestIds.count)
    }

    func testALateReplyAfterATimeoutDoesNotCorruptTheNextRequest() async throws {
        let fixture = SFTPFixture(requestTimeout: .milliseconds(150), handshakeTimeout: .seconds(2))
        await fixture.server.seed("/a.txt", "alpha")
        await fixture.server.ignoreRequests = true

        do {
            _ = try await fixture.client.readFile("/a.txt")
            XCTFail("a silent server must time out")
        } catch {
            XCTAssertEqual(error as? SFTPError, .timedOut)
        }
        let stale = fixture.peer.requestIds.last
        await fixture.server.ignoreRequests = false

        // The reply for the abandoned request arrives now, with its old id.
        if let stale {
            var writer = SFTPWriter()
            writer.write(byte: SFTP.replyStatus)
            writer.write(uint32: stale)
            writer.write(uint32: SFTP.statusOK)
            fixture.peer.send(writer.data)
        }

        let data = try await fixture.client.readFile("/a.txt")
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "alpha")
    }

    func testCancellingARequestReturnsAtOnceAndTheClientStaysUsable() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(30), handshakeTimeout: .seconds(2))
        await fixture.server.seed("/a.txt", "alpha")
        await fixture.server.ignoreRequests = true

        let task = Task { try await fixture.client.readFile("/a.txt") }
        try await Task.sleep(for: .milliseconds(100))
        let started = Date()
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("a cancelled request must not succeed")
        } catch {
            XCTAssertTrue(error is CancellationError, "unexpected error \(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 5, "cancellation waited for the timeout")

        await fixture.server.ignoreRequests = false
        let data = try await fixture.client.readFile("/a.txt")
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "alpha")
    }

    func testCloseReleasesEveryWaitingCaller() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(30), handshakeTimeout: .seconds(2))
        await fixture.server.seed("/a.txt", "alpha")
        await fixture.server.ignoreRequests = true

        let task = Task { try await fixture.client.readFile("/a.txt") }
        try await Task.sleep(for: .milliseconds(100))
        let started = Date()
        await fixture.client.close()

        do {
            _ = try await task.value
            XCTFail("a closed client must not answer")
        } catch {
            XCTAssertEqual(error as? SFTPError, .notConnected)
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
    }

    // MARK: Transfers

    func testUploadThenDownloadRoundTrips() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(2))
        let payload = Data("a whole file\n".utf8)

        try await fixture.client.writeFile("/upload.txt", data: payload)
        let readBack = try await fixture.client.readFile("/upload.txt")

        XCTAssertEqual(readBack, payload)
        // The temporary name was renamed into place, not left behind.
        let paths = await fixture.server.paths
        XCTAssertEqual(paths, ["/upload.txt"])
    }

    func testAFailedUploadRemovesItsTemporaryFile() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(2))
        await fixture.server.failWrites = true

        do {
            try await fixture.client.writeFile("/broken.txt", data: Data("payload".utf8))
            XCTFail("a refused write must not report success")
        } catch {
            XCTAssertEqual(error as? SFTPError, .server(code: 4, message: "no space left"))
        }
        let paths = await fixture.server.paths
        XCTAssertEqual(paths, [], "the temporary file was left behind")
    }

    func testAnUploadOverAnExistingFileReplacesItOnlyAfterSuccess() async throws {
        let fixture = SFTPFixture(requestTimeout: .seconds(2))
        await fixture.server.seed("/existing.txt", "old contents")

        try await fixture.client.writeFile("/existing.txt", data: Data("new".utf8))

        let contents = await fixture.server.contents("/existing.txt")
        let paths = await fixture.server.paths
        XCTAssertEqual(contents, "new")
        XCTAssertEqual(paths, ["/existing.txt"])
    }
}
