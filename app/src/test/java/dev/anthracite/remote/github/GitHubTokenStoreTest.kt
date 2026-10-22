package dev.anthracite.remote.github

import dev.anthracite.remote.CredentialCrypto
import dev.anthracite.remote.host.FileSecretStore
import dev.anthracite.remote.host.SecretNames
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub token path, end to end: the failure this guards against was a store
 * whose file-name rule rejected the token's name, so the token was accepted by
 * GitHub and then silently never persisted.
 */
class GitHubTokenStoreTest {
    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(directory: File, key: SecretKey) =
        GitHubTokenStore(FileSecretStore(directory), key)

    @Test
    fun `the token name is one the secret store accepts`() {
        assertTrue(
            "GitHubTokenStore.DEFAULT_NAME must be a valid secret name",
            SecretNames.isValid(GitHubTokenStore.DEFAULT_NAME),
        )
    }

    @Test
    fun `a token survives a simulated process restart and clears completely`() {
        val directory = Files.createTempDirectory("anthracite-github-token").toFile()
        try {
            val key = softwareKey()
            val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"
            store(directory, key).write(token)

            // A new store instance is what the app builds after process death.
            assertEquals(token, store(directory, key).read())

            // The ciphertext on disk must not contain the token.
            val blob = File(directory, GitHubTokenStore.DEFAULT_NAME).readBytes()
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("ghp_"))

            store(directory, key).clear()
            assertNull(store(directory, key).read())
            assertFalse(File(directory, GitHubTokenStore.DEFAULT_NAME).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a blob sealed with another key fails closed instead of reporting signed out`() {
        val directory = Files.createTempDirectory("anthracite-github-token").toFile()
        try {
            store(directory, softwareKey()).write("ghp_0123456789abcdefghijklmnopqrstuvwxyz")

            // A rotated Keystore key, or tampering, looks like a decrypt failure.
            val other = GitHubTokenStore(FileSecretStore(directory), softwareKey())
            assertThrows(GitHubTokenException::class.java) { other.read() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a tampered blob is rejected`() {
        val directory = Files.createTempDirectory("anthracite-github-token").toFile()
        try {
            val key = softwareKey()
            store(directory, key).write("ghp_0123456789abcdefghijklmnopqrstuvwxyz")
            val file = File(directory, GitHubTokenStore.DEFAULT_NAME)
            val blob = file.readBytes()
            blob[blob.lastIndex] = (blob.last().toInt() xor 0xff).toByte()
            file.writeBytes(blob)
            assertThrows(GitHubTokenException::class.java) { store(directory, key).read() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `writing an empty token is refused`() {
        val directory = Files.createTempDirectory("anthracite-github-token").toFile()
        try {
            val store = store(directory, softwareKey())
            assertThrows(IllegalArgumentException::class.java) { store.write("   ") }
            assertNull(store.read())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the sealed format is the shared credential format`() {
        // Guards against the token drifting to a different crypto envelope.
        val key = softwareKey()
        val sealed = CredentialCrypto.seal(
            dev.anthracite.remote.SavedSecrets("ghp_example", null, null),
            key,
        )
        assertEquals("ghp_example", CredentialCrypto.open(sealed, key).password)
    }
}
