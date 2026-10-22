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

/**
 * The secret store is the only place a stored credential can be lost or leaked,
 * so its rules are pinned here rather than only exercised through Android.
 *
 * `FileSecretStore` carries all the behaviour; `KeystoreSecretStore` only chooses
 * the directory, which is why this runs on the JVM.
 */
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

            // A sealed blob is never shorter than the GCM envelope, so the
            // fixture uses a realistic size rather than an arbitrary small one.
            store.write("github_token.v1", ByteArray(64) { 1 })
            assertArrayEquals(ByteArray(64) { 1 }, store.read("github_token.v1"))

            store.write("github_token.v1", ByteArray(64) { 2 })
            assertArrayEquals(ByteArray(64) { 2 }, store.read("github_token.v1"))

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

            File(directory, "0123456789abcdef.bin")
                .writeBytes(ByteArray(CredentialCrypto.MAX_CIPHERTEXT_BYTES + 1))
            assertThrows(HostStoreException::class.java) { store.read("0123456789abcdef.bin") }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid names and empty payloads are refused before touching the disk`() {
        val directory = tempDirectory()
        try {
            val store = FileSecretStore(directory)
            assertThrows(IllegalArgumentException::class.java) {
                store.write("../escape.bin", ByteArray(64))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.write("empty.bin", ByteArray(0))
            }
            assertThrows(IllegalArgumentException::class.java) { store.read("../escape.bin") }
            assertThrows(IllegalArgumentException::class.java) { store.delete("../escape.bin") }
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
