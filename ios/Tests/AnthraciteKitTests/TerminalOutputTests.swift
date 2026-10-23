@testable import AnthraciteKit
import NIOCore
import NIOEmbedded
import NIOSSH
import XCTest

/// Terminal output is bounded, ordered and lossless.
///
/// A slow consumer used to mean an unbounded buffer, because the stream NIO fed
/// buffered without limit. These tests measure the bound itself (not just that a
/// callback exists), prove that no byte is dropped or reordered while the reader is
/// stopped and started, and drive the channel handler to show it really asks NIO to
/// stop reading.
final class TerminalOutputTests: XCTestCase {
    /// Records the reader's state as the stream reports it.
    private final class Reader: @unchecked Sendable {
        private let lock = NSLock()
        private var events: [Bool] = []

        var states: [Bool] {
            lock.lock()
            defer { lock.unlock() }
            return events
        }

        var paused: Bool { states.last ?? false }

        func record(_ paused: Bool) {
            lock.lock()
            events.append(paused)
            lock.unlock()
        }
    }

    private func chunk(_ number: Int, size: Int = 32 * 1024) -> Data {
        Data(repeating: UInt8(number % 251), count: size)
    }

    // MARK: The bound

    func testTheQueueStopsGrowingAndAsksTheReaderToStop() {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }

        // 1 MiB, far past the 512 KiB high-water mark, with nobody consuming.
        var sent = 0
        for number in 0..<32 {
            let part = chunk(number)
            stream.append(part)
            sent += part.count
        }

