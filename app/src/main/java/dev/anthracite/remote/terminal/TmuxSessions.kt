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

    fun attachId(id: String): String = "exec tmux attach-session -t ${quoteId(id)}"

    fun detachClients(name: String): String = "tmux detach-client -s ${quoteName(name)}"

    fun killSession(name: String): String = "tmux kill-session -t ${quoteName(name)}"

    // tmux's host-issued session id stays unique when a name is reused. Never
    // kill a new session which acquired the old name after the user's selection.
    fun killSessionId(id: String): String = "tmux kill-session -t ${quoteId(id)}"

    private fun quoteId(id: String): String {
        require(id.matches(Regex("\\$[0-9]+"))) { "invalid tmux session id" }
        return PosixShell.quote(id)
    }

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

/**
 * Reads `tmux 3.4` from `tmux -V`.
 *
 * The remainder must contain a digit: a line that is only the word `tmux`
 * carries no version, and reporting it as one would put "tmux" in a capability
 * check that compares versions. A build from git reports `tmux next-3.5`, which
 * has a digit and is accepted.
 */
internal fun parseTmuxVersion(stdout: String): String? {
    val line = stdout.lineSequence().firstOrNull { it.startsWith("tmux ") }?.trim() ?: return null
    val version = line.removePrefix("tmux ").take(32)
    return version.takeIf { candidate -> candidate.any { it.isDigit() } }
}

/** Verification is distinct from a failed SSH command or unparseable host output. */
internal enum class TmuxPresence { PRESENT, ABSENT, UNKNOWN }

internal fun tmuxPresence(result: ExecResult, name: String, id: String): TmuxPresence {
    if (result.exitCode != 0) {
        // tmux reports this when its server has no sessions at all. An arbitrary
        // nonzero exit (including permission or transport failure) is unknown.
        return if (result.exitCode == 1 &&
            (result.stderr.trim().startsWith("no server running on ") ||
                result.stderr.trim() == "no sessions")) TmuxPresence.ABSENT else TmuxPresence.UNKNOWN
    }
    val (sessions, unparsed) = parseTmuxSessions(result.stdout)
    if (unparsed != 0) return TmuxPresence.UNKNOWN
    if (sessions.any { it.name == name && it.id == id }) return TmuxPresence.PRESENT
    // A replacement under the same name is not evidence that the selected
    // session ended as intended; tell the user verification was inconclusive.
    if (sessions.any { it.name == name || it.id == id }) return TmuxPresence.UNKNOWN
    return TmuxPresence.ABSENT
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

    /** A successful listing establishes identity; an error can never mean absent. */
    fun sessionState(name: String, id: String): TmuxPresence =
        tmuxPresence(ssh.exec(TmuxCommands.LIST, timeoutMs = TIMEOUT_MS), name, id)

    /**
     * Fail closed unless the exact selected host-issued id still belongs to the
     * selected name. Kill by id, not by a name which another client may reuse.
     */
    fun killAndVerify(name: String, id: String, attempts: Int = 3): Boolean {
        require(attempts > 0)
        TmuxCommands.quoteName(name)
        TmuxCommands.killSessionId(id)
        if (sessionState(name, id) != TmuxPresence.PRESENT) return false
        val killed = ssh.exec(TmuxCommands.killSessionId(id), timeoutMs = TIMEOUT_MS)
        if (killed.exitCode != 0) return false
        repeat(attempts) { attempt ->
            when (sessionState(name, id)) {
                TmuxPresence.ABSENT -> return true
                TmuxPresence.UNKNOWN -> return false
                TmuxPresence.PRESENT -> if (attempt < attempts - 1) Thread.sleep(250)
            }
        }
        return false
    }

    fun detachClients(name: String): ExecResult =
        ssh.exec(TmuxCommands.detachClients(name), timeoutMs = TIMEOUT_MS)

    /** Opens the interactive attachment. The returned PTY is owned by the terminal tab. */
    fun attach(
        id: String,
        term: String,
        cols: Int,
        rows: Int,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): dev.anthracite.remote.SshPty =
        ssh.openPtyCommand(TmuxCommands.attachId(id), term, cols, rows, widthPx, heightPx)

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
