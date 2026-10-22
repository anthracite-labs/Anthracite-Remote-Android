package dev.anthracite.remote

/** Which remote OpenCode the phone opened last. Non-secret UI state. */
internal enum class LaunchMode { MANAGED, EXISTING }

internal enum class HomeCta { SET_UP, INSTALL_AND_LAUNCH, OPEN_OPENCODE }

internal enum class HomeSecondary { NONE, CONNECT_EXISTING, INSTALL_MANAGED }

internal enum class ConnectionStatus { NO_SERVER, IDLE, CONNECTING, SSH_READY, TUNNEL_READY, RECONNECTING, ERROR }

internal data class HomeUi(
    val cta: HomeCta,
    val secondary: HomeSecondary,
    val status: ConnectionStatus,
)

/**
 * Resolves the adaptive Home surface: exactly one dominant action per state,
 * a quiet secondary for the alternate journey, and the status-chip state.
 *
 * The alternate journey is always explicit: an existing-installation open
 * never runs the installer, and a managed install is only ever started from
 * its own primary or secondary action.
 */
internal object HomeStatePolicy {
    fun homeUi(
        hasConnection: Boolean,
        lastMode: LaunchMode?,
        sshConnected: Boolean,
        tunnelPort: Int,
        operationRunning: Boolean,
        reconnectPending: Boolean,
        errorPresent: Boolean,
    ): HomeUi {
        if (!hasConnection) {
            return HomeUi(HomeCta.SET_UP, HomeSecondary.NONE, ConnectionStatus.NO_SERVER)
        }
        val status = when {
            tunnelPort > 0 -> ConnectionStatus.TUNNEL_READY
            errorPresent -> ConnectionStatus.ERROR
            operationRunning -> ConnectionStatus.CONNECTING
            reconnectPending -> ConnectionStatus.RECONNECTING
            sshConnected -> ConnectionStatus.SSH_READY
            else -> ConnectionStatus.IDLE
        }
        val cta = if (lastMode == null) HomeCta.INSTALL_AND_LAUNCH else HomeCta.OPEN_OPENCODE
        val secondary = when (lastMode) {
            null, LaunchMode.MANAGED -> HomeSecondary.CONNECT_EXISTING
            LaunchMode.EXISTING -> HomeSecondary.INSTALL_MANAGED
        }
        return HomeUi(cta, secondary, status)
    }
}
