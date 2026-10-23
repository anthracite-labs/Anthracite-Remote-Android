@testable import AnthraciteKit
import XCTest

/// Flow-control signals must converge on the latest state when producers and
/// consumers race. A stale pause must never overwrite a fresh resume.
final class TerminalOutputConcurrencyTests: XCTestCase {
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

    private func chunk(_ number: Int, size: Int = 8 * 1024) -> Data {
        Data(repeating: UInt8(number % 251), count: size)
    }

    /// One producer and one consumer racing still delivers every byte in order
    /// and leaves the reader running once drained.
    func testConcurrentProducerAndConsumerStayLosslessAndUnpaused() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }
        let count = 200
        let parts = (0..<count).map { chunk($0) }

        let producer = Task {
            for part in parts { stream.append(part) }
            stream.finish()
        }
        var received = Data()
        for try await part in stream { received.append(part) }
        await producer.value

        XCTAssertEqual(received, parts.reduce(Data()) { $0 + $1 })
        XCTAssertFalse(stream.isPaused)
        XCTAssertFalse(reader.paused, "stale pause left the reader stopped: \(reader.states.suffix(4))")
    }

    /// Rapid pause/resume overlap converges: the last signal always matches the
    /// stream state, even when many tasks toggle at once.
    func testOverlappingPauseAndResumeConvergeOnTheFinalState() async throws {
        for _ in 0..<30 {
            let reader = Reader()
            let stream = TerminalOutput()
            stream.onFlowControl { reader.record($0) }

            // Fill past the high-water mark so the stream pauses.
            for number in 0..<80 { stream.append(chunk(number, size: 16 * 1024)) }
            XCTAssertTrue(stream.isPaused)

            // Drain and refill concurrently: resume and pause overlap. The chunk
            // builder is a free function so no test-case state is captured.
            func makeChunk(_ number: Int) -> Data {
                Data(repeating: UInt8(number % 251), count: 16 * 1024)
            }
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    var iterator = stream.makeAsyncIterator()
                    for _ in 0..<40 { _ = try? await iterator.next() }
                }
                group.addTask {
                    for number in 80..<120 { stream.append(makeChunk(number)) }
                }
            }
            // Drain fully: the reader must end running with no stale pause.
            var iterator = stream.makeAsyncIterator()
            while stream.bufferedByteCount > 0 { _ = try await iterator.next() }
            stream.finish()
            for try await _ in stream {}

            XCTAssertFalse(stream.isPaused, "reader left stopped after concurrent pause/resume")
            XCTAssertFalse(reader.paused, "stale signal left the reader stopped: \(reader.states.suffix(6))")
        }
    }

    /// Cancelling a parked consumer while a producer pauses still converges.
    func testCancellationDuringAPauseStillResumesTheReader() async throws {
        let reader = Reader()
        let stream = TerminalOutput()
        stream.onFlowControl { reader.record($0) }
        for number in 0..<80 { stream.append(chunk(number, size: 16 * 1024)) }
        XCTAssertTrue(stream.isPaused)

        var iterator = stream.makeAsyncIterator()
        while stream.bufferedByteCount > 0 { _ = try await iterator.next() }
        XCTAssertFalse(stream.isPaused)

        let consumer = Task { () -> Data? in
            var parked = stream.makeAsyncIterator()
            return try await parked.next()
        }
        for _ in 0..<10_000 where !stream.hasParkedConsumer { await Task.yield() }
        XCTAssertTrue(stream.hasParkedConsumer)
        consumer.cancel()
        _ = await consumer.result
        XCTAssertFalse(reader.paused)
    }
}
