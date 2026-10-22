package dev.anthracite.remote.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWriterTest {
    @Test
    fun `blocked output never blocks input and pending bytes are bounded and discarded on close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val writer = TerminalWriter(output) { error("Unexpected failure: $it") }
        try {
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repeat(TerminalWriter.MAX_PENDING_CHUNKS) { assertTrue(writer.enqueue(byteArrayOf(2))) }
            assertFalse(writer.enqueue(byteArrayOf(3)))
            assertFalse(writer.enqueue(ByteArray(TerminalWriter.MAX_CHUNK_BYTES + 1)))
            assertTrue("enqueue blocked behind I/O", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            writer.close()
            assertFalse(writer.enqueue(byteArrayOf(4)))
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test
    fun `writes retain order and copy caller buffers`() {
        val output = ByteArrayOutputStream()
        val written = CountDownLatch(1)
        val stream = object : OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                output.write(bytes, off, len)
            }
            override fun flush() {
                if (output.size() >= 4) written.countDown()
            }
        }
        val writer = TerminalWriter(stream) { error("Unexpected failure: $it") }
        try {
            val bytes = byteArrayOf(1, 2)
            assertTrue(writer.enqueue(bytes))
            bytes[0] = 9
            assertTrue(writer.enqueueResize { output.write(4) })
            assertTrue(writer.enqueue(byteArrayOf(3)))
            assertTrue(written.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 4, 3), output.toByteArray())
        } finally {
            writer.close()
        }
    }

    @Test
    fun `a blocked PTY resize cannot block the caller`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = TerminalWriter(ByteArrayOutputStream()) { error("Unexpected failure: $it") }
        try {
            assertTrue(writer.enqueueResize {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue("input waited for resize", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
        } finally {
            writer.close()
            release.countDown()
        }
    }

    @Test
    fun `one stalled terminal cannot hold another terminal's input`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stalled = TerminalWriter(object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }) { error("Unexpected stalled writer failure: $it") }
        val independent = ByteArrayOutputStream()
        val wrote = CountDownLatch(1)
        val other = TerminalWriter(object : OutputStream() {
            override fun write(value: Int) {
                independent.write(value)
                wrote.countDown()
            }
        }) { error("Unexpected second writer failure: $it") }
        try {
            assertTrue(stalled.enqueue(byteArrayOf(1)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(other.enqueue(byteArrayOf(7)))
            assertTrue(wrote.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(7), independent.toByteArray())
        } finally {
            stalled.close()
            other.close()
            release.countDown()
        }
    }

    @Test
    fun `write failure closes the queue and reports it`() {
        val failed = CountDownLatch(1)
        val writer = TerminalWriter(object : OutputStream() {
            override fun write(value: Int) { throw IOException("offline") }
        }) { failed.countDown() }
        assertTrue(writer.enqueue(byteArrayOf(1)))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(writer.enqueue(byteArrayOf(2)))
        writer.close()
    }
}
