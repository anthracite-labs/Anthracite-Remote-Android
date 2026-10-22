package dev.anthracite.remote.terminal

import dev.anthracite.remote.ExecResult
import dev.anthracite.remote.host.PosixShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tmux identity is the difference between "detached" and "stopped", so both the
 * parser and the name grammar are pinned here.
 */
class TmuxParsingTest {
    @Test
    fun `list-sessions rows parse into sessions and count unparseable rows`() {
        val stdout = listOf(
            "work\t$1\t1700000000\t0\t3",
            "build\t$2\t1700000800\t2\t1",
            "malformed row",
            "empty-name\t$3\tnot-a-time\t0\t1",
            "",
        ).joinToString("\n")

        val (sessions, unparsed) = parseTmuxSessions(stdout)

        assertEquals(2, sessions.size)
        assertEquals(2, unparsed)
        assertEquals("work", sessions[0].name)
        assertEquals(1700000000L, sessions[0].createdAtSeconds)
        assertEquals(0, sessions[0].attachedClients)
        assertEquals(3, sessions[0].windows)
        assertEquals("build", sessions[1].name)
        assertEquals(2, sessions[1].attachedClients)
    }

    @Test
    fun `an empty listing is not an error and produces no rows`() {
        val (sessions, unparsed) = parseTmuxSessions("")
        assertTrue(sessions.isEmpty())
        assertEquals(0, unparsed)
    }

    @Test
    fun `version parsing tolerates extra output and rejects noise`() {
        assertEquals("3.4", parseTmuxVersion("tmux 3.4\n"))
        assertEquals("3.4a", parseTmuxVersion("\nwarning: something\ntmux 3.4a\n"))
        assertEquals("next-3.5", parseTmuxVersion("tmux next-3.5"))
        assertNull(parseTmuxVersion("command not found"))
        // The bare word carries no version and must not be reported as one.
        assertNull(parseTmuxVersion("tmux "))
        assertNull(parseTmuxVersion("tmux"))
    }

