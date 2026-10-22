package dev.anthracite.remote

/** Remote operations that get a progress surface. */
internal enum class DeployOp { INSTALL, OPEN_MANAGED, OPEN_EXISTING, RESTART, UPDATE, UNINSTALL, STATUS }

internal enum class PhaseId {
    CONNECT_SSH, UPLOAD_INSTALLER, PREFLIGHT, DOWNLOAD, VERIFY_CHECKSUM, UNPACK,
    VERIFY_BINARY, ACTIVATE, SERVICE_READY, RUN_COMMAND, PROBE_SERVICE, OPEN_TUNNEL,
    REOPEN_TUNNEL, LAUNCH_WEB,
}

internal enum class PhaseState { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

internal data class Phase(val id: PhaseId, val state: PhaseState, val detail: String? = null)

internal data class DeployProgressState(
    val op: DeployOp,
    val phases: List<Phase>,
    val failureMessage: String? = null,
    val stopped: Boolean = false,
    val lingerNote: Boolean = false,
) {
    val failed: Boolean get() = failureMessage != null
    val finished: Boolean
        get() = !failed && !stopped &&
            phases.all { it.state == PhaseState.DONE || it.state == PhaseState.SKIPPED }
    val activePhase: Phase? get() = phases.firstOrNull { it.state == PhaseState.ACTIVE }
}

/**
 * Immutable deployment-progress model driven only by real events:
 *
 * - activity-side boundaries (connect, upload, probe, tunnel, launch) activate
 *   their own phases;
 * - the bundled installer's own output lines (`step:`, `ok:`, `error:`,
 *   `limitation:`, `preserved:`) advance phases and details.
 *
 * No percentages and no invented events: an unknown line never moves a phase,
 * and a phase that never ran is marked [PhaseState.SKIPPED], not done.
 */
internal object DeployProgress {
    fun phasesFor(op: DeployOp): List<PhaseId> = when (op) {
        DeployOp.INSTALL -> listOf(
            PhaseId.CONNECT_SSH, PhaseId.UPLOAD_INSTALLER, PhaseId.PREFLIGHT, PhaseId.DOWNLOAD,
            PhaseId.VERIFY_CHECKSUM, PhaseId.UNPACK, PhaseId.VERIFY_BINARY, PhaseId.ACTIVATE,
            PhaseId.SERVICE_READY, PhaseId.PROBE_SERVICE, PhaseId.OPEN_TUNNEL, PhaseId.LAUNCH_WEB,
        )
        DeployOp.OPEN_MANAGED, DeployOp.OPEN_EXISTING -> listOf(
            PhaseId.CONNECT_SSH, PhaseId.PROBE_SERVICE, PhaseId.OPEN_TUNNEL, PhaseId.LAUNCH_WEB,
        )
        // cmd_restart: step restart -> restart_service -> wait_ready (step ready).
        DeployOp.RESTART -> listOf(
            PhaseId.CONNECT_SSH, PhaseId.UPLOAD_INSTALLER, PhaseId.RUN_COMMAND,
            PhaseId.SERVICE_READY, PhaseId.REOPEN_TUNNEL,
        )
        // cmd_update: optional preflight + stage_release + activate + ready, or
        // "No approved update" with no steps at all.
        DeployOp.UPDATE -> listOf(
            PhaseId.CONNECT_SSH, PhaseId.UPLOAD_INSTALLER, PhaseId.PREFLIGHT, PhaseId.DOWNLOAD,
            PhaseId.VERIFY_CHECKSUM, PhaseId.UNPACK, PhaseId.VERIFY_BINARY, PhaseId.ACTIVATE,
            PhaseId.SERVICE_READY,
        )
        DeployOp.UNINSTALL, DeployOp.STATUS -> listOf(
            PhaseId.CONNECT_SSH, PhaseId.UPLOAD_INSTALLER, PhaseId.RUN_COMMAND,
        )
    }

    fun initial(op: DeployOp): DeployProgressState =
        DeployProgressState(op, phasesFor(op).map { Phase(it, PhaseState.PENDING) })

