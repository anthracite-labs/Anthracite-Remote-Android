package dev.anthracite.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

class AtomicFileStoreTest {
    @Test
    fun `atomic replacement publishes a complete new credential blob`() {
        val directory = Files.createTempDirectory("anthracite-atomic").toFile()
        val destination = File(directory, "ssh-secrets.bin")
        try {
            val replacement = ByteArray(8 * 1024) { (it % 251).toByte() }
            AtomicFileStore.write(destination, replacement)
            assertArrayEquals(replacement, destination.readBytes())
            assertFalse(directory.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `interrupted replacement preserves the previous complete credential blob`() {
        val directory = Files.createTempDirectory("anthracite-atomic").toFile()
        val destination = File(directory, "ssh-secrets.bin")
        val original = "previous encrypted ciphertext".toByteArray()
        try {
            destination.writeBytes(original)
            try {
                AtomicFileStore.replace(destination) { temporary ->
                    FileOutputStream(temporary).use { output ->
                        output.write("partial replacement".toByteArray())
                        output.fd.sync()
                    }
                    throw IOException("simulated interrupted update")
                }
                throw AssertionError("interrupted replacement unexpectedly completed")
            } catch (_: IOException) {
                // expected: the rename was never reached
            }
            assertArrayEquals(original, destination.readBytes())
            assertFalse(directory.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `forget removes the blob and stale owned temporary files`() {
        val directory = Files.createTempDirectory("anthracite-atomic").toFile()
        val destination = File(directory, "ssh-secrets.bin")
        val stale = File(directory, ".ssh-secrets.bin.interrupted.tmp")
        try {
            destination.writeBytes(byteArrayOf(1))
            stale.writeBytes(byteArrayOf(2))
            AtomicFileStore.deleteWithTemporaryFiles(destination)
            assertFalse(destination.exists())
            assertFalse(stale.exists())
            assertTrue(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
