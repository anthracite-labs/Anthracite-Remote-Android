package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class CredentialCryptoTest {
    @Test
    fun `sealed secrets can be saved, loaded, overwritten, and forgotten`() {
        val key = softwareKey()
        val file = File.createTempFile("anthracite-secrets", ".bin")
        try {
            val first = SavedSecrets("CorrectHorse1", "private-key".toByteArray(), "phrase")
            file.writeBytes(CredentialCrypto.seal(first, key))
            val loaded = CredentialCrypto.open(file.readBytes(), key)
            assertEquals("CorrectHorse1", loaded.password)
            assertTrue(loaded.privateKey.contentEquals("private-key".toByteArray()))
            assertEquals("phrase", loaded.passphrase)

            file.writeBytes(CredentialCrypto.seal(SavedSecrets("CorrectHorse2", null, null), key))
            val replaced = CredentialCrypto.open(file.readBytes(), key)
            assertEquals("CorrectHorse2", replaced.password)
            assertNull(replaced.privateKey)
            assertNull(replaced.passphrase)

            assertTrue(file.delete())
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = softwareKey()
        val sealed = CredentialCrypto.seal(SavedSecrets("CorrectHorse1", null, null), key)
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex].toInt() xor 1).toByte()
        try {
            CredentialCrypto.open(sealed, key)
            throw AssertionError("tampered blob was accepted")
        } catch (_: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun `malformed plaintext fields are rejected instead of partially decoded`() {
        val truncated = byteArrayOf(0, 0, 0, 1, 7)
        try {
            CredentialCrypto.decode(truncated)
            throw AssertionError("truncated plaintext was accepted")
        } catch (_: IllegalStateException) {
            // expected
        }

        val valid = CredentialCrypto.encode(SavedSecrets("CorrectHorse1", null, null))
        try {
            CredentialCrypto.decode(valid + byteArrayOf(0))
            throw AssertionError("trailing plaintext was accepted")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `aes gcm does not select the bouncy castle jce provider`() {
        val cipher = CredentialCrypto.cipher()
        assertFalse(cipher.provider.name.equals("BC", ignoreCase = true))
    }

    private fun softwareKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
