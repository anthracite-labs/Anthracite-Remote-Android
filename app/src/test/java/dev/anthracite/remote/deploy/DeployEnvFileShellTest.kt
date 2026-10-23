package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the **generated** environment-file command in a disposable home, under
 * every POSIX shell the host offers.
 *
 * P0-1 was a shell defect that no string assertion would have caught: the command
 * compared a ten-character `ls -ld` mode with an eleven-character pattern, so a
 * correctly private `0700` directory was always rejected and no container with
 * environment variables could be created at all. These tests therefore execute the
 * command with a real `sh`/`dash`/`bash`, a real home directory and real files, and
 * look at what happened on disk.
 */
class DeployEnvFileShellTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A test that spawns processes must never be able to hold the runner. Every
     * wait below is bounded, and this is the backstop if one of them is not.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private val secret = "s3cr3t-token-value"

    private data class Outcome(val exit: Int, val stderr: String)

    /** Every distinct POSIX shell available here; at least `/bin/sh` on any Unix. */
    private val shells: List<File> = listOf("/bin/sh", "/usr/bin/dash", "/bin/bash", "/usr/local/bin/bash")
        .map(::File)
        .filter { it.canExecute() }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        .ifEmpty { listOf(File("/bin/sh")) }

    private fun plan(name: String = "anthracite-1a2b3c4d") = DeployPlan.Container(
        name = name,
        runtime = DeployCommands.Runtime.DOCKER,
        image = "nginx:1.27",
        environment = listOf(EnvVar("TOKEN", secret)),
    )

    private fun home(name: String = "home"): File = temp.newFolder(name)

    /**
     * A scratch file outside `TemporaryFolder`, which is not safe to use from two
     * threads at once.
     */
    private fun scratch(suffix: String): File =
        File.createTempFile("anthracite-test", suffix).apply { deleteOnExit() }

    private fun finish(process: Process, what: String): Int {
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$what did not finish")
        }
        return process.exitValue()
    }

    /** Runs a setup tool and fails loudly, with its output, if it did not succeed. */
    private fun tool(vararg arguments: String) {
        val out = scratch(".out")
        val err = scratch(".err")
        val process = ProcessBuilder(*arguments)
            .redirectOutput(out)
            .redirectError(err)
            .start()
        val exit = finish(process, arguments.joinToString(" "))
        assertEquals("${arguments.joinToString(" ")}: ${err.readText()}", 0, exit)
    }

    /** The exact path the generated command writes to, with `$HOME` resolved. */
    private fun targetPath(plan: DeployPlan.Container, home: File): File =
        File(home, ".anthracite/deploy/${plan.name}-${plan.envToken}/env")

    private fun deployDir(home: File) = File(home, ".anthracite/deploy")

    /**
     * Executes [command] with [home] as `HOME`, feeding [stdin] as the payload.
     * Output goes to files so a chatty shell cannot deadlock the test.
     */
    private fun run(
        shell: File,
        command: String,
        home: File,
        stdin: ByteArray? = null,
        prefix: String = "",
    ): Outcome {
        val out = scratch(".out")
        val err = scratch(".err")
        val process = ProcessBuilder(shell.path, "-c", prefix + command)
            .redirectOutput(out)
            .redirectError(err)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        process.outputStream.use { stream ->
            // A refusal exits before it reads anything, so the write can hit a
            // closed pipe. That is expected here, not a failure of the test.
            runCatching { if (stdin != null) stream.write(stdin) }
        }
        val exit = finish(process, "the shell")
        return Outcome(exit, err.readText())
    }

    /**
     * Injects [body] at the validation→claim boundary of the generated command.
     *
     * The command marks that boundary with `set -C;`: the destination has just
     * been validated and the very next step claims it. Running a competing
     * process there makes the race deterministic — no timing loop, no reliance on
     * luck — while every line of the logic under test still executes unchanged.
     */
    private fun withCompetitor(command: String, body: String): String {
        val marker = "set -C;"
        val at = command.indexOf(marker)
        require(at >= 0) { "the generated command no longer marks the claim boundary with set -C;" }
        return command.substring(0, at) + body + "; " + command.substring(at)
    }

    /** A home whose deployment directory is the private one the command expects. */
    private fun preparedHome(name: String): File = home(name).also { root ->
        deployDir(root).mkdirs()
        tool("/bin/chmod", "700", deployDir(root).path)
    }

    // ------------------------------------------------------------------ positive

    @Test
    fun `a private directory accepts the values under every shell`() {
        for (shell in shells) {
            val home = home("ok-${shell.name}")
            val plan = plan()
            val outcome = run(shell, plan.envFileCommand(), home, plan.envFileBytes())

            assertEquals("${shell.path}: ${outcome.stderr}", 0, outcome.exit)
            val file = targetPath(plan, home)
            assertTrue("${shell.path}: the environment file was not created", file.isFile)
            assertEquals(String(plan.envFileBytes()), file.readText())
            assertFalse("${shell.path}: the values must not be readable by others", file.readableByOthers())
            assertEquals("${shell.path}", "rw-------", permissions(file))
            // The claimed directory is private too: it is what makes the cleanup
            // safe, so its mode is part of the guarantee.
            assertEquals("${shell.path}", "rwx------", permissions(file.parentFile))
        }
    }

    @Test
    fun `the directory is created private on a fresh home`() {
        val home = home("fresh")
        val plan = plan()
        assertEquals(0, run(shells.first(), plan.envFileCommand(), home, plan.envFileBytes()).exit)
        assertEquals("rwx------", permissions(deployDir(home)))
    }

    // ------------------------------------------------------------------ negative

    @Test
    fun `a group or world accessible directory is refused`() {
        for (shell in shells) {
            for (mode in listOf("0750", "0755", "0777")) {
                val home = home("mode-${shell.name}-$mode")
                val plan = plan()
                val dir = deployDir(home).apply { mkdirs() }
                tool("/bin/chmod", mode, dir.path)
                val outcome = run(shell, plan.envFileCommand(), home, plan.envFileBytes())

                assertEquals("${shell.path} $mode", DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
                assertTrue("${shell.path} $mode: ${outcome.stderr}", outcome.stderr.contains("must be 0700"))
                assertFalse("${shell.path} $mode wrote a file", targetPath(plan, home).exists())
            }
        }
    }

    @Test
    fun `a symlinked directory is refused and nothing is written through it`() {
        for (shell in shells) {
            val home = home("symlink-${shell.name}")
            val elsewhere = temp.newFolder("elsewhere-${shell.name}")
            File(home, ".anthracite").mkdirs()
            tool("/bin/ln", "-s", elsewhere.path, deployDir(home).path)
            val plan = plan()

            val outcome = run(shell, plan.envFileCommand(), home, plan.envFileBytes())

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
            assertTrue(outcome.stderr.contains("symbolic link"))
            assertEquals("${shell.path}: the link target must stay empty", 0, elsewhere.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun `an existing file is refused and never truncated`() {
        for (shell in shells) {
            val home = home("existing-${shell.name}")
            val plan = plan()
            deployDir(home).apply { mkdirs() }
            tool("/bin/chmod", "700", deployDir(home).path)
            val file = targetPath(plan, home).apply {
                parentFile.mkdirs()
                writeText("OLD=1\n")
            }

            val outcome = run(shell, plan.envFileCommand(), home, plan.envFileBytes())

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
            assertEquals("${shell.path}: a foreign file was modified", "OLD=1\n", file.readText())
        }
    }

    @Test
    fun `a symlink at the file path is refused and its target is untouched`() {
        for (shell in shells) {
            val home = home("links-${shell.name}")
            val plan = plan()
            deployDir(home).apply { mkdirs() }
            tool("/bin/chmod", "700", deployDir(home).path)
            val victim = File(home, "victim.txt").apply { writeText("SECRET-VICTIM\n") }
            targetPath(plan, home).parentFile.mkdirs()
            tool("/bin/ln", "-s", victim.path, targetPath(plan, home).path)

            val outcome = run(shell, plan.envFileCommand(), home, plan.envFileBytes())

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
            assertEquals("${shell.path}: the link target was written", "SECRET-VICTIM\n", victim.readText())
        }
    }

    @Test
    fun `a write that fails half-way leaves nothing behind`() {
        // `ulimit -f` makes the kernel fail the write after the file exists, which
        // is the case where a partial secret file would otherwise survive.
        val payload = (0 until 400).joinToString("") { "KEY$it=value$it\n" }.toByteArray()
        for (shell in shells) {
            val home = home("partial-${shell.name}")
            val plan = plan()
            deployDir(home).apply { mkdirs() }
            tool("/bin/chmod", "700", deployDir(home).path)

            val outcome = run(shell, plan.envFileCommand(), home, payload, prefix = "ulimit -f 1; ")

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_ENV_WRITE_FAILED, outcome.exit)
            assertFalse(
                "${shell.path}: a partial environment file survived",
                targetPath(plan, home).exists(),
            )
        }
    }

    // ------------------------------------------------------------------- the race

    @Test
    fun `a file created between validation and the claim is never deleted`() {
        for (shell in shells) {
            val root = preparedHome("race-${shell.name}")
            val plan = plan()
            val target = targetPath(plan, root)
            // The competing process wins the window and writes its own file.
            val command = withCompetitor(
                plan.envFileCommand(),
                "mkdir \"\$p\"; printf 'FOREIGN=1\n' > \"\$f\"; chmod 644 \"\$f\"",
            )

            val outcome = run(shell, command, root, plan.envFileBytes())

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
            assertTrue("${shell.path}: the competing file was deleted", target.isFile)
            assertEquals("${shell.path}: the competing file was modified", "FOREIGN=1\n", target.readText())
            assertEquals("${shell.path}: the competing file's mode was changed", "rw-r--r--", permissions(target))
            // It is a refusal to claim, not a failed write: the two are different
            // conditions and must stay distinguishable.
            assertTrue("${shell.path}: ${outcome.stderr}", outcome.stderr.contains("left untouched"))
            assertFalse("${shell.path}: reported as a write failure", outcome.stderr.contains("could not be written"))
            assertFalse("${shell.path}: the secret reached stderr", outcome.stderr.contains(secret))
        }
    }

    @Test
    fun `a claim lost to a competitor leaves the filesystem otherwise untouched`() {
        val shell = shells.first()
        val root = preparedHome("race-untouched")
        val plan = plan()
        val directory = deployDir(root)
        val before = directory.listFiles()!!.map { it.name }.toSet()
        val command = withCompetitor(plan.envFileCommand(), "mkdir \"\$p\"; printf 'FOREIGN=1\n' > \"\$f\"")

        assertEquals(DeployPlans.EXIT_INSECURE_PATH, run(shell, command, root, plan.envFileBytes()).exit)

        assertEquals(
            "a stray file was left behind",
            before + "anthracite-1a2b3c4d-${plan.envToken}",
            directory.listFiles()!!.map { it.name }.toSet(),
        )
        assertEquals("the secret was written anyway", "FOREIGN=1\n", targetPath(plan, root).readText())
    }

    @Test
    fun `a partial write after a won claim removes only its own file`() {
        val payload = (0 until 400).joinToString("") { "KEY$it=value$it\n" }.toByteArray()
        for (shell in shells) {
            val root = preparedHome("partial-owned-${shell.name}")
            val plan = plan()

            val outcome = run(shell, plan.envFileCommand(), root, payload, prefix = "ulimit -f 1; ")

            assertEquals("${shell.path}: ${outcome.stderr}", DeployPlans.EXIT_ENV_WRITE_FAILED, outcome.exit)
            assertFalse("${shell.path}: a partial secret survived", targetPath(plan, root).exists())
            assertEquals("${shell.path}: the directory was left dirty", 0, deployDir(root).listFiles()!!.size)
        }
    }

    @Test
    fun `an operation killed mid-write never removes another operation's file`() {
        val shell = shells.first()
        val root = preparedHome("cancelled")
        val cancelled = plan("anthracite-cccc3333")
        val other = plan("anthracite-dddd4444")
        // A second operation has already claimed and written its own file.
        val otherFile = targetPath(other, root).apply {
            parentFile.mkdirs()
            writeText("OTHER=1\n")
        }

        // Start the operation, wait until it has claimed its file, then kill it
        // while it is still reading the secret from stdin.
        val process = ProcessBuilder(shell.path, "-c", cancelled.envFileCommand())
            .redirectOutput(scratch(".out"))
            .redirectError(scratch(".err"))
            .apply { environment()["HOME"] = root.absolutePath }
            .start()
        val claimed = targetPath(cancelled, root)
        val deadline = System.currentTimeMillis() + 30_000
        while (!claimed.exists() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("the operation never claimed its file", claimed.exists())
        process.destroyForcibly()
        finish(process, "the killed operation")

        assertTrue("the other operation's file was removed", otherFile.isFile)
        assertEquals("OTHER=1\n", otherFile.readText())
        // Its own leftover is its own to remove, by its own random name.
        tool(shell.path, "-c", "HOME=${root.path}; export HOME; ${cancelled.envFileRemoveCommand()}")
        assertFalse(claimed.exists())
        assertTrue("cleanup of one operation removed another's file", otherFile.isFile)
    }

    @Test
    fun `a stale partial file is never deleted by a different operation`() {
        val shell = shells.first()
        val root = preparedHome("stale")
        val stale = plan("anthracite-eeee5555")
        val fresh = plan("anthracite-ffff6666")
        val staleFile = targetPath(stale, root).apply {
            parentFile.mkdirs()
            writeText("TOKEN=leftover")
        }

        // The fresh operation has its own name, so it neither claims nor cleans
        // the leftover: only the operation that owns it may remove it.
        val outcome = run(shell, fresh.envFileCommand(), root, fresh.envFileBytes())

        assertEquals(outcome.stderr, 0, outcome.exit)
        assertTrue("a different operation deleted a leftover it does not own", staleFile.isFile)
        assertEquals("TOKEN=leftover", staleFile.readText())
        assertTrue(targetPath(fresh, root).isFile)
    }

    @Test
    fun `a directory owned by another user is refused`() {
        // Only a privileged test process can create the condition. Reporting the
        // skip is honest; pretending to have tested ownership would not be.
        val idOut = scratch(".out")
        val id = ProcessBuilder("/usr/bin/id", "-u").redirectOutput(idOut).start()
        finish(id, "id")
        val uid = idOut.readText().trim()
        assumeTrue("ownership can only be forged as root (running as uid $uid)", uid == "0")

        val home = home("owner")
        val plan = plan()
        deployDir(home).apply { mkdirs() }
        tool("/bin/chown", "65534", deployDir(home).path)

        val outcome = run(shells.first(), plan.envFileCommand(), home, plan.envFileBytes())
        assertEquals(DeployPlans.EXIT_INSECURE_PATH, outcome.exit)
        assertTrue(outcome.stderr.contains("owned by another user"))
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `two plans writing at the same time cannot collide or delete each other`() {
        val shell = shells.first()
        val home = home("concurrent")
        deployDir(home).apply { mkdirs() }
        tool("/bin/chmod", "700", deployDir(home).path)
        val first = plan("anthracite-aaaa1111")
        val second = plan("anthracite-bbbb2222")
        val start = CountDownLatch(1)
        val outcomes = arrayOfNulls<Outcome>(2)
        val workers = listOf(first, second).mapIndexed { index, plan ->
            thread {
                start.await()
                outcomes[index] = run(shell, plan.envFileCommand(), home, plan.envFileBytes())
            }
        }
        start.countDown()
        workers.forEach { it.join(60_000) }

        assertEquals(0, outcomes[0]?.exit)
        assertEquals(0, outcomes[1]?.exit)
        for (plan in listOf(first, second)) {
            val file = targetPath(plan, home)
            assertTrue("${plan.name} did not write its own file", file.isFile)
            assertEquals(String(plan.envFileBytes()), file.readText())
        }
        // Cleanup of one plan must not touch the other's file.
        val removeOut = scratch(".out")
        val remove = ProcessBuilder(shell.path, "-c", first.envFileRemoveCommand())
            .redirectOutput(removeOut)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        assertEquals(removeOut.readText(), 0, finish(remove, "the removal"))
        assertFalse(targetPath(first, home).exists())
        assertTrue("removing one plan's file removed the other's", targetPath(second, home).isFile)
    }

    // -------------------------------------------------------------------- hygiene

    @Test
    fun `no command carries the secret`() {
        val plan = plan()
        for (command in listOf(
            plan.envFileCommand(),
            plan.runCommand(),
            plan.envFileRemoveCommand(),
            plan.commands().joinToString("\n"),
        )) {
            assertFalse("a command carried the value", command.contains(secret))
        }
        assertEquals("TOKEN=$secret\n", String(plan.envFileBytes()))
    }

    @Test
    fun `every refusal status is distinct from success`() {
        assertTrue(DeployPlans.EXIT_INSECURE_PATH != 0)
        assertTrue(DeployPlans.EXIT_ENV_WRITE_FAILED != 0)
        assertTrue(DeployPlans.EXIT_INSECURE_PATH != DeployPlans.EXIT_ENV_WRITE_FAILED)
    }

    private fun permissions(file: File): String {
        val out = scratch(".out")
        val process = ProcessBuilder(
            "/bin/sh", "-c", "stat -c %A \"\$1\" 2>/dev/null || stat -f %Sp \"\$1\"", "sh", file.path,
        )
            .redirectOutput(out)
            .redirectErrorStream(true)
            .start()
        finish(process, "stat")
        return out.readText().trim().removePrefix("d").removePrefix("-")
    }

    /** True when anyone other than the owner may read the file. */
    private fun File.readableByOthers(): Boolean {
        val out = scratch(".out")
        val stat = ProcessBuilder(
            "/bin/sh", "-c", "stat -c %a \"\$1\" 2>/dev/null || stat -f %Lp \"\$1\"", "sh", path,
        )
            .redirectOutput(out)
            .redirectErrorStream(true)
            .start()
        finish(stat, "stat")
        val mode = out.readText().trim()
        val other = mode.lastOrNull()?.digitToIntOrNull() ?: return true
        return other and 4 != 0
    }
}
