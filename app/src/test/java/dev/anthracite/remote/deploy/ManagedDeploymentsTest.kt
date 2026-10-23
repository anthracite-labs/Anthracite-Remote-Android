package dev.anthracite.remote.deploy

import dev.anthracite.remote.host.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recorded deployment is only useful if it can be trusted to describe the
 * object the app actually created. These tests pin both halves of that: the
 * record survives a restart intact, and every way of misreading it — a corrupt
 * preference, a different host, a replaced object — withholds the operations
 * instead of offering them on a name.
 */
class ManagedDeploymentsTest {
    private val uid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val otherUid = "11111111-2222-3333-4444-555555555555"
    private val containerId = "a".repeat(64)
    private val otherContainerId = "b".repeat(64)
    private val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"

    private class FakePreferences : Preferences {
        val values = mutableMapOf<String, Any>()
        var failNextCommit = false

        override fun string(key: String): String? = values[key] as? String
        override fun int(key: String, fallback: Int): Int = values[key] as? Int ?: fallback
        override fun boolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun commit(): Boolean {
            if (failNextCommit) {
                failNextCommit = false
                return false
            }
            return true
        }
    }

    private fun container(identity: String = containerId) = ManagedDeployment(
        hostId = "host-1",
        hostFingerprint = fingerprint,
        kind = ManagedTargetKind.CONTAINER,
        name = "anthracite-1a2b3c4d",
        identity = identity,
        runtime = DeployCommands.Runtime.DOCKER,
        image = "nginx:1.27",
        createdAtMillis = 1_700_000_000_000,
    )

    private fun workload(identity: String = this.uid) = ManagedDeployment(
        hostId = "host-1",
        hostFingerprint = fingerprint,
        kind = ManagedTargetKind.WORKLOAD,
        name = "anthracite-1a2b3c4d",
        identity = identity,
        workloadKind = WorkloadKind.DEPLOYMENT,
        context = "prod-cluster",
        namespace = "demo",
        image = "nginx:1.27",
        replicas = 2,
        containerPort = 8080,
        createdAtMillis = 1_700_000_000_000,
    )

    private fun target(
        hostId: String = "host-1",
        hostFingerprint: String = fingerprint,
        runtime: DeployCommands.Runtime? = DeployCommands.Runtime.DOCKER,
        context: String? = "prod-cluster",
        namespace: String? = "demo",
    ) = DeploymentTarget(hostId, hostFingerprint, runtime, context, namespace)

    // ------------------------------------------------------------------ records

    @Test
    fun `a container record survives a round trip`() {
        val encoded = ManagedDeploymentCodec.encode(container())
        assertNotNull(encoded)
        assertEquals(container(), ManagedDeploymentCodec.decode(encoded))
    }

    @Test
    fun `a workload record survives a round trip`() {
        val encoded = ManagedDeploymentCodec.encode(workload())
        assertNotNull(encoded)
        assertEquals(workload(), ManagedDeploymentCodec.decode(encoded))
    }

    @Test
    fun `a record whose identity is a name is refused`() {
        // This is the failure the record exists to prevent: a name is not ownership.
        assertFalse(container(identity = "anthracite-1a2b3c4d").isValid())
        assertNull(ManagedDeploymentCodec.encode(container(identity = "anthracite-1a2b3c4d")))
    }

    @Test
    fun `a workload record without a context is refused`() {
        assertFalse(workload().copy(context = null).isValid())
        assertFalse(workload().copy(namespace = null).isValid())
        assertFalse(workload().copy(workloadKind = null).isValid())
        // A container field must not leak into a workload record either.
        assertFalse(workload().copy(runtime = DeployCommands.Runtime.DOCKER).isValid())
    }

