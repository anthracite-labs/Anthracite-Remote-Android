package dev.anthracite.remote.host

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.anthracite.remote.AuthMethod
import dev.anthracite.remote.CredentialCrypto
import dev.anthracite.remote.SavedSecrets
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Multi-host storage with the same fail-closed rules as the legacy single-profile
 * store: a corrupt list is never partially trusted, secrets are sealed with an
 * Android Keystore key, and a failed commit rolls back rather than leaving a
 * profile pointing at a different credential.
 *
 * The legacy [dev.anthracite.remote.ConnectionStore] is **not** modified or
 * deleted: existing users keep their working OpenCode journey. A one-time import
 * copies that profile in as `IMPORTED_LEGACY`, and the copy can be forgotten
 * independently.
 */
internal enum class HostOrigin { CREATED, IMPORTED_LEGACY }

internal data class HostProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: AuthMethod,
    val remotePort: Int,
    val keyName: String,
    val hostKey: ByteArray?,
    val hostKeyType: String,
    val fingerprint: String,
    val origin: HostOrigin,
    val lastUsedAtMillis: Long,
) {
    val displayLabel: String get() = label.ifBlank { username + "@" + host }
    val endpoint: String get() = "$username@$host:$port"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HostProfile) return false
        return id == other.id &&
            label == other.label &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            auth == other.auth &&
            remotePort == other.remotePort &&
            keyName == other.keyName &&
            (hostKey?.contentEquals(other.hostKey) ?: (other.hostKey == null)) &&
            hostKeyType == other.hostKeyType &&
            fingerprint == other.fingerprint &&
            origin == other.origin &&
            lastUsedAtMillis == other.lastUsedAtMillis
    }

    override fun hashCode(): Int = listOf(id, host, port, username, fingerprint).hashCode()
}

