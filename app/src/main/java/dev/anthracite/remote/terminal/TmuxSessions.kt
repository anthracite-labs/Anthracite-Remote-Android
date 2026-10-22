package dev.anthracite.remote.terminal

import dev.anthracite.remote.ExecResult
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.host.PosixShell

/**
 * A host-side tmux session, as reported by `tmux list-sessions`.
 *
 * `name` and `id` are host-issued values. They are only ever used as *matched*
 * identities: nothing is executed with them unless they pass [PosixShell.isToolName].
 */
internal data class TmuxSession(
    val name: String,
    val id: String,
    val createdAtSeconds: Long,
    val attachedClients: Int,
    val windows: Int,
)

internal data class TmuxListResult(
    val installed: Boolean,
    val version: String?,
    val sessions: List<TmuxSession>,
    val unparsedLines: Int,
)

/**
 * Every tmux command string the app can send.
 *
 * Commands are constants or are built from validated tokens through
 * [PosixShell.quote]; no GUI text is interpolated. Session names are constrained
 * so a name can never be read as a tmux target separator (`:` or `.`) or as a
 * command line flag.
 *
 * Ordinary PTY attach is used deliberately: tmux control mode (`-C`/`-CC`)
 * would add a second protocol parser for panes and layouts with no demonstrated
 * requirement behind it.
 */
internal object TmuxCommands {
    const val VERSION = "command -v tmux >/dev/null 2>&1 && tmux -V"

    const val LIST =
        "tmux list-sessions -F '#{session_name}\t#{session_id}\t#{session_created}\t#{session_attached}\t#{session_windows}'"

    fun hasSession(name: String): String = "tmux has-session -t ${quoteName(name)}"

    /**
     * Creates the session if it does not exist and leaves it detached.
     * `-A` makes this idempotent, so reconnecting never launches a second copy.
     */
    fun createDetached(name: String, workingDirectory: String?): String {
        val target = quoteName(name)
        val directory = workingDirectory
            ?.takeIf { PosixShell.isQuotablePath(it) }
            ?.let { " -c ${PosixShell.quote(it)}" }
            ?: ""
        return "tmux new-session -A -d -s $target$directory"
    }

    fun attachCommand(name: String): String = "exec tmux attach-session -t ${quoteName(name)}"

    fun detachClients(name: String): String = "tmux detach-client -s ${quoteName(name)}"

    fun killSession(name: String): String = "tmux kill-session -t ${quoteName(name)}"

    /**
     * tmux reads `:` as a session/window separator and `.` as a window/pane
     * separator, so a name containing either addresses something other than the
     * session the user picked. Such names are refused rather than quoted.
     */
    fun quoteName(name: String): String {
        require(PosixShell.isToolName(name)) { "invalid tmux session name" }
        require(!name.contains(':') && !name.contains('.')) { "tmux session name contains a target separator" }
        return PosixShell.quote(name)
    }
}

/** Parses the fixed `list-sessions` format above; rows that do not match are counted, not guessed. */
internal fun parseTmuxSessions(stdout: String): Pair<List<TmuxSession>, Int> {
    val sessions = mutableListOf<TmuxSession>()
    var unparsed = 0
    for (line in stdout.lineSequence()) {
        if (line.isBlank()) continue
        val fields = line.split('\t')
        if (fields.size != 5) {
            unparsed++
            continue
        }
        val created = fields[2].toLongOrNull()
        val attached = fields[3].toIntOrNull()
        val windows = fields[4].toIntOrNull()
        if (created == null || attached == null || windows == null || fields[0].isBlank()) {
            unparsed++
            continue
        }
        sessions += TmuxSession(fields[0], fields[1], created, attached, windows)
    }
    return sessions to unparsed
}

internal fun parseTmuxVersion(stdout: String): String? {
    val line = stdout.lineSequence().firstOrNull { it.startsWith("tmux ") }?.trim() ?: return null
    return line.removePrefix("tmux ").take(32).ifBlank { null }
}

/**
 * Host-side session operations over the existing SSH connection.
 *
 * Failure modes are reported, never smoothed over: a missing tmux binary yields
 * `installed = false`, and a kill that cannot be verified returns the observed
 * `has-session` state instead of an assumed success.
 */
internal class TmuxManager(private val ssh: SshClient) {
    fun probe(timeoutMs: Long = TIMEOUT_MS): TmuxListResult {
        val version = ssh.exec(TmuxCommands.VERSION, timeoutMs = timeoutMs)
        if (version.exitCode != 0) {
            return TmuxListResult(false, null, emptyList(), 0)
        }
        val parsedVersion = parseTmuxVersion(version.stdout)
        val listed = ssh.exec(TmuxCommands.LIST, timeoutMs = timeoutMs)
        if (listed.exitCode != 0) {
            // `list-sessions` exits non-zero when a server runs with no sessions.
            val absent = listed.text.contains("no server running", ignoreCase = true) ||
                listed.text.contains("no sessions", ignoreCase = true)
            if (absent) return TmuxListResult(true, parsedVersion, emptyList(), 0)
            throw IllegalStateException("tmux could not list sessions: ${listed.text.take(200)}")
        }
        val (sessions, unparsed) = parseTmuxSessions(listed.stdout)
        return TmuxListResult(true, parsedVersion, sessions, unparsed)
    }

    fun create(name: String, workingDirectory: String?): ExecResult =
        ssh.exec(TmuxCommands.createDetached(name, workingDirectory), timeoutMs = TIMEOUT_MS)

    fun hasSession(name: String): Boolean =
        ssh.exec(TmuxCommands.hasSession(name), timeoutMs = TIMEOUT_MS).exitCode == 0

    /**
     * Requests a graceful end of the session and then observes the host.
     * Returns true only when the session is verified gone.
     */
    fun killAndVerify(name: String, attempts: Int = 3): Boolean {
        ssh.exec(TmuxCommands.killSession(name), timeoutMs = TIMEOUT_MS)
        repeat(attempts) { attempt ->
            if (!hasSession(name)) return true
            if (attempt < attempts - 1) Thread.sleep(250)
        }
        return false
    }

    fun detachClients(name: String): ExecResult =
        ssh.exec(TmuxCommands.detachClients(name), timeoutMs = TIMEOUT_MS)

    /** Opens the interactive attachment. The returned PTY is owned by the terminal tab. */
    fun attach(
        name: String,
        term: String,
        cols: Int,
        rows: Int,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): dev.anthracite.remote.SshPty =
        ssh.openPtyCommand(TmuxCommands.attachCommand(name), term, cols, rows, widthPx, heightPx)

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
