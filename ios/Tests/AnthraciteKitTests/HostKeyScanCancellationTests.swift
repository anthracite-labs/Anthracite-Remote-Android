@testable import AnthraciteKit
import NIOCore
import XCTest

/// The scanner must never strand a waiter: cancellation, timeout, disconnect
/// and key delivery all resolve the same continuation exactly once.
final class HostKeyScanCancellationTests: XCTestCase {
    private func sampleKey() -> HostKey {
        HostKey(
            algorithm: "ssh-ed25519",
            base64: "AAAAC3NzaC1lZDI1NTE5AAAAICAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/"
        )
    }

    private func waitForParked(_ collector: KeyCaptureDelegate) async {
        for _ in 0..<10_000 {
            if collector.hasWaiter { return }
            await Task.yield()
        }
        XCTFail("the waiter never parked")
    }

    /// A silent server (no key, no close) plus task cancellation must return
    /// promptly instead of hanging on the parked continuation.
    func testCancellationReleasesASilentServerWaiter() async throws {
        let collector = KeyCaptureDelegate()
        let waiting = Task { try await collector.wait(timeout: .seconds(30)) }
        await waitForParked(collector)

        waiting.cancel()
        let outcome = await withTaskGroup(of: String.self) { group -> String in
            group.addTask {
                do {
                    _ = try await waiting.value
                    return "returned"
                } catch is CancellationError {
                    return "cancelled"
                } catch {
                    return "other:\(error)"
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                return "hung"
            }
            let first = await group.next()!
            group.cancelAll()
            return first
        }
        XCTAssertEqual(outcome, "cancelled", "cancelling a silent scan must throw CancellationError, got \(outcome)")
    }

    /// A timeout resolves the waiter so a later waiter sees the stored result.
    func testTimeoutResolvesTheWaiterExactlyOnce() async throws {
        let collector = KeyCaptureDelegate()
        do {
            _ = try await collector.wait(timeout: .nanoseconds(10_000_000))
            XCTFail("a silent server must time out")
        } catch let error as SSHError {
            XCTAssertEqual(error, .timeout)
        }
        do {
            _ = try await collector.wait(timeout: .seconds(5))
            XCTFail("the stored timeout must persist")
        } catch let error as SSHError {
            XCTAssertEqual(error, .timeout)
        }
    }

    /// A disconnect (channel close) resolves the waiter.
    func testDisconnectResolvesTheWaiter() async throws {
        let collector = KeyCaptureDelegate()
        let waiting = Task { try await collector.wait(timeout: .seconds(30)) }
        await waitForParked(collector)
        collector.fail(SSHError.connectionFailed("the connection closed before the key exchange"))
        do {
            _ = try await waiting.value
            XCTFail("a closed connection must fail")
        } catch let error as SSHError {
            XCTAssertEqual(error, .connectionFailed("the connection closed before the key exchange"))
        }
    }

    /// Key delivery resolves the waiter; a late waiter gets the stored key.
    func testKeyDeliveryResolvesTheWaiter() async throws {
        let collector = KeyCaptureDelegate()
        let waiting = Task { try await collector.wait(timeout: .seconds(30)) }
        await waitForParked(collector)
        collector.deliver(sampleKey())
        let first = try await waiting.value
        XCTAssertEqual(first, sampleKey())
        let late = try await collector.wait(timeout: .seconds(5))
        XCTAssertEqual(late, sampleKey())
    }

    /// Key delivery, timeout, disconnect and cancellation race: exactly one wins
    /// and the continuation is never resumed twice (which would trap).
    func testKeyTimeoutDisconnectAndCancellationRaceSafely() async throws {
        for _ in 0..<50 {
            let collector = KeyCaptureDelegate()
            let key = sampleKey()
            await withTaskGroup(of: Void.self) { group in
                group.addTask { collector.deliver(key) }
                group.addTask { collector.fail(SSHError.timeout) }
                group.addTask { collector.fail(CancellationError()) }
                group.addTask {
                    collector.fail(SSHError.connectionFailed("the connection closed before the key exchange"))
                }
            }
            // Whichever won is stored; both waiters see the same stored result —
            // never hang, never double-resume.
            let first: Result<HostKey, Error> = await {
                do {
                    return .success(try await collector.wait(timeout: .seconds(5)))
                } catch {
                    return .failure(error)
                }
            }()
            let second: Result<HostKey, Error> = await {
                do {
                    return .success(try await collector.wait(timeout: .seconds(5)))
                } catch {
                    return .failure(error)
                }
            }()
            switch (first, second) {
            case (.success(let a), .success(let b)):
                XCTAssertEqual(a, b)
            case (.failure, .failure):
                break
            default:
                XCTFail("concurrent waiters must agree, got \(first) vs \(second)")
            }
        }
    }
}
