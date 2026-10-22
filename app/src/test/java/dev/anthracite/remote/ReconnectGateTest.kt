package dev.anthracite.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectGateTest {
    @Test
    fun `explicit disconnect suppresses later automatic reconnect`() {
        val gate = ReconnectGate()
        gate.userConnected()
        gate.userDisconnected()
        assertFalse(gate.wantConnected)
        assertNull(gate.networkChanged())
    }

    @Test
    fun `rapid network changes keep only the latest attempt`() {
        val gate = ReconnectGate()
        gate.userConnected()
        val first = gate.networkChanged()
        val second = gate.networkChanged()
        assertTrue(first != null && second != null && first != second)
        assertFalse(gate.stillCurrent(first!!))
        assertTrue(gate.stillCurrent(second!!))
    }

    @Test
    fun `disconnect invalidates an in-flight reconnect`() {
        val gate = ReconnectGate()
        gate.userConnected()
        val token = gate.networkChanged()!!
        gate.userDisconnected()
        assertFalse(gate.stillCurrent(token))
        assertNull(gate.networkChanged())
    }

    @Test
    fun `manual reconnect is allowed again after disconnect`() {
        val gate = ReconnectGate()
        gate.userConnected()
        gate.userDisconnected()
        gate.userConnected()
        val token = gate.networkChanged()
        assertTrue(token != null && gate.stillCurrent(token!!))
    }
}
