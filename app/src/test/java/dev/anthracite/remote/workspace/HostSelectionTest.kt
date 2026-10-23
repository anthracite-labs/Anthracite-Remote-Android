package dev.anthracite.remote.workspace

import dev.anthracite.remote.AuthMethod
import dev.anthracite.remote.host.HostOrigin
import dev.anthracite.remote.host.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen opened for one host must never act on another.
 *
 * The dangerous shape is "the requested id did not resolve, so use whatever is
 * connected": a stale notification or a restored activity could then point a
 * destructive screen at the wrong host. An explicit id either resolves or the
 * screen stops.
 */
class HostSelectionTest {
    private fun profile(id: String, label: String = id) = HostProfile(
        id = id,
        label = label,
        host = "example.test",
        port = 22,
        username = "ada",
        auth = AuthMethod.PASSWORD,
        remotePort = 4096,
        keyName = "",
        hostKey = null,
        hostKeyType = "",
        fingerprint = "",
        origin = HostOrigin.CREATED,
        lastUsedAtMillis = 0,
    )

    private val alpha = profile("alpha")
    private val beta = profile("beta")

    @Test
    fun `an explicit id resolves to exactly that host`() {
        val selection = HostPicker.select("beta", listOf(alpha, beta), active = alpha)
        assertEquals(HostSelection.Selected(beta), selection)
    }

    @Test
    fun `an unknown explicit id is missing, never the active host`() {
        val selection = HostPicker.select("deleted", listOf(alpha, beta), active = alpha)
        assertEquals(HostSelection.Missing("deleted"), selection)
        assertEquals(null, selection.profileOrNull)
    }

    @Test
    fun `no id uses the active host`() {
        assertEquals(HostSelection.Selected(alpha), HostPicker.select(null, listOf(alpha, beta), alpha))
    }

    @Test
    fun `no id and no active host is none`() {
        assertEquals(HostSelection.None, HostPicker.select(null, listOf(alpha), null))
    }

    @Test
    fun `an empty store with an explicit id is missing, not none`() {
        val selection = HostPicker.select("alpha", emptyList(), active = null)
        assertTrue(selection is HostSelection.Missing)
    }
}
