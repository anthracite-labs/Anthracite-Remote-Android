import Foundation
#if canImport(Security)
import Security
#endif

/// Secrets live in the Keychain, never in UserDefaults and never in the app's
/// documents directory. This protocol exists so the store can be exercised in
/// tests without a device.
public protocol SecretStore: AnyObject {
    func set(_ value: Data, for key: String) throws
    func value(for key: String) throws -> Data?
    func remove(_ key: String) throws
}

public enum CredentialStoreError: Error, Equatable {
    case keychain(Int32)
    case notFound
    case corrupted
}

#if canImport(Security)
/// Keychain items are per-device, `ThisDeviceOnly` (never synced or restored to
/// another device), and readable only after the first unlock.
public final class KeychainSecretStore: SecretStore {
    private let service: String

    public init(service: String = "dev.anthracite.remote.ios") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    public func set(_ value: Data, for key: String) throws {
        var attributes = query(key)
        attributes[kSecValueData as String] = value
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(attributes as CFDictionary)
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw CredentialStoreError.keychain(status) }
    }

    public func value(for key: String) throws -> Data? {
        var attributes = query(key)
        attributes[kSecReturnData as String] = true
        attributes[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(attributes as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw CredentialStoreError.keychain(status) }
        return item as? Data
    }

    public func remove(_ key: String) throws {
        let status = SecItemDelete(query(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CredentialStoreError.keychain(status)
        }
    }
}
#endif

/// Used by tests and the simulator previews. Never a production store: it has no
/// persistence and no protection.
public final class MemorySecretStore: SecretStore {
    private var items: [String: Data] = [:]
    private let lock = NSLock()

    public init() {}

    public func set(_ value: Data, for key: String) throws {
        lock.lock(); items[key] = value; lock.unlock()
    }

    public func value(for key: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return items[key]
    }

    public func remove(_ key: String) throws {
        lock.lock(); items.removeValue(forKey: key); lock.unlock()
    }
}

public enum ServerAuthKind: String, Codable, Sendable {
    case password
    case privateKey
}

public struct ServerProfile: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var host: String
    public var port: Int
    public var username: String
    public var authKind: ServerAuthKind

    public init(
        id: UUID = UUID(),
        name: String,
        host: String,
        port: Int = 22,
        username: String,
        authKind: ServerAuthKind
    ) {
        self.id = id
        self.name = name
        self.host = host
        self.port = port
        self.username = username
        self.authKind = authKind
    }

    public func validate() -> String? {
        if host.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the server's host name or IP address." }
        if host.contains(" ") || host.contains("/") { return "The host must be a name or address, not a URL." }
        if port < 1 || port > 65535 { return "The port must be between 1 and 65535." }
        if username.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the SSH user name." }
        if username.contains(" ") { return "The SSH user name cannot contain spaces." }
        return nil
    }
}

/// Profiles and their secrets. The list is JSON in the Keychain as well, so a
/// device backup does not leak a host inventory through a plist.
public final class ProfileStore {
    private let store: SecretStore
    private let profilesKey = "profiles.v1"
    private let pinKey = "hostkeys.v1"

    public init(store: SecretStore) {
        self.store = store
    }

    public func profiles() throws -> [ServerProfile] {
        guard let data = try store.value(for: profilesKey) else { return [] }
        do {
            return try JSONDecoder().decode([ServerProfile].self, from: data)
        } catch {
            // A corrupt list is reported, never silently replaced: losing every
            // connection because one byte is wrong is worse than asking.
            throw CredentialStoreError.corrupted
        }
    }

    public func save(_ profile: ServerProfile) throws {
        var list = try profiles()
        if let index = list.firstIndex(where: { $0.id == profile.id }) {
            list[index] = profile
        } else {
            list.append(profile)
        }
        try persist(list)
    }

    public func delete(_ id: UUID) throws {
        var list = try profiles()
        list.removeAll { $0.id == id }
        try persist(list)
        try store.remove(key: secretKey(id, kind: .password))
        try store.remove(key: secretKey(id, kind: .privateKey))
    }

    private func persist(_ list: [ServerProfile]) throws {
        try store.set(try JSONEncoder().encode(list), for: profilesKey)
    }

    private func secretKey(_ id: UUID, kind: ServerAuthKind) -> String {
        "secret.\(id.uuidString).\(kind.rawValue)"
    }

    public func secret(for profile: ServerProfile) throws -> String? {
        guard let data = try store.value(for: secretKey(profile.id, kind: profile.authKind)) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func saveSecret(_ secret: String, for profile: ServerProfile) throws {
        try store.set(Data(secret.utf8), for: secretKey(profile.id, kind: profile.authKind))
    }

    public func clearSecret(for profile: ServerProfile) throws {
        try store.remove(key: secretKey(profile.id, kind: profile.authKind))
    }

    public func hostKeys() throws -> HostKeyStore {
        guard let data = try store.value(for: pinKey) else { return HostKeyStore() }
        let pins = try JSONDecoder().decode([HostKeyPin].self, from: data)
        return HostKeyStore(pins: pins)
    }

    public func save(hostKeys: HostKeyStore) throws {
        try store.set(try JSONEncoder().encode(hostKeys.all), for: pinKey)
    }
}
