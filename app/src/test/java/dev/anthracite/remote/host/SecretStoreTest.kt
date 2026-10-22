package dev.anthracite.remote.host

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    private fun tempDirectory(): File = Files.createTempDirectory("anthracite-secrets").toFile()

    @Test
    fun `secret names are flat, lowercase and traversal-free`() {
        assertTrue(SecretNames.isValid("0123456789abcdef.bin"))
        assertTrue(SecretNames.isValid("github_token.v1"))

        assertFalse(SecretNames.isValid(""))
        assertFalse(SecretNames.isValid("GitHub_token.v1"))
        assertFalse(SecretNames.isValid("../github_token.v1"))
        assertFalse(SecretNames.isValid("a/b"))
        assertFalse(SecretNames.isValid(".hidden"))
        assertFalse(SecretNames.isValid("with space"))
        assertFalse(SecretNames.isValid("a".repeat(SecretNames.MAX_LENGTH + 1)))
        assertFalse(SecretNames.isValid("trailing.."))
    }

    @Test
    fun `host credential names keep the profile id and reject other ids`() {
        assertEquals("0123456789abcdef.bin", SecretNames.hostCredential("0123456789abcdef"))
        assertThrows(IllegalArgumentException::class.java) {
            SecretNames.hostCredential("not-a-profile-id")
        }
    }

    @Test
    fun `a blob round-trips and is replaced atomically`() {
        val directory = tempDirectory()
        try {
            val store = FileSecretStore(directory)
            assertNull(store.read("github_token.v1"))

            store.write("github_token.v1", ByteArray(20) { 1 })
            assertArrayEquals(ByteArray(20) { 1 }, store.read("github_token.v1"))

            store.write("github_token.v1", ByteArray(20) { 2 })
            assertArrayEquals(ByteArray(20) { 2 }, store.read("github_token.v1"))

            // No temporary file may survive a successful write.
            val leftovers = directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
            assertTrue("temporary files left behind: $leftovers", leftovers.isEmpty())

            store.delete("github_token.v1")
            assertNull(store.read("github_token.v1"))
            // Deleting an absent secret is not an error.
            store.delete("github_token.v1")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `truncated or oversized blobs are refused instead of being returned`() {
        val directory = tempDirectory()
        try {
            val store = FileSecretStore(directory)
            store.write("0123456789abcdef.bin", ByteArray(64))

            File(directory, "0123456789abcdef.bin").writeBytes(ByteArray(3))
            assertThrows(HostStoreException::class.java) { store.read("0123456789abcdef.bin") }

            assertThrows(IllegalArgumentException::class.java) {
                store.write("../escape.bin", ByteArray(64))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.write("empty.bin", ByteArray(0))
            }
            assertFalse(File(directory.parentFile, "escape.bin").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