        XCTAssertGreaterThan(sent, TerminalOutput.highWaterMark)
        XCTAssertTrue(reader.paused, "the reader was never asked to stop")
        // The bound holds: the queue never grows past the mark plus one chunk.
        XCTAssertLessThanOrEqual(
            stream.bufferedByteCount,
            TerminalOutput.highWaterMark + 32 * 1024,
            "the queue outgrew its bound"
        )
        // Asked once, not once per chunk.
        XCTAssertEqual(reader.states, [true], "the reader was told the same thing repeatedly")
    }

    func testConsumingBelowTheLowWaterMarkStartsTheReaderAgain() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }
        for number in 0..<32 { stream.append(chunk(number)) }
        XCTAssertTrue(reader.paused)

        var iterator = stream.makeAsyncIterator()
        // Drain until the queue is under the mark, then the reader must restart.
        while stream.bufferedByteCount > TerminalOutput.lowWaterMark {
            _ = try await iterator.next()
        }
        XCTAssertFalse(reader.paused, "the reader was never restarted")
        XCTAssertEqual(reader.states, [true, false])
    }

    // MARK: Losslessness

    func testEveryByteArrivesInOrderWhileTheReaderStopsAndStarts() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }

        let parts = (0..<64).map { chunk($0, size: 20 * 1024) }
        let producer = Task {
            for part in parts { stream.append(part) }
            stream.finish()
        }

        var received = Data()
        for try await part in stream { received.append(part) }
        await producer.value

        XCTAssertEqual(received.count, parts.reduce(0) { $0 + $1.count })
        XCTAssertEqual(received, parts.reduce(Data()) { $0 + $1 })
        // The reader was stopped at least once and restarted: the path was used.
        XCTAssertTrue(reader.states.contains(true))
        XCTAssertEqual(reader.paused, false, "the reader was left stopped")
    }

    func testASlowConsumerStillSeesEveryByte() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }
        let parts = (0..<40).map { chunk($0, size: 16 * 1024) }

        let producer = Task {
            for part in parts { stream.append(part) }
            stream.finish()
        }
        var received = Data()
        for try await part in stream {
            received.append(part)
            // A deliberately slow consumer: the producer must wait on the bound.
            try await Task.sleep(for: .milliseconds(2))
        }
        await producer.value

        XCTAssertEqual(received, parts.reduce(Data()) { $0 + $1 })
        XCTAssertLessThanOrEqual(stream.bufferedByteCount, TerminalOutput.highWaterMark + 32 * 1024)
    }

    func testUTF8AndANSISequencesAreNotSplitOrAltered() async throws {
        // A multi-byte character and an escape sequence spanning chunk boundaries
        // must survive byte for byte: the stream carries bytes, and it must never
        // try to be clever about them.
        let text = "héllo \u{1B}[38;5;208mworld\u{1B}[0m 日本語 🚀\n"
        let bytes = Data(text.utf8)
        let stream = TerminalOutput()
        for index in stride(from: 0, to: bytes.count, by: 3) {
            let end = min(index + 3, bytes.count)
            stream.append(bytes.subdata(in: index..<end))
        }
        stream.finish()

        var received = Data()
        for try await part in stream { received.append(part) }
        XCTAssertEqual(String(decoding: received, as: UTF8.self), text)
    }

    // MARK: Ending

    func testEndingTheStreamDeliversQueuedBytesThenStops() async throws {
        let stream = TerminalOutput()
        stream.append(Data("first".utf8))
        stream.finish()
        stream.append(Data("after the end".utf8))

        var received = Data()
        for try await part in stream { received.append(part) }
        XCTAssertEqual(String(decoding: received, as: UTF8.self), "first")
    }

    func testAFailureReachesTheConsumer() async throws {
        let stream = TerminalOutput()
        stream.append(Data("buffered".utf8))
        stream.fail(SSHError.channelFailed("the channel ended"))

        var received = Data()
        do {
            for try await part in stream { received.append(part) }
            XCTFail("a failed stream must throw")
        } catch let error as SSHError {
            XCTAssertEqual(error, .channelFailed("the channel ended"))
        }
        XCTAssertEqual(String(decoding: received, as: UTF8.self), "buffered")
    }

    // MARK: Cancellation

    func testACancelledConsumerDoesNotLeaveTheReaderStopped() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }
        for number in 0..<32 { stream.append(chunk(number)) }
        XCTAssertTrue(stream.isPaused)

        let consumer = Task { () -> Data? in
            var iterator = stream.makeAsyncIterator()
            return try await iterator.next()
        }
        consumer.cancel()
        _ = try? await consumer.value

        // The reader is not left stopped because one consumer walked away: the next
        // consumer must be able to drain, and the queue must still be bounded.
        XCTAssertFalse(stream.isPaused, "the reader was left stopped after cancellation")
        XCTAssertEqual(reader.states.last, false)
        var iterator = stream.makeAsyncIterator()
        let next = try await iterator.next()
        XCTAssertNotNil(next)
    }

    // MARK: The channel handler actually stops reading

    func testTheCollectorStopsAndRestartsReadingWhenTheQueueFills() async throws {
        let collector = ShellCollector()
        let reader = Reader()
        collector.output.onFlowControl { reader.record($0) }
        let channel = EmbeddedChannel(handler: collector)
        defer { _ = try? channel.finish() }

        // Feed far more than the bound through the real channel handler.
        for number in 0..<40 {
            var buffer = channel.allocator.buffer(capacity: 32 * 1024)
            buffer.writeBytes(chunk(number))
            _ = try channel.writeInbound(NIOAny(SSHChannelData(type: .channel, data: .byteBuffer(buffer))))
        }

        XCTAssertTrue(reader.paused, "the handler kept reading past the bound")
        XCTAssertLessThanOrEqual(
            collector.output.bufferedByteCount,
            TerminalOutput.highWaterMark + 32 * 1024,
            "the queue outgrew its bound inside the handler"
        )

        // Draining below the low-water mark restarts the reader through the same
        // hook the channel uses.
        var iterator = collector.output.makeAsyncIterator()
        while collector.output.bufferedByteCount > TerminalOutput.lowWaterMark {
            _ = try await iterator.next()
        }
        XCTAssertFalse(reader.paused, "the handler never restarted reading")
    }
}