/** Key/value abstraction so profile persistence is testable without Android. */
internal interface Preferences {
    fun string(key: String): String?
    fun int(key: String, fallback: Int): Int
    fun boolean(key: String, fallback: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun commit(): Boolean
}

/** Secret blob abstraction so sealing is testable without Android Keystore. */
internal interface SecretStore {
    fun write(name: String, bytes: ByteArray)
    fun read(name: String): ByteArray?
    fun delete(name: String)
}

internal class HostStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal class HostStore(
    private val preferences: Preferences,
    private val secrets: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    fun list(): List<HostProfile> {
        val encoded = preferences.string(KEY_HOSTS) ?: return emptyList()
        val decoded = ProfileCodec.decode(encoded)
            ?: throw HostStoreException("Saved host list is unreadable. It was left untouched.")
        return decoded.sortedByDescending { it.lastUsedAtMillis }
    }

    fun find(id: String): HostProfile? = list().firstOrNull { it.id == id }

    fun hasSecrets(id: String): Boolean = runCatching { secrets.read(secretName(id)) != null }.getOrDefault(false)

    /**
     * Writes the credential blob first and only then commits the profile list.
     * If the commit fails, the previous blob is restored so the profile can never
     * authenticate with another host's credentials.
     */
    fun save(profile: HostProfile, credential: SavedSecrets?, key: SecretKey) {
        require(profile.id.matches(ID_PATTERN)) { "invalid host id" }
        require(profile.host.isNotBlank()) { "host is required" }
        require(profile.port in 1..65535) { "port is invalid" }
        require(profile.username.isNotBlank()) { "username is required" }
        val existing = list()
        if (existing.size >= MAX_HOSTS && existing.none { it.id == profile.id }) {
            throw HostStoreException("You can save at most $MAX_HOSTS hosts on this phone.")
        }
        val priorBlob = runCatching { secrets.read(secretName(profile.id)) }.getOrNull()
        if (credential != null) {
            val sealed = CredentialCrypto.seal(credential, key)
            try {
                secrets.write(secretName(profile.id), sealed)
            } finally {
                sealed.fill(0)
            }
        }
        val updated = existing.filterNot { it.id == profile.id } + profile
        try {
            persist(updated)
        } catch (error: Exception) {
            runCatching {
                if (credential != null) {
                    if (priorBlob == null) {
                        secrets.delete(secretName(profile.id))
                    } else {
                        secrets.write(secretName(profile.id), priorBlob)
                    }
                }
            }
            throw error
        }
    }

    fun secretsFor(profile: HostProfile, key: SecretKey): SavedSecrets {
        val blob = try {
            secrets.read(secretName(profile.id))
        } catch (error: Exception) {
            throw HostStoreException("Saved credentials for ${profile.displayLabel} are unavailable", error)
        } ?: return SavedSecrets(null, null, null)
        return try {
            CredentialCrypto.open(blob, key)
        } catch (error: Exception) {
            throw HostStoreException("Saved credentials for ${profile.displayLabel} could not be opened", error)
        }
    }

    /**
     * Removes only local data. Host files, sessions and containers are untouched,
     * and nothing here can reach the host at all.
     */
    fun forget(id: String) {
        val existing = list()
        if (existing.none { it.id == id }) return
        // Delete the credential first: if the profile list cannot be committed
        // afterwards, the user still cannot authenticate with a half-removed host.
        runCatching { secrets.delete(secretName(id)) }
            .onFailure { throw HostStoreException("Could not erase saved credentials for this host", it) }
        persist(existing.filterNot { it.id == id })
    }

    fun markUsed(id: String, atMillis: Long) {
        val existing = list()
        if (existing.none { it.id == id }) return
        persist(existing.map { if (it.id == id) it.copy(lastUsedAtMillis = atMillis) else it })
    }

    fun updateHostKey(id: String, hostKey: ByteArray, keyType: String, fingerprint: String) {
        val existing = list()
        if (existing.none { it.id == id }) return
        persist(
            existing.map {
                if (it.id == id) it.copy(hostKey = hostKey, hostKeyType = keyType, fingerprint = fingerprint) else it
            },
        )
    }

    fun newId(): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * One-time import of the legacy single-profile connection.
     *
     * The legacy record is read, never written or deleted, so an existing
     * OpenCode journey keeps working even if the user removes the imported copy.
     * If the legacy credential cannot be opened, the host is still imported
     * without a credential and the UI asks for it again.
     */
    fun importLegacyIfNeeded(
        legacy: HostProfile,
        legacySecrets: SavedSecrets?,
        key: SecretKey,
    ): HostProfile? {
        if (preferences.boolean(KEY_LEGACY_IMPORTED, false)) return null
        val existing = runCatching { list() }.getOrElse { emptyList() }
        var imported: HostProfile? = null
        if (existing.none { it.origin == HostOrigin.IMPORTED_LEGACY && it.endpoint == legacy.endpoint }) {
            val profile = legacy.copy(
                id = newId(),
                origin = HostOrigin.IMPORTED_LEGACY,
                label = legacy.label.ifBlank { "Imported host" },
            )
            val credential = legacySecrets?.takeIf {
                it.password != null || it.privateKey != null
            }
            save(profile, credential, key)
            imported = profile
        }
        preferences.putBoolean(KEY_LEGACY_IMPORTED, true)
        check(preferences.commit()) { "Could not record the one-time host import" }
        return imported
    }

    private fun persist(hosts: List<HostProfile>) {
        val previous = preferences.string(KEY_HOSTS)
        preferences.putString(KEY_HOSTS, ProfileCodec.encode(hosts))
        if (preferences.commit()) return
        // A failed commit leaves the in-memory value updated while the disk keeps
        // the old one, so the app would show a host that disappears on restart.
        // Put the previous value back before reporting the failure.
        if (previous == null) preferences.remove(KEY_HOSTS) else preferences.putString(KEY_HOSTS, previous)
        throw HostStoreException("Could not save the host list on this phone")
    }

    private fun secretName(id: String): String = SecretNames.hostCredential(id)

    companion object {
        const val MAX_HOSTS = 50
        const val KEY_HOSTS = "hosts_v1"
        const val KEY_LEGACY_IMPORTED = "legacy_imported_v1"
        val ID_PATTERN = Regex("[a-f0-9]{16}")
    }
}

/**
 * Line-oriented profile encoding.
 *
 * `%` escapes tabs, newlines and percent signs so a host label can contain any
 * printable text without corrupting the record. A decode that does not match the
 * expected shape returns null: the caller then reports an unreadable list instead
 * of silently dropping hosts.
 */
internal object ProfileCodec {
    private const val SEPARATOR = '\t'
    private const val RECORD_SEPARATOR = '\n'
    private const val VERSION = "1"

