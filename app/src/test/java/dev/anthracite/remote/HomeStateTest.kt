package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStateTest {
    private fun ui(
        hasConnection: Boolean = true,
        lastMode: LaunchMode? = null,
        sshConnected: Boolean = false,
        tunnelPort: Int = -1,
        operationRunning: Boolean = false,
        reconnectPending: Boolean = false,
        errorPresent: Boolean = false,
    ) = HomeStatePolicy.homeUi(
        hasConnection = hasConnection,
        lastMode = lastMode,
        sshConnected = sshConnected,
        tunnelPort = tunnelPort,
        operationRunning = operationRunning,
        reconnectPending = reconnectPending,
        errorPresent = errorPresent,
    )

    @Test
    fun `without a saved connection the only action is setup`() {
        val home = ui(hasConnection = false)
        assertEquals(HomeCta.SET_UP, home.cta)
        assertEquals(HomeSecondary.NONE, home.secondary)
        assertEquals(ConnectionStatus.NO_SERVER, home.status)
    }

    @Test
    fun `a fresh verified server offers install as the single dominant action`() {
        val home = ui(lastMode = null)
        assertEquals(HomeCta.INSTALL_AND_LAUNCH, home.cta)
        assertEquals(HomeSecondary.CONNECT_EXISTING, home.secondary)
        assertEquals(ConnectionStatus.IDLE, home.status)
    }

    @Test
    fun `after a managed open the cta is open opencode and the alternate journey stays reachable`() {
        val home = ui(lastMode = LaunchMode.MANAGED)
        assertEquals(HomeCta.OPEN_OPENCODE, home.cta)
        assertEquals(HomeSecondary.CONNECT_EXISTING, home.secondary)
    }

    @Test
    fun `after an existing-service open the secondary offers the managed install explicitly`() {
        val home = ui(lastMode = LaunchMode.EXISTING)
        assertEquals(HomeCta.OPEN_OPENCODE, home.cta)
        assertEquals(HomeSecondary.INSTALL_MANAGED, home.secondary)
    }

    @Test
    fun `a live tunnel outranks every other chip state`() {
        val home = ui(lastMode = LaunchMode.MANAGED, tunnelPort = 41234, errorPresent = true, reconnectPending = true)
        assertEquals(ConnectionStatus.TUNNEL_READY, home.status)
    }

    @Test
    fun `chip states follow the connection lifecycle`() {
        assertEquals(ConnectionStatus.ERROR, ui(errorPresent = true).status)
        assertEquals(ConnectionStatus.CONNECTING, ui(operationRunning = true).status)
        assertEquals(ConnectionStatus.RECONNECTING, ui(reconnectPending = true).status)
        assertEquals(ConnectionStatus.SSH_READY, ui(sshConnected = true).status)
        assertEquals(ConnectionStatus.IDLE, ui().status)
    }

    @Test
    fun `an in-flight operation outranks a plain reconnect wish`() {
        assertEquals(ConnectionStatus.CONNECTING, ui(operationRunning = true, reconnectPending = true).status)
    }
}
