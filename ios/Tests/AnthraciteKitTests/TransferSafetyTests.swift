import XCTest
@testable import AnthraciteKit

/// Upload and download have to be honest about size and about debris.
///
/// The decoder and the temporary-name rules are pure and pinned here; the
/// read-until-EOF loop itself needs a server, which this suite does not have.
final class TransferSafetyTests: XCTestCase {
    // MARK: - Framing

    func testAnImpossibleFrameLengthIsFatalNotSilent() {
        var decoder = SFTPPacketDecoder()
        decoder.append(Data([0x00, 0x00, 0x00, 0x00]))
        XCTAssertThrowsError(try decoder.next()) { error in
            guard case SFTPError.protocolViolation = error as? SFTPError else {
                return XCTFail("expected a protocol violation, got \(error)")
            }
        }
    }

    func testAFrameLargerThanTheCeilingIsFatal() {
        var decoder = SFTPPacketDecoder()
        decoder.append(Data([0x7F, 0xFF, 0xFF, 0xFF]))
        XCTAssertThrowsError(try decoder.next())
    }

    func testAnIncompleteFrameWaitsInsteadOfFailing() throws {
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyData)
        writer.write(uint32: 9)
        writer.write(bytes: Data(repeating: 0x01, count: 4))
        let framed = writer.framed()
        var decoder = SFTPPacketDecoder()
        decoder.append(framed.dropLast(1))
        XCTAssertNil(try decoder.next())
        decoder.append(framed.suffix(1))
        XCTAssertEqual(try decoder.next()?.count, framed.count - 4)
    }

    // MARK: - Temporary upload names

    func testTemporaryNamesAreRandomPerAttempt() {
        let tokens = (0..<32).map { _ in SFTPClient.newPartToken() }
        XCTAssertEqual(Set(tokens).count, tokens.count, "tokens must not repeat")
        for token in tokens {
            XCTAssertEqual(token.count, 16)
            XCTAssertTrue(token.allSatisfy { "0123456789abcdef".contains($0) })
        }
    }

    // MARK: - Timeouts

    func testARequestHasABoundedWait() {
        // The deadline is part of the client's contract, not an implementation
        // detail: an unbounded wait is what let a stalled server hang the UI.
        XCTAssertLessThanOrEqual(SFTPClient.requestTimeout, .seconds(120))
        XCTAssertGreaterThan(SFTPClient.requestTimeout, .zero)
    }

    func testTimeoutIsAFirstClassError() {
        let error: SFTPError = .timedOut
        XCTAssertNotEqual(error, .notConnected)
    }
}