    fun encode(hosts: List<HostProfile>): String = hosts.joinToString(RECORD_SEPARATOR.toString()) { host ->
        val fields = listOf(
            VERSION,
            host.id,
            escape(host.label),
            escape(host.host),
            host.port.toString(),
            escape(host.username),
            host.auth.name,
            host.remotePort.toString(),
            escape(host.keyName),
            host.hostKeyType,
            escape(host.fingerprint),
            host.origin.name,
            host.lastUsedAtMillis.toString(),
            host.hostKey?.let { java.util.Base64.getEncoder().encodeToString(it) }.orEmpty(),
        )
        fields.joinToString(SEPARATOR.toString())
    }

    fun decode(encoded: String): List<HostProfile>? {
        if (encoded.isBlank()) return emptyList()
        val hosts = mutableListOf<HostProfile>()
        for (record in encoded.split(RECORD_SEPARATOR)) {
            if (record.isEmpty()) continue
            val fields = record.split(SEPARATOR)
            if (fields.size != 14 || fields[0] != VERSION) return null
            val port = fields[4].toIntOrNull() ?: return null
            val auth = runCatching { AuthMethod.valueOf(fields[6]) }.getOrNull() ?: return null
            val remotePort = fields[7].toIntOrNull() ?: return null
            val origin = runCatching { HostOrigin.valueOf(fields[11]) }.getOrNull() ?: return null
            val lastUsed = fields[12].toLongOrNull() ?: 0L
            val hostKey = fields[13].takeIf { it.isNotEmpty() }?.let { encodedKey ->
                runCatching { java.util.Base64.getDecoder().decode(encodedKey) }.getOrNull()
            }
            if (!HostStore.ID_PATTERN.matches(fields[1])) return null
            if (port !in 1..65535 || remotePort !in 1..65535) return null
            hosts += HostProfile(
                id = fields[1],
                label = unescape(fields[2]) ?: return null,
                host = unescape(fields[3]) ?: return null,
                port = port,
                username = unescape(fields[5]) ?: return null,
                auth = auth,
                remotePort = remotePort,
                keyName = unescape(fields[8]) ?: return null,
                hostKeyType = fields[9],
                fingerprint = unescape(fields[10]) ?: return null,
                origin = origin,
                lastUsedAtMillis = lastUsed,
                hostKey = hostKey,
            )
        }
        return hosts
    }

    private fun escape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '%' -> builder.append("%25")
                '\t' -> builder.append("%09")
                '\n' -> builder.append("%0A")
                '\r' -> builder.append("%0D")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun unescape(value: String): String? {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '%') {
                builder.append(char)
                index++
                continue
            }
            if (index + 2 >= value.length) return null
            val code = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            builder.append(code.toChar())
            index += 3
        }
        return builder.toString()
    }
}

// ---------------------------------------------------------------------------
// Android adapters
// ---------------------------------------------------------------------------

internal class SharedPreferencesAdapter(private val preferences: SharedPreferences) : Preferences {
    private var pending: SharedPreferences.Editor? = null

    private fun editor(): SharedPreferences.Editor = pending ?: preferences.edit().also { pending = it }

