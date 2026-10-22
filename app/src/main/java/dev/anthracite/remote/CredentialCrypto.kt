package dev.anthracite.remote

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM blob used for SSH secrets.
 *
 * The cipher provider is chosen explicitly so a Bouncy Castle JCE provider cannot
 * intercept Android Keystore keys. JSch's Ed25519 and X25519 code uses Bouncy
 * Castle's lightweight API and does not need that provider registered.
 */
internal object CredentialCrypto {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD = "anthracite-remote-ssh-v1".toByteArray()
    internal const val MAX_FIELD = 256 * 1024
    // Three length-prefixed fields, a maximum IV, and a GCM tag.
    internal const val MAX_CIPHERTEXT_BYTES = 1 + 16 + (3 * (4 + MAX_FIELD)) + 16

    fun cipher(): Cipher {
        for (name in listOf("AndroidKeyStoreBCWorkaround", "AndroidOpenSSL")) {
            val selected = runCatching { Cipher.getInstance(TRANSFORMATION, name) }.getOrNull()
            if (selected != null) return selected
        }
        val fallback = Cipher.getInstance(TRANSFORMATION)
        if (fallback.provider.name.equals("BC", ignoreCase = true)) {
            throw GeneralSecurityException("AES-GCM resolved to Bouncy Castle, which cannot use Android Keystore keys")
        }
        return fallback
    }

    fun seal(secrets: SavedSecrets, key: SecretKey): ByteArray {
        val cipher = cipher()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        val body = cipher.doFinal(encode(secrets))
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun open(blob: ByteArray, key: SecretKey): SavedSecrets {
        if (blob.size !in 13..MAX_CIPHERTEXT_BYTES) error("secret blob length is invalid")
        val ivLength = blob[0].toInt() and 0xff
        if (ivLength !in 12..16 || blob.size < 1 + ivLength + 16) error("secret blob iv is invalid")
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val body = blob.copyOfRange(1 + ivLength, blob.size)
        val cipher = cipher()
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return decode(cipher.doFinal(body))
    }

    fun encode(secrets: SavedSecrets): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeField(out, secrets.password?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        writeField(out, secrets.privateKey ?: ByteArray(0))
        writeField(out, secrets.passphrase?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        return out.toByteArray()
    }

    fun decode(plain: ByteArray): SavedSecrets {
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
        val passwordBytes = read()
        val key = read().takeIf { it.isNotEmpty() }
        val passphraseBytes = read()
        if (offset != plain.size) error("secret blob has trailing data")
        val password = passwordBytes.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }
        val passphrase = passphraseBytes.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }
        return SavedSecrets(password, key, passphrase)
    }

    private fun writeField(out: java.io.ByteArrayOutputStream, bytes: ByteArray) {
        val size = bytes.size
        require(size <= MAX_FIELD) { "secret field is too large" }
        out.write(size ushr 24)
        out.write(size ushr 16)
        out.write(size ushr 8)
        out.write(size)
        out.write(bytes)
    }
}
