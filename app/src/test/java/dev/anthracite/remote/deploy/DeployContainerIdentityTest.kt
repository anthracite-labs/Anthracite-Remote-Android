package dev.anthracite.remote.deploy

import dev.anthracite.remote.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Container identity, and the private environment file.
 *
 * A container is remembered by the id the runtime assigned, not by its name: a
 * name can be reused the moment the first container is gone. The environment file
 * is the other half — it holds secrets, so it is created exclusively in a
 * directory checked to be private, and it is removed on every path.
 */
class DeployContainerIdentityTest {
    private val containerId = "3f2a1b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708"
    private val otherId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private class FakeShell(private val respond: (String) -> ExecResult) : RemoteShell {
        val commands = mutableListOf<String>()
        val stdins = mutableListOf<ByteArray?>()

        override fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
            commands += command
            stdins += stdin
            return respond(command)
        }
    }

    private fun plan(createdId: String? = null) = DeployPlan.Container(
        name = "anthracite-1a2b3c4d",
        runtime = DeployCommands.Runtime.DOCKER,
        image = "nginx:1.27",
        environment = listOf(EnvVar("TOKEN", "s3cr3t")),
        createdId = createdId,
    )

    private fun inspectLine(id: String, label: String?) = "$id|${label.orEmpty()}\n"

    // ------------------------------------------------------------------ removal

    @Test
    fun `removal names the recorded id, not the name`() {
        val shell = FakeShell {
            if (it.contains("inspect")) ExecResult(0, inspectLine(containerId, "1"), "") else ExecResult(0, "", "")
        }
        val result = DeployCreator(shell).removeOwnedContainer(plan(containerId))

        assertEquals(0, result?.exitCode)
        assertTrue(shell.commands.last().endsWith("docker rm --force $containerId"))
        assertFalse(shell.commands.last().contains("anthracite-1a2b3c4d"))
    }

    @Test
    fun `a name now owned by a different container is left alone`() {
        val shell = FakeShell {
            if (it.contains("inspect")) ExecResult(0, inspectLine(otherId, "1"), "") else ExecResult(0, "", "")
        }
        assertNull(DeployCreator(shell).removeOwnedContainer(plan(containerId)))
        assertFalse(shell.commands.any { it.contains("rm --force") })
    }

    @Test
    fun `a container without our label is left alone`() {
        val shell = FakeShell {
            if (it.contains("inspect")) ExecResult(0, inspectLine(containerId, null), "") else ExecResult(0, "", "")
        }
        assertNull(DeployCreator(shell).removeOwnedContainer(plan(containerId)))
        assertFalse(shell.commands.any { it.contains("rm --force") })
    }

    @Test
    fun `a plan with no recorded id is never removed`() {
        val shell = FakeShell { ExecResult(0, inspectLine(containerId, "1"), "") }
        assertNull(DeployCreator(shell).removeOwnedContainer(plan(null)))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `an unreadable inspect refuses`() {
        for (answer in listOf("", "garbage", "not-an-id|1", "$containerId")) {
            val shell = FakeShell { ExecResult(0, answer, "") }
            assertNull("inspect '$answer'", DeployCreator(shell).removeOwnedContainer(plan(containerId)))
            assertFalse(shell.commands.any { it.contains("rm --force") })
        }
    }

    // --------------------------------------------------------------- creation id

    @Test
    fun `creation records the id the runtime reports`() {
        val shell = FakeShell {
            if (it.contains("inspect")) ExecResult(0, inspectLine(containerId, "1"), "") else ExecResult(0, "", "")
        }
        val outcome = DeployCreator(shell).containerRun(plan())
        assertEquals(0, outcome.result.exitCode)
        assertEquals(containerId, outcome.identity)
    }

    // ------------------------------------------------------- environment file

    @Test
    fun `the environment file is created exclusively in a private directory`() {
        val command = plan().envFileCommand()
        // A symlinked or world-accessible directory is refused before writing.
        assertTrue(command.contains("[ -L \"\$d\" ]"))
        assertTrue(command.contains("d????------"))
        assertTrue(command.contains("exit ${DeployPlans.EXIT_INSECURE_PATH}"))
        // noclobber: an existing file or a symlink is an error, not a truncation.
        assertTrue(command.endsWith("set -C; cat > \"\$f\""))
        assertTrue(command.startsWith("umask 077; "))
    }

    @Test
    fun `the file name carries a per-plan token`() {
        val first = plan()
        val second = plan()
        assertTrue(first.envFilePath().contains(first.envToken))
        assertTrue(second.envFilePath().contains(second.envToken))
        assertTrue(first.envFilePath() != second.envFilePath())
        assertTrue(Regex("^[0-9a-f]{16}$").matches(first.envToken))
    }

    @Test
    fun `the file is removed even when the write fails`() {
        val shell = FakeShell {
            when {
                it == plan().envFileCommand() -> ExecResult(DeployPlans.EXIT_INSECURE_PATH, "", "refused")
                it.startsWith("rm -f") -> ExecResult(0, "", "")
                else -> ExecResult(0, "", "")
            }
        }
        val outcome = DeployCreator(shell).containerRun(plan())

        assertEquals(DeployPlans.EXIT_INSECURE_PATH, outcome.result.exitCode)
        assertNull(outcome.identity)
        assertTrue(shell.commands.any { it.startsWith("rm -f") })
        assertFalse(shell.commands.any { it.contains("docker run") })
    }

    @Test
    fun `the file is removed when the container starts`() {
        val shell = FakeShell {
            when {
                it.contains("inspect") -> ExecResult(0, inspectLine(containerId, "1"), "")
                else -> ExecResult(0, "", "")
            }
        }
        DeployCreator(shell).containerRun(plan())
        val order = shell.commands
        val write = order.indexOfFirst { it.contains("cat >") }
        val run = order.indexOfFirst { it.contains("docker run") }
        val remove = order.indexOfFirst { it.startsWith("rm -f") }
        assertTrue(write in 0 until run)
        assertTrue(run < remove)
        // The secret only ever travels on stdin.
        assertTrue(String(shell.stdins[write]!!).contains("TOKEN=s3cr3t"))
        assertFalse(order.any { it.contains("s3cr3t") })
    }
}
