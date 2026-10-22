package dev.anthracite.remote.terminal

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One FIFO per PTY. enqueue never waits for network I/O (including a blocked flush). */
internal class TerminalWriter(
    private val output: OutputStream,
    private val onFailure: (Exception) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<() -> Unit>(MAX_PENDING_CHUNKS)
    private val worker = Thread({ drain() }, "anthracite-pty-writer").apply {
        isDaemon = true
        start()
    }

    fun enqueue(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES || closed.get()) return false
        // Copy because IMEs and callers can reuse the buffer after this returns.
        val copy = bytes.copyOf()
        return offer { output.write(copy); output.flush() }
    }

    /** PTY resize is network I/O too: serialize it with input, never on the UI thread. */
    fun enqueueResize(resize: () -> Unit): Boolean = offer(resize)

    private fun offer(operation: () -> Unit): Boolean {
        if (closed.get()) return false
        // Report queue admission, not a later transport outcome. A fast worker
        // may fail (and close the session) before the caller returns; changing
        // this result after admission would falsely report that input was rejected.
        // close() clears anything still queued and onFailure reports I/O errors.
        return queue.offer(operation)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        worker.interrupt()
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                val operation = queue.take()
                if (closed.get()) break
                operation()
            }
        } catch (_: InterruptedException) {
            // Shutdown: the channel owner also disconnects the underlying PTY.
        } catch (error: Exception) {
            if (closed.compareAndSet(false, true)) onFailure(error)
        } finally {
            closed.set(true)
            queue.clear()
        }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 64 * 1024
        const val MAX_PENDING_CHUNKS = 32
    }
}
