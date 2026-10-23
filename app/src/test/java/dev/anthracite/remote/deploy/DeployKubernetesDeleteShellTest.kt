package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes the generated conditional delete with a stand-in `kubectl`.
 *
 * The point of `--raw -f -` is that the cluster — not a shell comparison — decides
 * whether the object is still the one that was reviewed. That only holds if the
 * document reaches `kubectl` exactly as built, with the UID inside it and no shell
 * expanding anything. A string assertion cannot show that, so the command runs
 * here under a real `sh` with a `kubectl` that records its arguments and stdin.
 */
class DeployKubernetesDeleteShellTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val uid = "0f8f8d5e-1c2b-4a3c-9d7e-2b6f5a4c3d21"

    private fun plan(
        kind: WorkloadKind = WorkloadKind.DEPLOYMENT,
        namespace: String = "demo",
    ) = DeployPlan.Workload(
        name = "anthracite-1a2b3c4d",
        context = "prod-cluster",
        namespace = namespace,
        kind = kind,
        image = "nginx:1.27",
    )

    private class Stub(val bin: File, val argv: File, val stdin: File)

    /** A `kubectl` that records what it was given and exits with [exitCode]. */
    private fun stubKubectl(exitCode: Int = 0): Stub {
        val bin = temp.newFolder("bin")
        val argv = File(temp.root, "argv")
        val stdin = File(temp.root, "stdin")
        File(bin, "kubectl").apply {
            writeText(
                """
                #!/bin/sh
                for argument in "${'$'}@"; do echo "${'$'}argument" >> "${argv.path}"; done
                cat > "${stdin.path}"
                exit $exitCode
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        return Stub(bin, argv, stdin)
    }

    private fun run(command: String, bin: File): Int {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .apply { environment()["PATH"] = "${bin.path}:" + environment()["PATH"] }
            .redirectErrorStream(true)
            .start()
        // Drain before waiting, so the command can never block on a full pipe.
        process.inputStream.bufferedReader().readText()
        assertTrue("kubectl was never reached in time", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    @Test
    fun `the delete carries the reviewed uid in the document the cluster reads`() {
        val stub = stubKubectl()

        assertEquals(0, run(plan().conditionalDeleteCommand(uid), stub.bin))

        val argv = stub.argv.readLines()
        assertEquals(
            listOf(
                "--context",
                "prod-cluster",
                "delete",
                "--raw=/apis/apps/v1/namespaces/demo/deployments/anthracite-1a2b3c4d",
                "-f",
                "-",
            ),
            argv,
        )
        assertEquals(
            "{\"apiVersion\":\"v1\",\"kind\":\"DeleteOptions\"," +
                "\"preconditions\":{\"uid\":\"$uid\"},\"propagationPolicy\":\"Background\"}",
            stub.stdin.readText(),
        )
        // No shell word splitting, no left-over quoting, and no unconditional form.
        assertFalse(argv.any { it.contains("'") })
        assertFalse(argv.any { it.contains("--ignore-not-found") })
    }

    @Test
    fun `a statefulset is deleted through its own path`() {
        val stub = stubKubectl()

        assertEquals(0, run(plan(kind = WorkloadKind.STATEFULSET).conditionalDeleteCommand(uid), stub.bin))
        assertTrue(
            stub.argv.readLines().contains(
                "--raw=/apis/apps/v1/namespaces/demo/statefulsets/anthracite-1a2b3c4d",
            ),
        )
    }

    @Test
    fun `a refused delete is reported, not swallowed`() {
        // The API server answering 409 (the object was replaced) must reach the app
        // as a failure rather than a successful cleanup.
        val stub = stubKubectl(exitCode = 1)

        assertEquals(1, run(plan().conditionalDeleteCommand(uid), stub.bin))
    }

    @Test
    fun `the raw request is only built from a well formed uid`() {
        for (bad in listOf("", "not-a-uid", "0f8f8d5e-1c2b-4a3c-9d7e-2b6f5a4c3d2", "0f8f8d5e; id")) {
            val refused = try {
                plan().conditionalDeleteCommand(bad)
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
            assertTrue("uid '$bad' should have been refused", refused)
        }
    }
}
