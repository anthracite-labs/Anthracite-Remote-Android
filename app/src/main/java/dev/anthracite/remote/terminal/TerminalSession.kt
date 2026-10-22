package dev.anthracite.remote.terminal

import dev.anthracite.remote.SshPty
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One terminal tab: a live PTY channel, a byte pump and an emulator.
 *
 * The session never assumes the remote program is a shell, an agent or a
 * well-behaved TUI. It moves opaque bytes in both directions and reports exactly
 * what the host reported (closed, exit status, exit signal) without inventing
 * success.
 *
 * Lifecycle semantics are deliberately narrow:
 * * [close] closes the SSH channel. For an ephemeral shell that ends the
 *   process; for a `tmux` attachment the host session keeps running.
 * * Detaching a persistent session is a `tmux detach-client` request, not a
 *   channel kill, and is driven by the tmux manager.
 * * Nothing here terminates host processes on network loss.
 */
internal class TerminalSession(
    val kind: Kind,
    val title: String,
    val emulator: TerminalEmulator,
    private val pty: SshPty,
    /** Optional host-side identity (tmux session name) for reconnect matching. */
    val tmuxSession: String? = null,
    /** Host-issued identity captured when the tab was attached. */
    val tmuxId: String? = null,
) {
    enum class Kind { SHELL, TMUX, COMMAND }

    interface Listener {
        /** Called from the reader thread whenever output was rendered. */
        fun onOutput() = Unit

        /** Called once, from the reader thread or a close request. */
        fun onClosed(state: ClosedState) = Unit

        fun onBlockedClipboardWrite() = Unit

        fun onTitle(title: String) = Unit

        fun onHyperlink(uri: String) = Unit
    }

    data class ClosedState(
        val reason: String,
        val exitStatus: Int = -1,
        val exitSignal: String? = null,
    ) {
        val exited: Boolean get() = exitStatus >= 0 || exitSignal != null
    }

    var listener: Listener? = null

    private val closed = AtomicBoolean(false)
    private val writer = TerminalWriter(pty.output) { error ->
        finish("Write to the host failed: ${error.message ?: "I/O error"}")
    }
    private val reader: Thread

    /** Bytes received from the host since the tab was opened, for honest reporting. */
    @Volatile
    var receivedBytes: Long = 0
        private set

    @Volatile
    var lastCloseState: ClosedState? = null
        private set

    init {
        emulator.listener = object : TerminalEmulator.Listener {
            override fun onReply(bytes: ByteArray) {
                write(bytes)
            }

            override fun onTitle(title: String) {
                listener?.onTitle(title)
            }

            override fun onBlockedClipboardWrite() {
                listener?.onBlockedClipboardWrite()
            }

            override fun onHyperlink(uri: String) {
                listener?.onHyperlink(uri)
            }
        }
        reader = Thread({ pump() }, "anthracite-pty-${title.take(24)}").apply {
            isDaemon = true
            start()
        }
    }

    val isOpen: Boolean get() = !closed.get() && pty.isOpen

    /** Nonblocking; false means input was not accepted (closed, too large or backpressured). */
    fun write(bytes: ByteArray): Boolean = !closed.get() && writer.enqueue(bytes)

    fun resize(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        emulator.resize(cols, rows)
        if (!closed.get()) {
            runCatching { pty.resize(cols, rows, widthPx, heightPx) }
        }
    }

    /** Sends Ctrl-C. Whether the program stops is the host program's decision. */
    fun interrupt() {
        write(TerminalKeys.INTERRUPT)
    }

    /**
     * Closes the SSH channel. This is a *detach* for tmux attachments and a
     * process-ending close for an ephemeral shell; the caller must label it
     * correctly in the UI.
     */
    fun close(reason: String = "Disconnected") {
        finish(reason)
    }

    private fun pump() {
        val buffer = ByteArray(READ_BUFFER)
        try {
            val input = pty.input
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                receivedBytes += read
                emulator.feed(if (read == buffer.size) buffer else buffer.copyOf(read))
                listener?.onOutput()
            }
            finish("The host closed this terminal channel")
        } catch (error: IOException) {
            finish("Connection lost: ${error.message ?: "I/O error"}")
        } catch (error: Exception) {
            finish("Terminal channel failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        val status = runCatching { pty.exitStatus }.getOrDefault(-1)
        val signal = runCatching { pty.exitSignal }.getOrNull()
        val state = ClosedState(reason, status, signal)
        lastCloseState = state
        writer.close()
        // Disconnect may wait for a stuck transport; never make the UI or reader
        // wait for it. The writer is per-session and cannot send to another tab.
        Thread({ pty.close() }, "anthracite-pty-close").apply { isDaemon = true; start() }
        // The emulator may still hold a partial UTF-8 sequence from a cut connection.
        emulator.resetDecoder()
        listener?.onClosed(state)
    }

    private companion object {
        const val READ_BUFFER = 8192
    }
}
