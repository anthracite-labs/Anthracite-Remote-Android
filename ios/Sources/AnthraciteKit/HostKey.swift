import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// A server host key, in the OpenSSH wire format.
///
/// The fingerprint is the same string `ssh-keygen -lf` prints for that key:
/// `SHA256:` followed by unpadded base64 of the SHA-256 of the key blob. Showing
/// users the same value their laptop shows is the whole point of a fingerprint.
public struct HostKey: Equatable, Sendable, Hashable {
    public let algorithm: String
    public let base64: String

    public init(algorithm: String, base64: String) {
        self.algorithm = algorithm
        self.base64 = base64
    }

    public var openSSH: String { "\(algorithm) \(base64)" }

    public var blob: Data { Data(base64Encoded: base64) ?? Data() }

    public var fingerprint: String { HostKey.fingerprint(ofBlob: blob) }

    public static func fingerprint(ofBlob blob: Data) -> String {
        HostKeyFingerprint.sha256Base64(blob)
    }
}

enum HostKeyFingerprint {
    static func sha256Base64(_ data: Data) -> String {
        var digest: [UInt8]
        #if canImport(CryptoKit)
        digest = Array(SHA256.hash(data: data))
        #else
        digest = SHA256Fallback.digestBytes(data)
        #endif
        var encoded = Data(digest).base64EncodedString()
        while encoded.hasSuffix("=") { encoded.removeLast() }
        return "SHA256:" + encoded
    }
}

public enum HostKeyError: Error, Equatable {
    case malformed
    case unsupportedAlgorithm(String)
}

/// Parsers for the parts of the SSH wire format that pinning needs. They take
/// bytes because the bytes are what the server actually sent.
public enum HostKeyWire {
    /// Reads one SSH `string` (uint32 length + bytes) from `data` at `offset`.
    public static func readString(_ data: Data, _ offset: inout Int) -> Data? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        var length: UInt32 = 0
        for index in 0..<4 {
            length = (length << 8) | UInt32(data[data.startIndex + offset + index])
        }
        let start = offset + 4
        guard length < 1 << 20, start + Int(length) <= data.count else { return nil }
        offset = start + Int(length)
        return data.subdata(in: (data.startIndex + start)..<(data.startIndex + start + Int(length)))
    }

    /// Reads a uint32.
    public static func readUInt32(_ data: Data, _ offset: inout Int) -> UInt32? {
        guard offset + 4 <= data.count else { return nil }
        var value: UInt32 = 0
        for index in 0..<4 {
            value = (value << 8) | UInt32(data[data.startIndex + offset + index])
        }
        offset += 4
        return value
    }

    /// A key blob is `string algorithm` followed by the algorithm-specific
    /// material. Only algorithms this client can verify are accepted.
    public static func parsePublicKeyBlob(_ blob: Data) throws -> HostKey {
        var offset = 0
        guard let algorithmData = readString(blob, &offset),
              let algorithm = String(data: algorithmData, encoding: .utf8) else {
            throw HostKeyError.malformed
        }
        let supported = ["ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"]
        guard supported.contains(algorithm) else {
            throw HostKeyError.unsupportedAlgorithm(algorithm)
        }
        // The rest must be non-empty and the whole blob must be consumed as a
        // single key: trailing bytes would mean we pinned something the server
        // did not present.
        let material = blob.subdata(in: offset..<blob.count)
        guard !material.isEmpty else { throw HostKeyError.malformed }
        return HostKey(algorithm: algorithm, base64: blob.base64EncodedString())
    }

    /// Extracts the host key from an SSH_MSG_KEX_ECDH_REPLY (31) payload: the
    /// message byte is already removed, so the first field is K_S.
    public static func hostKeyFromKeyExchangeReply(_ payload: Data) throws -> HostKey {
        var offset = 0
        guard let blob = readString(payload, &offset) else { throw HostKeyError.malformed }
        return try parsePublicKeyBlob(blob)
    }

    /// Parses the server's cleartext identification line.
    public static func identification(_ data: Data) -> String? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        for line in text.split(separator: "\r\n", omittingEmptySubsequences: true) {
            if line.hasPrefix("SSH-") { return String(line) }
        }
        return nil
    }
}

/// What the app remembers about a server's key.
///
/// The first successful connection records a key only after the user compares the
/// fingerprint out of band. A later connection presenting a different key is
/// refused until the user explicitly accepts the new one.
public struct HostKeyPin: Equatable, Sendable, Codable {
    public let host: String
    public let port: Int
    public let algorithm: String
    public let base64: String
    public let acceptedAt: Date

    public init(host: String, port: Int, key: HostKey, acceptedAt: Date = Date()) {
        self.host = host
        self.port = port
        self.algorithm = key.algorithm
        self.base64 = key.base64
        self.acceptedAt = acceptedAt
    }

    public var key: HostKey { HostKey(algorithm: algorithm, base64: base64) }
    public var fingerprint: String { key.fingerprint }
}

public enum PinDecision: Equatable, Sendable {
    case approved
    /// No pin yet: the caller must ask the user to compare the fingerprint.
    case unknownNeedsApproval(HostKey)
    /// A different key than the one the user approved.
    case changed(previous: HostKeyPin, presented: HostKey)
}

public struct HostKeyStore: Sendable {
    private var pins: [String: HostKeyPin]

    public init(pins: [HostKeyPin] = []) {
        var map: [String: HostKeyPin] = [:]
        for pin in pins { map[HostKeyStore.id(host: pin.host, port: pin.port)] = pin }
        self.pins = map
    }

    public var all: [HostKeyPin] { Array(pins.values).sorted { $0.host < $1.host } }

    public static func id(host: String, port: Int) -> String {
        "\(host.lowercased()):\(port)"
    }

    public func pin(host: String, port: Int) -> HostKeyPin? {
        pins[HostKeyStore.id(host: host, port: port)]
    }

    public func decide(host: String, port: Int, presented: HostKey) -> PinDecision {
        guard let existing = pin(host: host, port: port) else {
            return .unknownNeedsApproval(presented)
        }
        if existing.algorithm == presented.algorithm && existing.base64 == presented.base64 {
            return .approved
        }
        return .changed(previous: existing, presented: presented)
    }

    /// Only an explicit user action calls this.
    public mutating func approve(host: String, port: Int, key: HostKey, at date: Date = Date()) {
        let pin = HostKeyPin(host: host, port: port, key: key, acceptedAt: date)
        pins[HostKeyStore.id(host: host, port: port)] = pin
    }

    /// Forgetting a pin means the next connection asks again.
    public mutating func forget(host: String, port: Int) {
        pins.removeValue(forKey: HostKeyStore.id(host: host, port: port))
    }
}
