package dev.anthracite.remote.terminal

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
