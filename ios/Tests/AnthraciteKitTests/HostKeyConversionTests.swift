import XCTest
import NIOSSH
@testable import AnthraciteKit

/// The scan must produce exactly the key the server offered, in the same
/// OpenSSH form and with the same fingerprint `ssh-keygen -lf` prints.
///
/// The wire protocol itself is now NIOSSH's: this suite pins the conversion that
/// sits between NIOSSH's parsed key and the pin the app stores, which is the only
/// part of the scan this project still owns.
final class HostKeyConversionTests: XCTestCase {
    private let algorithm = "ssh-ed25519"
    private let base64 = "AAAAC3NzaC1lZDI1NTE5AAAAICAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/"
    private let fingerprint = "SHA256:LVC7PY6ur9Dk+WVIYQsgEU2uvTXAor8XoYLl+ZmNyRo"

    func testTheFixtureIsWhatSSHKeygenReports() {
        let key = HostKey(algorithm: algorithm, base64: base64)
        XCTAssertEqual(key.fingerprint, fingerprint)
        XCTAssertEqual(key.openSSH, "\(algorithm) \(base64)")
    }

    /// A key parsed by NIOSSH and converted back must be identical, including the
    /// base64 the fingerprint is computed over.
    func testAnNIOSSHKeyConvertsBackToTheSameOpenSSHString() throws {
        let original = HostKey(algorithm: algorithm, base64: base64)
        let parsed = try NIOSSHPublicKey(openSSHPublicKey: original.openSSH)
        let text = String(openSSHPublicKey: parsed)
        let parts = text.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
        XCTAssertEqual(parts.count, 2)
        let converted = HostKey(algorithm: String(parts[0]), base64: String(parts[1]))
        XCTAssertEqual(converted, original)
        XCTAssertEqual(converted.fingerprint, fingerprint)
    }

    /// The pinned key and the presented key compare as equal only when they are
    /// the same key: this is the check the real connection relies on.
    func testPinnedAndPresentedKeysCompareOnlyWhenIdentical() throws {
        let pinned = try NIOSSHPublicKey(openSSHPublicKey: "\(algorithm) \(base64)")
        let same = try NIOSSHPublicKey(openSSHPublicKey: "\(algorithm) \(base64)")
        // One byte different in the key body.
        let other = HostKey(
            algorithm: algorithm,
            base64: "AAAAC3NzaC1lZDI1NTE5AAAAICAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4+"
        )
        let different = try NIOSSHPublicKey(openSSHPublicKey: other.openSSH)
        XCTAssertEqual(pinned, same)
        XCTAssertNotEqual(pinned, different)
        XCTAssertNotEqual(other.fingerprint, fingerprint)
    }
}