    @Test
    fun `discovery distinguishes missing binary empty server and unknown errors`() {
        assertEquals(null, tmuxVersion(ExecResult(127, "ANTHRACITE_TMUX_NOT_INSTALLED\n", "")))
        assertEquals("3.4", tmuxVersion(ExecResult(0, "tmux 3.4\n", "")))
        for (failure in listOf(
            ExecResult(127, "", "permission denied"), ExecResult(255, "", "connection lost"),
            ExecResult(1, "", "permission denied"), ExecResult(0, "garbage", ""),
        )) assertThrows(IllegalStateException::class.java) { tmuxVersion(failure) }
        assertTrue(tmuxNoSessions(ExecResult(1, "", "no sessions\n")))
        assertTrue(tmuxListing("3.4", ExecResult(1, "", "no sessions\n")).sessions.isEmpty())
        assertEquals("work", tmuxListing("3.4", ExecResult(0, "work\t\$1\t1700000000\t0\t1\n", ""))
            .sessions.single().name)
        assertThrows(IllegalStateException::class.java) {
            tmuxListing("3.4", ExecResult(0, "work\tbad-id\t1700000000\t0\t1\n", ""))
        }
        for (failure in listOf(
            ExecResult(255, "", "SSH disconnected"), ExecResult(1, "", "permission denied"),
            ExecResult(2, "", "no sessions"), ExecResult(0, "garbled", ""),
            ExecResult(0, "", "permission denied"),
        )) assertThrows(IllegalStateException::class.java) { tmuxListing("3.4", failure) }
        assertTrue(tmuxNoSessions(ExecResult(1, "", "no server running on /tmp/tmux-1/default\n")))
        // tmux prints this before its first server has created a socket.
        val firstSession = ExecResult(1, "", "error connecting to /tmp/tmux-1000/default (No such file or directory)\n")
        assertTrue(tmuxMissingSocket(firstSession))
        assertTrue(tmuxListing("3.4", firstSession).sessions.isEmpty())
        // A deleted socket can strand a live server; absence is not proven for STOP.
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(firstSession, "work", "\$1"))
        for (failure in listOf(
            ExecResult(1, "", "error connecting to /tmp/tmux-1000/default (Permission denied)"),
            ExecResult(1, "", "error connecting to /tmp/tmux-1000/default (Connection refused)"),
            ExecResult(255, "", "error connecting to /tmp/tmux-1000/default (No such file or directory)"),
            ExecResult(1, "", "error connecting to /tmp/tmux-1000/default (No such file or directory)\npermission denied"),
        )) {
            assertTrue(!tmuxMissingSocket(failure))
            assertThrows(IllegalStateException::class.java) { tmuxListing("3.4", failure) }
        }
        for (failure in listOf(
            ExecResult(255, "", "SSH disconnected"), ExecResult(1, "", "permission denied"),
            ExecResult(2, "", "no sessions"), ExecResult(1, "", "no sessions\npermission denied"),
            ExecResult(1, "", "no server running on /tmp/socket permission denied"),
            ExecResult(1, "work\t\$1\t1\t0\t1", "no sessions"),
        )) assertTrue("unexpected absence: $failure", !tmuxNoSessions(failure))
    }

    @Test
    fun `session names cannot become flags, separators or shell syntax`() {
        // A plain name stays bare; quoting only appears when the shell needs it.
        assertEquals(PosixShell.quote("work"), TmuxCommands.quoteName("work"))
        for (bad in listOf("", "-rf", "a:b", "a.b", "a b", "a;rm -rf /", "a/b", "a\\b", "a'b")) {
            assertThrows("name '$bad' should be refused", IllegalArgumentException::class.java) {
                TmuxCommands.quoteName(bad)
            }
        }
    }

    @Test
    fun `only a successful exact identity listing proves presence or absence`() {
        val id = "\$1"
        val listed = ExecResult(0, "work\t$id\t1700000000\t0\t1\n", "")
        assertEquals(TmuxPresence.PRESENT, tmuxPresence(listed, "work", id))
        assertEquals(TmuxPresence.ABSENT, tmuxPresence(ExecResult(0, "", ""), "work", id))
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(ExecResult(255, "", "SSH disconnected"), "work", id))
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(ExecResult(1, "", "permission denied"), "work", id))
        assertEquals(TmuxPresence.ABSENT, tmuxPresence(ExecResult(1, "", "no server running on /tmp/tmux.sock"), "work", id))
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(ExecResult(0, "garbled", ""), "work", id))
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(listed, "work", "\$2"))
        assertEquals(TmuxPresence.UNKNOWN, tmuxPresence(listed, "other", id))
        assertEquals("tmux kill-session -t '\$1'", TmuxCommands.killSessionId(id))
        assertEquals("exec tmux attach-session -t '\$1'", TmuxCommands.attachId(id))
        assertThrows(IllegalArgumentException::class.java) { TmuxCommands.killSessionId("work") }
    }

    @Test
    fun `create is idempotent and quote the working directory once`() {
        val command = TmuxCommands.createDetached("work", "/srv/my project")
        assertTrue("unexpected command: $command", command.startsWith("tmux new-session -A -d -s work"))
        assertTrue("unexpected command: $command", command.contains("-c '/srv/my project'"))

        assertEquals("tmux new-session -A -d -s work", TmuxCommands.createDetached("work", null))

        // An invalid directory is dropped rather than quoted into the command.
        assertEquals("tmux new-session -A -d -s work", TmuxCommands.createDetached("work", "bad\npath"))
    }

    @Test
    fun `attach, detach and kill address exactly one validated session`() {
        assertEquals("exec tmux attach-session -t work", TmuxCommands.attachCommand("work"))
        assertEquals("tmux detach-client -s work", TmuxCommands.detachClients("work"))
        assertEquals("tmux kill-session -t work", TmuxCommands.killSession("work"))
        assertEquals("tmux has-session -t work", TmuxCommands.hasSession("work"))
        assertTrue(PosixShell.isToolName("work-2_alpha"))
    }
}