    @Test
    fun `garbage, an unknown version and an unknown kind decode to nothing`() {
        assertNull(ManagedDeploymentCodec.decode(null))
        assertNull(ManagedDeploymentCodec.decode(""))
        assertNull(ManagedDeploymentCodec.decode("{"))
        assertNull(ManagedDeploymentCodec.decode("[]"))
        assertNull(ManagedDeploymentCodec.decode("""{"version":1}"""))
        val encoded = ManagedDeploymentCodec.encode(container())!!
        assertNull(ManagedDeploymentCodec.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull(ManagedDeploymentCodec.decode(encoded.replace("CONTAINER", "SOMETHING_ELSE")))
        // A field edited to an invalid value is rejected, not partially trusted.
        assertNull(
            ManagedDeploymentCodec.decode(
                encoded.replace(containerId, "not-an-id"),
            ),
        )
    }

    @Test
    fun `a native plan has no identity to record`() {
        val native = DeployPlan.Native(
            name = "anthracite-1a2b3c4d",
            workingDirectory = "/home/user",
            executable = "sleep",
            arguments = listOf("60"),
        )
        assertNull(ManagedRecords.of(native, "host-1", fingerprint, 1))
    }

    @Test
    fun `a confirmed plan produces a record that rebuilds the same plan`() {
        val plan = DeployPlan.Workload(
            name = "anthracite-1a2b3c4d",
            context = "prod-cluster",
            namespace = "demo",
            kind = WorkloadKind.DEPLOYMENT,
            image = "nginx:1.27",
            replicas = 2,
            appliedUid = uid,
        )

        val record = ManagedRecords.of(plan, "host-1", fingerprint, 1_700_000_000_000)
        assertNotNull(record)
        val encoded = ManagedDeploymentCodec.encode(record!!)!!
        val restored = ManagedPlans.of(ManagedDeploymentCodec.decode(encoded)!!)

        assertEquals(plan, restored)
    }

    // -------------------------------------------------------------------- store

    @Test
    fun `the store keeps one slot per host and refuses a host id with a separator`() {
        val preferences = FakePreferences()
        val first = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-1"))
        val second = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-2"))

        assertTrue(first.save(container()))
        assertTrue(second.save(container().copy(hostId = "host-2")))

        assertEquals(container(), first.load())
        assertEquals("host-2", second.load()?.hostId)
        assertThrows(IllegalArgumentException::class.java) {
            ManagedDeploymentStore.keyFor("host/../other")
        }
    }

    @Test
    fun `a failed commit leaves the previous record in place`() {
        val preferences = FakePreferences()
        val store = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-1"))
        assertTrue(store.save(container()))

        preferences.failNextCommit = true
        // A valid record for a different container: only the failed commit can
        // explain the old value still being there.
        assertFalse(store.save(container(otherContainerId)))
        assertEquals(container(), store.load())
    }

    @Test
    fun `a failed first commit leaves nothing behind`() {
        val preferences = FakePreferences()
        val store = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-1"))

        preferences.failNextCommit = true
        assertFalse(store.save(container()))
        assertNull(store.load())
    }

    @Test
    fun `clearing removes the record`() {
        val preferences = FakePreferences()
        val store = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-1"))
        assertTrue(store.save(container()))
        assertTrue(store.clear())
        assertNull(store.load())
    }

    @Test
    fun `a stored record for another schema version is ignored`() {
        val preferences = FakePreferences()
        val store = ManagedDeploymentStore(preferences, ManagedDeploymentStore.keyFor("host-1"))
        assertTrue(store.save(container()))
        preferences.values[ManagedDeploymentStore.keyFor("host-1")] =
            (preferences.values[ManagedDeploymentStore.keyFor("host-1")] as String)
                .replace("\"version\":1", "\"version\":9")
        assertNull(store.load())
    }

    // ----------------------------------------------------------- reconciliation

    @Test
    fun `the same identity is the only match`() {
        assertEquals(
            ManagedState.MATCH,
            ManagedReconciliation.evaluate(container(), target(), LiveIdentity.Present(containerId)),
        )
        assertEquals(
            ManagedState.REPLACED,
            ManagedReconciliation.evaluate(container(), target(), LiveIdentity.Present(otherUid)),
        )
        assertEquals(
            ManagedState.GONE,
            ManagedReconciliation.evaluate(container(), target(), LiveIdentity.Missing),
        )
        assertEquals(
            ManagedState.UNKNOWN,
            ManagedReconciliation.evaluate(container(), target(), LiveIdentity.Unreadable("timeout")),
        )
    }

    @Test
    fun `a record from another host, runtime, context or namespace is never applied here`() {
        val elsewhere = listOf(
            target(hostId = "host-2"),
            target(hostFingerprint = "SHA256:someone-else"),
            target(runtime = DeployCommands.Runtime.PODMAN),
        )
        for (candidate in elsewhere) {
            assertEquals(
                ManagedState.ELSEWHERE,
                ManagedReconciliation.evaluate(container(), candidate, LiveIdentity.Present(containerId)),
            )
        }

        val workloads = listOf(
            target(hostId = "host-2"),
            target(runtime = null, context = "staging"),
            target(runtime = null, namespace = "other"),
        )
        for (candidate in workloads) {
            assertEquals(
                ManagedState.ELSEWHERE,
                ManagedReconciliation.evaluate(workload(), candidate, LiveIdentity.Present(uid)),
            )
        }
    }

    @Test
    fun `a target the screen does not know is not compared`() {
        // Container mode has no cluster selected: the screen knows the host but not
        // the context, so a workload record is judged on the host alone. The plan
        // re-reads context and namespace on the host before any mutation.
        assertEquals(
            ManagedState.MATCH,
            ManagedReconciliation.evaluate(
                workload(),
                target(runtime = null, context = null, namespace = null),
                LiveIdentity.Present(uid),
            ),
        )
        // A container record in pod mode: the runtime is unknown, so it is not compared.
        assertEquals(
            ManagedState.MATCH,
            ManagedReconciliation.evaluate(
                container(),
                target(runtime = null, context = "prod-cluster", namespace = "demo"),
                LiveIdentity.Present(containerId),
            ),
        )
    }

    @Test
    fun `a record for another host is not even evidence about this one`() {
        val record = workload()
        assertFalse(ManagedReconciliation.sameTarget(record, target(hostId = "host-2")))
        assertTrue(ManagedReconciliation.sameTarget(record, target(runtime = null)))
        // The same name and identity in another namespace is a different object.
        assertFalse(
            ManagedReconciliation.sameTarget(record, target(runtime = null, namespace = "other")),
        )
    }
}
