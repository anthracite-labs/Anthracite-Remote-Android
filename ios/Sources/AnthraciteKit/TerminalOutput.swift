import Foundation
import NIOCore

/// The byte stream of a terminal session: ordered, bounded and lossless.
///
/// A remote program can write far faster than a phone renders. NIO reads as fast as
/// the peer sends unless it is told to stop, and a stream with the default policy
/// buffers without limit, so a slow consumer meant unbounded growth — and, under
/// sustained output, the app being killed.
///
/// This stream carries a **bounded** queue and never drops or reorders a byte. When
/// the queue passes the high-water mark it asks the reader to stop reading; when the
/// consumer has drained it below the low-water mark it asks the reader to start
/// again. On an SSH channel that is real flow control: the window closes and the
/// remote program blocks in `write`, exactly as it does when a real terminal stops
/// reading.
///
/// One consumer at a time, matching the single terminal reader.
public final class TerminalOutput: AsyncSequence, @unchecked Sendable {
    public typealias Element = Data

    /// The queue may not grow past this, beyond a single inbound chunk.
    public static let highWaterMark = 512 * 1024
    /// Reading resumes once the queue is back below this.
    public static let lowWaterMark = 128 * 1024

    public struct AsyncIterator: AsyncIteratorProtocol {
        let stream: TerminalOutput

        public mutating func next() async throws -> Data? {
            try await stream.nextChunk()
        }
    }

    private let lock = NSLock()
    private var queue: [Data] = []
    private var buffered = 0
    private var parked: CheckedContinuation<Data?, Error>?
    private var terminal: Result<Void, Error>?
    private var paused = false
    /// Called with `true` to stop reading, `false` to start again.
    private var flowControl: (@Sendable (Bool) -> Void)?

    public init(flowControl: (@Sendable (Bool) -> Void)? = nil) {
        self.flowControl = flowControl
    }

    /// Connects the reader. The current state is delivered at once, so a pause that
    /// happened before the channel existed is not lost.
    func onFlowControl(_ closure: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        flowControl = closure
        let pausedNow = paused
        lock.unlock()
        closure(pausedNow)
    }

    public func makeAsyncIterator() -> AsyncIterator { AsyncIterator(stream: self) }

    /// Bytes waiting to be consumed: the quantity the bound is about.
    public var bufferedByteCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return buffered
    }

    /// True while the reader has been asked to stop.
    public var isPaused: Bool {
        lock.lock()
        defer { lock.unlock() }
        return paused
    }

    /// Hands over one chunk without ever blocking the channel's thread.
    ///
    /// Order is preserved: a chunk goes behind everything already waiting, unless a
    /// consumer is parked on `next()`, which takes it directly.
    func append(_ chunk: Data) {
        guard !chunk.isEmpty else { return }
        var parked: CheckedContinuation<Data?, Error>?
        var pause = false
        lock.lock()
        if terminal != nil {
            lock.unlock()
            return
        }
        if let waiting = self.parked {
            self.parked = nil
            parked = waiting
        } else {
            queue.append(chunk)
            buffered += chunk.count
            if !paused, buffered > Self.highWaterMark {
                paused = true
                pause = true
            }
        }
        lock.unlock()
        parked?.resume(returning: chunk)
        if pause { flowControl?(true) }
    }

    /// Ends the stream normally; bytes already queued are still delivered.
    func finish() {
        var parked: CheckedContinuation<Data?, Error>?
        lock.lock()
        if terminal == nil { terminal = .success(()) }
        parked = self.parked
        self.parked = nil
        lock.unlock()
        parked?.resume(returning: nil)
    }

    /// Ends the stream with an error; bytes already queued are still delivered.
    func fail(_ error: Error) {
        var parked: CheckedContinuation<Data?, Error>?
        lock.lock()
        if terminal == nil { terminal = .failure(error) }
        parked = self.parked
        self.parked = nil
        lock.unlock()
        parked?.resume(throwing: error)
    }

    private func nextChunk() async throws -> Data? {
        // Fast path, and the one that keeps the bound honest: taking a chunk may
        // drop the queue below the low-water mark, which reopens the reader.
        var resume = false
        lock.lock()
        if !queue.isEmpty {
            let chunk = queue.removeFirst()
            buffered -= chunk.count
            if paused, buffered < Self.lowWaterMark {
                paused = false
                resume = true
            }
            lock.unlock()
            if resume { flowControl?(false) }
            return chunk
        }
        if let terminal {
            lock.unlock()
            if case .failure(let error) = terminal { throw error }
            return nil
        }
        if Task.isCancelled {
            if paused { paused = false; resume = true }
            lock.unlock()
            if resume { flowControl?(false) }
            throw CancellationError()
        }
        lock.unlock()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data?, Error>) in
                // Registration and the checks share one lock, so a chunk that
                // arrives now either resolves this waiter or is queued behind it —
                // it can never be left unread with a consumer parked forever.
                lock.lock()
                if !queue.isEmpty {
                    let chunk = queue.removeFirst()
                    buffered -= chunk.count
                    if paused, buffered < Self.lowWaterMark {
                        paused = false
                        resume = true
                    }
                    lock.unlock()
                    if resume { flowControl?(false) }
                    continuation.resume(returning: chunk)
                } else if let terminal {
                    lock.unlock()
                    if case .failure(let error) = terminal {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: nil)
                    }
                } else {
                    parked = continuation
                    lock.unlock()
                }
            }
        } onCancel: {
            self.abandon()
        }
    }

    /// A consumer that went away must not leave the reader paused for ever.
    private func abandon() {
        var parked: CheckedContinuation<Data?, Error>?
        var resume = false
        lock.lock()
        parked = self.parked
        self.parked = nil
        if paused { paused = false; resume = true }
        lock.unlock()
        parked?.resume(throwing: CancellationError())
        if resume { flowControl?(false) }
    }
}
