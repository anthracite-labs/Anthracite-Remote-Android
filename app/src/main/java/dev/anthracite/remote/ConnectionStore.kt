package dev.anthracite.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
        val file = secretFile()
        file.writeBytes(encrypt(encode(secrets)))
    }

    fun secrets(): SavedSecrets {
        val file = secretFile()
        if (!file.isFile) return SavedSecrets(null, null, null)
        return decode(decrypt(file.readBytes()))
    }

    fun forget() {
        prefs.edit().clear().apply()
        secretFile().delete()
    }

    private fun secretFile() = java.io.File(app.noBackupFilesDir, SECRET_FILE)

    private fun encode(secrets: SavedSecrets): ByteArray {
        val out = ByteArrayOutputStream()
        writeField(out, secrets.password?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        writeField(out, secrets.privateKey ?: ByteArray(0))
        writeField(out, secrets.passphrase?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        return out.toByteArray()
    }

    private fun decode(plain: ByteArray): SavedSecrets {
        var offset = 0
        fun read(): ByteArray {
            if (offset + 4 > plain.size) error("secret blob truncated")
            val size = ((plain[offset].toInt() and 0xff) shl 24) or
                ((plain[offset + 1].toInt() and 0xff) shl 16) or
                ((plain[offset + 2].toInt() and 0xff) shl 8) or
                (plain[offset + 3].toInt() and 0xff)
            offset += 4
            if (size < 0 || size > MAX_FIELD || offset + size > plain.size) error("secret field is invalid")
            val bytes = plain.copyOfRange(offset, offset + size)
            offset += size
            return bytes
        }
        val password = read().takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
        val key = read().takeIf { it.isNotEmpty() }
        val passphrase = read().takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
        return SavedSecrets(password, key, passphrase)
    }

    private fun writeField(out: ByteArrayOutputStream, bytes: ByteArray) {
        val size = bytes.size
        out.write(size ushr 24)
        out.write(size ushr 16)
        out.write(size ushr 8)
        out.write(size)
        out.write(bytes)
    }

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

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 13) error("secret blob is too short")
        val ivLength = blob[0].toInt() and 0xff
        if (ivLength !in 12..16 || blob.size < 1 + ivLength + 16) error("secret blob iv is invalid")
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val body = blob.copyOfRange(1 + ivLength, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(body)
    }

    private companion object {
        const val PREFS = "connection"
        const val SECRET_FILE = "ssh-secrets.bin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anthracite_remote_ssh"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "anthracite-remote-ssh-v1".toByteArray()
        const val MAX_FIELD = 256 * 1024
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
