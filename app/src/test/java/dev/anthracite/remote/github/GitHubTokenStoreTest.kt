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
 * The token has to survive a process restart, which is exactly the path that was
 * broken: the GitHub token name and the host credential name were validated by
 * two different rules, and the GitHub one was rejected on write.
 */
class GitHubTokenStoreTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(directory: File, key: SecretKey) =
        GitHubTokenStore(FileSecretStore(directory), key)

    @Test
    fun `the default name is one the store accepts`() {
        assertTrue(GitHubTokenStore.DEFAULT_NAME, SecretNames.isValid(GitHubTokenStore.DEFAULT_NAME))
    }

    @Test
    fun `a written token is read back by a new store instance`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            val key = key()
            store(directory, key).write("ghp_example_token_value_123456")

            // A new instance stands in for the next app start: nothing is cached
            // in memory, so this is a real re-read from the sealed file.
            assertEquals("ghp_example_token_value_123456", store(directory, key).read())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the token is sealed on disk, not stored as plaintext`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            val token = "ghp_sealed_on_disk_987654321"
            store(directory, key()).write(token)
            val blob = File(directory, GitHubTokenStore.DEFAULT_NAME).readBytes()
            assertFalse(String(blob, Charsets.ISO_8859_1).contains(token))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `clearing removes the token`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            val key = key()
            val tokens = store(directory, key)
            tokens.write("ghp_clear_me_1234567890")
            tokens.clear()
            assertNull(tokens.read())
            assertFalse(File(directory, GitHubTokenStore.DEFAULT_NAME).exists())
            // Clearing again is not an error.
            tokens.clear()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable blob is an error, not a signed-out state`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            store(directory, key()).write("ghp_tamper_target_1234567890")
            val file = File(directory, GitHubTokenStore.DEFAULT_NAME)
            val blob = file.readBytes()
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
            file.writeBytes(blob)

            // A rotated Keystore key or a tampered file must be visible to the
            // user, not silently treated as "signed out".
            assertThrows(GitHubTokenException::class.java) { store(directory, key()).read() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a blank token is refused`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                store(directory, key()).write("   ")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the sealed blob uses the same format as an SSH credential`() {
        val directory = Files.createTempDirectory("anthracite-token").toFile()
        try {
            val key = key()
            store(directory, key).write("ghp_shared_format_1234567890")
            val blob = File(directory, GitHubTokenStore.DEFAULT_NAME).readBytes()
            // Same opener, same envelope: one crypto path, not two.
            assertEquals(
                "ghp_shared_format_1234567890",
                CredentialCrypto.open(blob, key).password,
            )
            assertTrue(blob.isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
