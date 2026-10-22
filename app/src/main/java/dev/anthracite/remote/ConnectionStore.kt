package dev.anthracite.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal enum class AuthMethod { PASSWORD, KEY }

internal data class SavedConnection(
    val host: String,
    val sshPort: Int,
    val username: String,
    val auth: AuthMethod,
    val remotePort: Int,
    val keyName: String,
    val hostKey: ByteArray?,
    val hostKeyType: String,
    val fingerprint: String,
)

internal data class SavedSecrets(
    val password: String?,
    val privateKey: ByteArray?,
    val passphrase: String?,
)

/** The encrypted blob could not be read without risking a partial or silent fallback. */
internal class StoredSecretsException(cause: Throwable) :
    IllegalStateException("Stored SSH credentials are unavailable", cause)

internal class ConnectionStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasConnection(): Boolean = prefs.getString(KEY_HOST, "").orEmpty().isNotBlank()

    fun load(): SavedConnection = SavedConnection(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        sshPort = prefs.getInt(KEY_SSH_PORT, 22),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        auth = if (prefs.getString(KEY_AUTH, AUTH_PASSWORD) == AUTH_KEY) AuthMethod.KEY else AuthMethod.PASSWORD,
        remotePort = prefs.getInt(KEY_REMOTE_PORT, 4096),
        keyName = prefs.getString(KEY_KEY_NAME, "").orEmpty(),
        // A corrupt preference must never be interpreted as a matching host key.
        hostKey = prefs.getString(KEY_HOST_KEY, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        },
        hostKeyType = prefs.getString(KEY_HOST_KEY_TYPE, "").orEmpty(),
        fingerprint = prefs.getString(KEY_FINGERPRINT, "").orEmpty(),
    )

    fun saveProfile(
        host: String,
        sshPort: Int,
        username: String,
        auth: AuthMethod,
        remotePort: Int,
        keyName: String,
    ) {
        val trimmedHost = host.trim()
        val editor = prefs.edit()
            .putString(KEY_HOST, trimmedHost)
            .putInt(KEY_SSH_PORT, sshPort)
            .putString(KEY_USER, username.trim())
            .putString(KEY_AUTH, if (auth == AuthMethod.KEY) AUTH_KEY else AUTH_PASSWORD)
            .putInt(KEY_REMOTE_PORT, remotePort)
            .putString(KEY_KEY_NAME, keyName)
        // Host keys are scoped to the network endpoint, not to credentials.
        if (prefs.getString(KEY_HOST, "") != trimmedHost || prefs.getInt(KEY_SSH_PORT, 22) != sshPort) {
            editor.remove(KEY_HOST_KEY).remove(KEY_HOST_KEY_TYPE).remove(KEY_FINGERPRINT)
        }
        persist(editor)
    }

    fun saveHostKey(key: ByteArray, type: String, fingerprint: String) {
        persist(
            prefs.edit()
                .putString(KEY_HOST_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
                .putString(KEY_HOST_KEY_TYPE, type)
                .putString(KEY_FINGERPRINT, fingerprint),
        )
    }

    fun clearHostKey() {
        persist(prefs.edit().remove(KEY_HOST_KEY).remove(KEY_HOST_KEY_TYPE).remove(KEY_FINGERPRINT))
    }

    fun saveSecrets(secrets: SavedSecrets) {
        val sealed = CredentialCrypto.seal(secrets, key())
        try {
            AtomicFileStore.write(secretFile(), sealed)
        } finally {
            sealed.fill(0)
        }
    }

    /**
     * Updates the encrypted credential blob before its profile points at it. If
     * the synchronous profile commit fails, restore the exact prior ciphertext
     * (including an unreadable blob) so an I/O failure cannot leave mismatched
     * connection metadata and credentials behind.
     */
    fun saveConnection(
        host: String,
        sshPort: Int,
        username: String,
        auth: AuthMethod,
        remotePort: Int,
        keyName: String,
        secrets: SavedSecrets,
    ) {
        val priorBlob = encryptedBlobForRollback()
        try {
            saveSecrets(secrets)
            try {
                saveProfile(host, sshPort, username, auth, remotePort, keyName)
            } catch (error: Exception) {
                try {
                    restoreEncryptedBlob(priorBlob)
                } catch (rollback: Exception) {
                    error.addSuppressed(rollback)
                }
                throw error
            }
        } finally {
            priorBlob?.fill(0)
        }
    }

    fun secrets(): SavedSecrets {
        val file = secretFile()
        if (!file.isFile) return SavedSecrets(null, null, null)
        return try {
            if (file.length() !in 13L..CredentialCrypto.MAX_CIPHERTEXT_BYTES.toLong()) {
                error("secret blob length is invalid")
            }
            CredentialCrypto.open(file.readBytes(), key())
        } catch (error: Exception) {
            // Keep the prior blob intact. A transient Android Keystore failure must
            // not silently erase the only recoverable credential copy.
            throw StoredSecretsException(error)
        }
    }

    fun forget() {
        // Delete the encrypted blob first. If storage denies that deletion, keep
        // the profile so the user is not falsely told the credential was erased.
        AtomicFileStore.deleteWithTemporaryFiles(secretFile())
        persist(prefs.edit().clear())
    }

    private fun encryptedBlobForRollback(): ByteArray? {
        val file = secretFile()
        if (!file.exists()) return null
        if (!file.isFile || file.length() !in 13L..CredentialCrypto.MAX_CIPHERTEXT_BYTES.toLong()) {
            error("existing credential blob cannot be safely replaced")
        }
        return file.readBytes()
    }

    private fun restoreEncryptedBlob(blob: ByteArray?) {
        if (blob == null) AtomicFileStore.deleteWithTemporaryFiles(secretFile())
        else AtomicFileStore.write(secretFile(), blob)
    }

    private fun persist(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Could not persist connection settings" }
    }

    private fun secretFile(): File = File(app.noBackupFilesDir, SECRET_FILE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "connection"
        const val SECRET_FILE = "ssh-secrets.bin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anthracite_remote_ssh"
        const val KEY_HOST = "host"
        const val KEY_SSH_PORT = "ssh_port"
        const val KEY_USER = "username"
        const val KEY_AUTH = "auth"
        const val KEY_REMOTE_PORT = "remote_port"
        const val KEY_KEY_NAME = "key_name"
        const val KEY_HOST_KEY = "host_key"
        const val KEY_HOST_KEY_TYPE = "host_key_type"
        const val KEY_FINGERPRINT = "fingerprint"
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}
