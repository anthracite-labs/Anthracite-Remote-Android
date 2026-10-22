package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployProgressTest {
    private fun stateOf(state: DeployProgressState, id: PhaseId): PhaseState =
        state.phases.first { it.id == id }.state

    @Test
    fun `install phases follow the real installer sequence`() {
        assertEquals(
            listOf(
                PhaseId.CONNECT_SSH, PhaseId.UPLOAD_INSTALLER, PhaseId.PREFLIGHT, PhaseId.DOWNLOAD,
                PhaseId.VERIFY_CHECKSUM, PhaseId.UNPACK, PhaseId.VERIFY_BINARY, PhaseId.ACTIVATE,
                PhaseId.SERVICE_READY, PhaseId.PROBE_SERVICE, PhaseId.OPEN_TUNNEL, PhaseId.LAUNCH_WEB,
            ),
            DeployProgress.phasesFor(DeployOp.INSTALL),
        )
        assertEquals(
            listOf(PhaseId.CONNECT_SSH, PhaseId.PROBE_SERVICE, PhaseId.OPEN_TUNNEL, PhaseId.LAUNCH_WEB),
            DeployProgress.phasesFor(DeployOp.OPEN_EXISTING),
        )
    }

    @Test
    fun `activating a later phase completes earlier ones and skips phases that never ran`() {
        var state = DeployProgress.initial(DeployOp.INSTALL)
        state = DeployProgress.activate(state, PhaseId.CONNECT_SSH)
        assertEquals(PhaseState.ACTIVE, stateOf(state, PhaseId.CONNECT_SSH))
        state = DeployProgress.onLine(state, "step: preflight-os")
        assertEquals(PhaseState.DONE, stateOf(state, PhaseId.CONNECT_SSH))
        assertEquals(PhaseState.SKIPPED, stateOf(state, PhaseId.UPLOAD_INSTALLER))
        assertEquals(PhaseState.ACTIVE, stateOf(state, PhaseId.PREFLIGHT))
        assertEquals("preflight-os", state.phases.first { it.id == PhaseId.PREFLIGHT }.detail)
    }

    @Test
    fun `every real installer step marker maps to a phase and unknown markers do not`() {
        val markers = mapOf(
            "preflight-os" to PhaseId.PREFLIGHT,
            "preflight-port" to PhaseId.PREFLIGHT,
            "download" to PhaseId.DOWNLOAD,
            "verify-hash" to PhaseId.VERIFY_CHECKSUM,
            "unpack" to PhaseId.UNPACK,
            "verify-binary" to PhaseId.VERIFY_BINARY,
            "activate" to PhaseId.ACTIVATE,
            "service" to PhaseId.ACTIVATE,
            "ready" to PhaseId.SERVICE_READY,
            "linger" to PhaseId.SERVICE_READY,
            "restart" to PhaseId.RUN_COMMAND,
            "uninstall" to PhaseId.RUN_COMMAND,
            "status" to PhaseId.RUN_COMMAND,
        )
        markers.forEach { (step, expected) -> assertEquals("step: $step", expected, DeployProgress.phaseForStep(step)) }
        assertNull(DeployProgress.phaseForStep("something-invented"))
        assertNull(DeployProgress.phaseForStep(""))
    }

    @Test
    fun `an unknown output line never moves a phase`() {
        val state = DeployProgress.initial(DeployOp.INSTALL)
        val unchanged = DeployProgress.onLine(state, "downloading https://example.invalid/opencode.tar.gz")
        assertEquals(state, unchanged)
        assertEquals(state, DeployProgress.onLine(state, ""))
    }

    @Test
    fun `an error line fails the active phase with the installer's own message`() {
        var state = DeployProgress.initial(DeployOp.INSTALL)
        state = DeployProgress.activate(state, PhaseId.DOWNLOAD)
        state = DeployProgress.onLine(state, "error: download failed; partial file kept at /tmp/x")
        assertTrue(state.failed)
        assertEquals("download failed; partial file kept at /tmp/x", state.failureMessage)
        assertEquals(PhaseState.FAILED, stateOf(state, PhaseId.DOWNLOAD))
        assertFalse(state.finished)
    }

    @Test
    fun `a failure freezes the phase list`() {
        var state = DeployProgress.initial(DeployOp.INSTALL)
        state = DeployProgress.onLine(state, "error: boom")
        val frozen = DeployProgress.activate(state, PhaseId.DOWNLOAD)
        assertEquals(state, frozen)
    }

    @Test
    fun `a limitation mentioning lingering raises the persistence note once`() {
        var state = DeployProgress.initial(DeployOp.INSTALL)
        state = DeployProgress.activate(state, PhaseId.SERVICE_READY)
        assertFalse(state.lingerNote)
        state = DeployProgress.onLine(state, "limitation: without lingering, this user service stops when the last login session ends, including after logout or reboot.")
        assertTrue(state.lingerNote)
        assertEquals(
            "without lingering, this user service stops when the last login session ends, including after logout or reboot.",
            state.phases.first { it.id == PhaseId.SERVICE_READY }.detail,
        )
    }

    @Test
    fun `success marks the active phase done and unrun phases skipped, never done`() {
        var state = DeployProgress.initial(DeployOp.UPDATE)
        state = DeployProgress.activate(state, PhaseId.CONNECT_SSH)
        state = DeployProgress.activate(state, PhaseId.RUN_COMMAND)
        state = DeployProgress.succeed(state)
        assertEquals(PhaseState.DONE, stateOf(state, PhaseId.CONNECT_SSH))
        assertEquals(PhaseState.DONE, stateOf(state, PhaseId.RUN_COMMAND))
        assertEquals(PhaseState.SKIPPED, stateOf(state, PhaseId.DOWNLOAD))
        assertEquals(PhaseState.SKIPPED, stateOf(state, PhaseId.SERVICE_READY))
        assertTrue(state.finished)
    }

    @Test
    fun `stop keeps completed phases honest and blocks further progress`() {
        var state = DeployProgress.initial(DeployOp.INSTALL)
        state = DeployProgress.activate(state, PhaseId.PREFLIGHT)
        state = DeployProgress.stop(state)
        assertTrue(state.stopped)
        assertFalse(state.finished)
        assertEquals(state, DeployProgress.activate(state, PhaseId.DOWNLOAD))
    }

    @Test
    fun `line stream reassembles chunks, crlf, split utf-8, and flushes the remainder`() {
        val stream = LineStream()
        assertEquals(emptyList<String>(), stream.accept(ByteArray(0)))
        assertEquals(listOf("step: down"), stream.accept("step: down".toByteArray(Charsets.UTF_8)))
        assertEquals(emptyList<String>(), stream.flush())

        val crlf = LineStream()
        assertEquals(listOf("ok: done", "warn: x"), crlf.accept("ok: done\r\nwarn: x\r\n".toByteArray(Charsets.UTF_8)))

        val split = LineStream()
        val bytes = "ok: café\n".toByteArray(Charsets.UTF_8)
        // Cut between the two UTF-8 bytes of 'é' (trailing byte + newline = 2).
        val cut = bytes.size - 2
        assertEquals(emptyList<String>(), split.accept(bytes.copyOfRange(0, cut)))
        assertEquals(listOf("ok: café"), split.accept(bytes.copyOfRange(cut, bytes.size)))

        val partial = LineStream()
        assertEquals(emptyList<String>(), partial.accept("tail without newline".toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("tail without newline"), partial.flush())
        assertEquals(emptyList<String>(), partial.flush())
    }
}
