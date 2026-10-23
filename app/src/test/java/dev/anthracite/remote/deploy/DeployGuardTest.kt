package dev.anthracite.remote.deploy

import dev.anthracite.remote.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Destructive Kubernetes actions must be decided by what the host reports *now*:
 * the active context, the caller's authority for the exact verb, and the live
 * object's label and UID. Anything unreadable refuses, and nothing is mutated
 * unless every check agrees.
 *
 * The shell is faked so the tests see the real command sequence, including the
 * commands that must never be sent.
 */
class DeployGuardTest {
    private val uid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val otherUid = "11111111-2222-3333-4444-555555555555"

    private class FakeShell(private val respond: (String) -> ExecResult) : RemoteShell {
        val commands = mutableListOf<String>()
        val stdins = mutableListOf<ByteArray?>()

        override fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
            commands += command
            stdins += stdin
            return respond(command)
        }
    }

    private fun plan(uid: String? = this.uid) = DeployPlan.Workload(
        name = "anthracite-1a2b3c4d",
        context = "prod-cluster",
        namespace = "demo",
        kind = WorkloadKind.DEPLOYMENT,
        image = "nginx:1.27",
        appliedUid = uid,
    )

    /** The cluster reports `uid` and (optionally) the ownership label. */
    private fun objectJson(uid: String, label: String? = "1"): String = buildString {
        append("{\"metadata\":{\"uid\":\"").append(uid).append("\"")
        append(",\"labels\":{\"anthracite.managed\":\"").append(label.orEmpty()).append("\"}}}")
    }

    /** The host answers as if everything is as reviewed. */
    private fun healthy(
        current: String = "prod-cluster",
        canI: String = "yes",
        identity: String? = objectJson(uid),
        identityExit: Int = if (identity == null) 1 else 0,
    ): FakeShell = FakeShell { command ->
        when {
            command.endsWith("config current-context") -> ExecResult(0, current, "")
            command.contains("auth can-i") -> ExecResult(0, canI, "")
            command.contains(" -o json") ->
                ExecResult(identityExit, identity.orEmpty(), if (identityExit == 0) "" else "NotFound")
            else -> ExecResult(0, "", "")
        }
    }

    // --------------------------------------------------------------- changed context

    @Test
    fun `a changed context refuses before any permission or identity read`() {
        val shell = healthy(current = "someone-elses-cluster")
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertTrue(result.stderr.contains("context changed"))
        assertEquals(listOf("kubectl config current-context"), shell.commands)
        assertFalse(shell.commands.any { it.contains("delete deployment") })
    }

    @Test
    fun `an unreadable context refuses`() {
        val shell = FakeShell { ExecResult(1, "", "error: no current context") }
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertEquals(1, shell.commands.size)
    }

    // ------------------------------------------------------------------ denied delete

    @Test
    fun `a denied delete is refused and never sent`() {
        val shell = healthy(canI = "no")
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertTrue(result.stderr.contains("does not allow"))
        assertTrue(shell.commands.any { it.contains("auth can-i delete deployment") })
        assertFalse(shell.commands.any { it.contains("delete deployment/") })
    }

    @Test
    fun `an unparseable authorisation answer is a refusal`() {
        for (answer in listOf("", "yes please", "maybe", "error: forbidden")) {
            val shell = healthy(canI = answer)
            val result = DeployCreator(shell).workloadDelete(plan())
            assertEquals("answer '$answer'", 64, result.exitCode)
            assertFalse(shell.commands.any { it.contains("delete deployment/") })
        }
    }

    // --------------------------------------------------------------- replaced resource

    @Test
    fun `an object replaced under the same name is refused`() {
        val shell = healthy(identity = objectJson(otherUid))
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertTrue(result.stderr.contains("replaced"))
        assertFalse(shell.commands.any { it.contains("delete deployment/") })
    }

    @Test
    fun `an object without the ownership label is refused`() {
        val shell = healthy(identity = objectJson(uid, label = null))
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertTrue(result.stderr.contains("not labelled"))
        assertFalse(shell.commands.any { it.contains("delete deployment/") })
    }

    @Test
    fun `an object that is gone is refused rather than deleted`() {
        val shell = healthy(identity = null)
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(64, result.exitCode)
        assertFalse(shell.commands.any { it.contains("delete deployment/") })
    }

    @Test
    fun `a plan with no recorded identity never touches the host`() {
        val shell = healthy()
        val result = DeployCreator(shell).workloadDelete(plan(uid = null))

        assertEquals(64, result.exitCode)
        assertTrue(result.stderr.contains("no recorded cluster identity"))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `a rollback is guarded by the same checks`() {
        val denied = healthy(canI = "no")
        assertEquals(64, DeployCreator(denied).workloadRollback(plan()).exitCode)
        assertFalse(denied.commands.any { it.contains("rollout undo") })

        val replaced = healthy(identity = objectJson(otherUid))
        assertEquals(64, DeployCreator(replaced).workloadRollback(plan()).exitCode)
        assertFalse(replaced.commands.any { it.contains("rollout undo") })
    }

    @Test
    fun `a consistent host allows exactly one delete of the named resource`() {
        val shell = healthy()
        val result = DeployCreator(shell).workloadDelete(plan())

        assertEquals(0, result.exitCode)
        val deletes = shell.commands.filter { it.contains("delete deployment/") }
        assertEquals(1, deletes.size)
        assertTrue(
            deletes.single().endsWith(
                "kubectl --context prod-cluster --namespace demo " +
                    "delete deployment/anthracite-1a2b3c4d --ignore-not-found --wait=false",
            ),
        )
        // Context, authority and identity are read, in that order, before the delete.
        assertEquals(4, shell.commands.size)
        assertTrue(shell.commands[0].endsWith("config current-context"))
        assertTrue(shell.commands[1].contains("auth can-i delete deployment"))
        assertTrue(shell.commands[2].contains("get deployment/anthracite-1a2b3c4d"))
        assertTrue(shell.commands[3].contains("delete deployment/"))
    }

    // ---------------------------------------------------------------------- apply

    @Test
    fun `an apply sends the generated manifest on stdin and records the uid`() {
        val shell = FakeShell { command ->
            when {
                command.endsWith("config current-context") -> ExecResult(0, "prod-cluster", "")
                command.contains("auth can-i") -> ExecResult(0, "yes", "")
                command.endsWith("apply -f -") -> ExecResult(0, "deployment.apps/x created", "")
                command.contains(" -o json") -> ExecResult(0, objectJson(uid), "")
                else -> ExecResult(0, "", "")
            }
        }
        val target = plan(uid = null)
        val outcome = DeployCreator(shell).workloadApply(target)

        assertEquals(0, outcome.result.exitCode)
        assertEquals(uid, outcome.appliedUid)
        val applyIndex = shell.commands.indexOfFirst { it.endsWith("apply -f -") }
        assertTrue(applyIndex >= 0)
        assertEquals(target.manifest(), String(shell.stdins[applyIndex]!!))
        // The manifest never travels as an argument.
        assertFalse(shell.commands.any { it.contains("kind: Deployment") })
    }

    @Test
    fun `an apply the cluster did not label yields no identity`() {
        val shell = FakeShell { command ->
            when {
                command.endsWith("config current-context") -> ExecResult(0, "prod-cluster", "")
                command.contains("auth can-i") -> ExecResult(0, "yes", "")
                command.endsWith("apply -f -") -> ExecResult(0, "", "")
                command.contains(" -o json") -> ExecResult(0, objectJson(uid, label = null), "")
                else -> ExecResult(0, "", "")
            }
        }
        assertNull(DeployCreator(shell).workloadApply(plan(uid = null)).appliedUid)
    }

    @Test
    fun `an apply to a changed context never runs kubectl apply`() {
        val shell = healthy(current = "other")
        val outcome = DeployCreator(shell).workloadApply(plan(uid = null))

        assertEquals(64, outcome.result.exitCode)
        assertNull(outcome.appliedUid)
        assertFalse(shell.commands.any { it.endsWith("apply -f -") })
    }

    @Test
    fun `an identity read that is malformed proves nothing`() {
        assertNull(WorkloadGuard.parseIdentity(""))
        assertNull(WorkloadGuard.parseIdentity("not json at all"))
        assertNull(WorkloadGuard.parseIdentity("{\"metadata\":{}}"))
        assertNull(WorkloadGuard.parseIdentity("{\"metadata\":{\"uid\":\"not-a-uid\"}}"))
        assertNull(WorkloadGuard.parseIdentity("{\"kind\":\"Deployment\"}"))
        assertEquals(WorkloadIdentity(uid, "1"), WorkloadGuard.parseIdentity(objectJson(uid)))
        assertEquals(WorkloadIdentity(uid, null), WorkloadGuard.parseIdentity(objectJson(uid, label = null)))
    }
}
