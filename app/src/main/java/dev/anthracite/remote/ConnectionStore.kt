package dev.anthracite.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
        hostKey = prefs.getString(KEY_HOST_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) },
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
        if (prefs.getString(KEY_HOST, "") != trimmedHost || prefs.getInt(KEY_SSH_PORT, 22) != sshPort) {
            editor.remove(KEY_HOST_KEY).remove(KEY_HOST_KEY_TYPE).remove(KEY_FINGERPRINT)
        }
        editor.apply()
    }

    fun saveHostKey(key: ByteArray, type: String, fingerprint: String) {
        prefs.edit()
            .putString(KEY_HOST_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
            .putString(KEY_HOST_KEY_TYPE, type)
            .putString(KEY_FINGERPRINT, fingerprint)
            .apply()
    }

    fun clearHostKey() {
        prefs.edit().remove(KEY_HOST_KEY).remove(KEY_HOST_KEY_TYPE).remove(KEY_FINGERPRINT).apply()
    }

    fun saveSecrets(secrets: SavedSecrets) {
        secretFile().writeBytes(CredentialCrypto.seal(secrets, key()))
    }

    fun secrets(): SavedSecrets {
        val file = secretFile()
        if (!file.isFile) return SavedSecrets(null, null, null)
        return CredentialCrypto.open(file.readBytes(), key())
    }

    fun forget() {
        prefs.edit().clear().apply()
        secretFile().delete()
    }

    private fun secretFile() = java.io.File(app.noBackupFilesDir, SECRET_FILE)

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