    override fun string(key: String): String? = preferences.getString(key, null)

    override fun int(key: String, fallback: Int): Int = preferences.getInt(key, fallback)

    override fun boolean(key: String, fallback: Boolean): Boolean = preferences.getBoolean(key, fallback)

    override fun putString(key: String, value: String) {
        editor().putString(key, value)
    }

    override fun putInt(key: String, value: Int) {
        editor().putInt(key, value)
    }

    override fun putBoolean(key: String, value: Boolean) {
        editor().putBoolean(key, value)
    }

    override fun remove(key: String) {
        editor().remove(key)
    }

    /** Applies every value written since the previous commit as one transaction. */
    override fun commit(): Boolean {
        val current = pending ?: return true
        pending = null
        return current.commit()
    }
}

/**
 * Valid names for sealed secret blobs.
 *
 * One rule for every secret the app keeps, so a new feature cannot pick a name
 * the store silently refuses. Names are flat file names: lowercase, digits,
 * dots, dashes and underscores only, no path separators, no leading dot, no
 * `..`, and short enough for any filesystem the app might run on.
 */
internal object SecretNames {
    const val MAX_LENGTH = 64
    private val PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun isValid(name: String): Boolean =
        name.length <= MAX_LENGTH && PATTERN.matches(name) && !name.contains("..")

    fun require(name: String): String {
        require(isValid(name)) { "invalid secret name" }
        return name
    }

    /** Host profile credentials keep the id as their prefix so they are easy to audit. */
    fun hostCredential(profileId: String): String {
        require(profileId.matches(HostStore.ID_PATTERN)) { "invalid host id" }
        return "$profileId.bin"
    }
}

/**
 * Sealed secret blobs stored as files.
 *
 * Writes go to a temporary file in the same directory, are flushed to disk, made
 * owner-only and then renamed over the target, so a crash cannot leave a
 * half-written credential behind. Reads validate the name and the length before
 * returning anything.
 *
 * This class has no Android dependency, which is what makes the round-trip
 * testable on the JVM; [KeystoreSecretStore] only chooses the directory.
 */
internal class FileSecretStore(private val directory: File) : SecretStore {
    override fun write(name: String, bytes: ByteArray) {
        SecretNames.require(name)
        require(bytes.isNotEmpty()) { "refusing to store an empty secret" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw HostStoreException("Could not create the secret directory")
        }
        val target = File(directory, name)
        val temporary = File.createTempFile(".${target.name}.", ".tmp", directory)
        try {
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw HostStoreException("Could not replace the secret file")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun read(name: String): ByteArray? {
        SecretNames.require(name)
        val file = File(directory, name)
        if (!file.isFile) return null
        if (file.length() !in CredentialCrypto.MIN_CIPHERTEXT_BYTES.toLong()..CredentialCrypto.MAX_CIPHERTEXT_BYTES.toLong()) {
            throw HostStoreException("Stored secret has an invalid length")
        }
        return file.readBytes()
    }

    override fun delete(name: String) {
        SecretNames.require(name)
        val file = File(directory, name)
        if (file.exists() && !file.delete() && file.exists()) {
            throw HostStoreException("Could not delete the secret file")
        }
    }
}

/**
 * Keystore-sealed secret blobs in the app's no-backup directory.
 *
 * Uses the same fail-closed rules as the legacy store: a dedicated alias, GCM
 * with randomized IVs, a directory excluded from backup, and atomic replacement.
 */
internal class KeystoreSecretStore(context: Context) : SecretStore {
    private val files = FileSecretStore(File(context.applicationContext.noBackupFilesDir, "hosts"))

    override fun write(name: String, bytes: ByteArray) = files.write(name, bytes)

    override fun read(name: String): ByteArray? = files.read(name)

    override fun delete(name: String) = files.delete(name)
}

internal object HostKeys {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "anthracite_remote_hosts"

    fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