    /** Maps the installer's own `step:` markers to phases. Unknown markers map to null. */
    fun phaseForStep(step: String): PhaseId? = when (step.trim()) {
        "preflight-os", "preflight-arch", "preflight-user", "preflight-tools",
        "preflight-disk", "preflight-systemd", "preflight-conflict", "preflight-port",
        -> PhaseId.PREFLIGHT
        "download" -> PhaseId.DOWNLOAD
        "verify-hash" -> PhaseId.VERIFY_CHECKSUM
        "unpack" -> PhaseId.UNPACK
        "verify-binary" -> PhaseId.VERIFY_BINARY
        // step activate / step service both belong to release activation.
        "activate", "service" -> PhaseId.ACTIVATE
        // step ready / step linger belong to service readiness (linger follows ready).
        "ready", "linger" -> PhaseId.SERVICE_READY
        "restart", "uninstall", "status" -> PhaseId.RUN_COMMAND
        else -> null
    }

    fun activate(state: DeployProgressState, id: PhaseId, detail: String? = null): DeployProgressState {
        if (state.failed || state.stopped) return state
        val target = state.phases.indexOfFirst { it.id == id }
        if (target < 0) return state
        val phases = state.phases.mapIndexed { index, phase ->
            when {
                index < target -> when (phase.state) {
                    PhaseState.PENDING -> phase.copy(state = PhaseState.SKIPPED)
                    PhaseState.ACTIVE -> phase.copy(state = PhaseState.DONE)
                    else -> phase
                }
                index == target -> when (phase.state) {
                    PhaseState.FAILED -> phase
                    else -> phase.copy(state = PhaseState.ACTIVE, detail = detail ?: phase.detail)
                }
                else -> phase
            }
        }
        return state.copy(phases = phases)
    }

    fun skip(state: DeployProgressState, id: PhaseId): DeployProgressState {
        val phases = state.phases.map { if (it.id == id && it.state == PhaseState.PENDING) it.copy(state = PhaseState.SKIPPED) else it }
        return state.copy(phases = phases)
    }

    fun succeed(state: DeployProgressState): DeployProgressState {
        if (state.failed) return state
        val phases = state.phases.map {
            when (it.state) {
                PhaseState.ACTIVE -> it.copy(state = PhaseState.DONE)
                PhaseState.PENDING -> it.copy(state = PhaseState.SKIPPED)
                else -> it
            }
        }
        return state.copy(phases = phases)
    }

    fun fail(state: DeployProgressState, message: String?): DeployProgressState {
        if (state.failed) return state
        val cleaned = message?.trim()?.takeIf { it.isNotEmpty() }
        val phases = state.phases.map {
            if (it.state == PhaseState.ACTIVE) it.copy(state = PhaseState.FAILED) else it
        }
        return state.copy(phases = phases, failureMessage = cleaned ?: "failed")
    }

    fun stop(state: DeployProgressState): DeployProgressState {
        if (state.failed) return state
        return state.copy(stopped = true)
    }

    /** Folds one installer/session output line into the state. Unknown lines change nothing. */
    fun onLine(state: DeployProgressState, line: String): DeployProgressState {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return state
        return when {
            trimmed.startsWith("step:") -> {
                val step = trimmed.removePrefix("step:").trim()
                val phase = phaseForStep(step) ?: return state
                activate(state, phase, detail = step)
            }
            trimmed.startsWith("error:") -> fail(state, trimmed.removePrefix("error:").trim())
            trimmed.startsWith("ok:") -> withDetail(state, trimmed.removePrefix("ok:").trim())
            trimmed.startsWith("limitation:") -> withDetail(state, trimmed.removePrefix("limitation:").trim())
                .copy(lingerNote = state.lingerNote || trimmed.contains("linger"))
            trimmed.startsWith("preserved:") -> withDetail(state, trimmed.removePrefix("preserved:").trim())
            else -> state
        }
    }

    private fun withDetail(state: DeployProgressState, detail: String): DeployProgressState {
        val phases = state.phases.map {
            if (it.state == PhaseState.ACTIVE && detail.isNotEmpty()) it.copy(detail = detail) else it
        }
        return state.copy(phases = phases)
    }
}
