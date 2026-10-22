package dev.anthracite.remote

/**
 * Collapses network handovers into one reconnect attempt and ignores attempts
 * after an explicit disconnect. The activity still has to disconnect the SSH
 * session and start at most one job for the returned token.
 */
internal class ReconnectGate {
    private var generation = 0
    var wantConnected: Boolean = false
        private set

    fun userConnected() {
        wantConnected = true
    }

    fun userDisconnected(): Int {
        wantConnected = false
        generation += 1
        return generation
    }

    /** Returns the token a delayed reconnect must match, or null when reconnecting is forbidden. */
    fun networkChanged(): Int? {
        generation += 1
        if (!wantConnected) return null
        return generation
    }

    fun stillCurrent(token: Int): Boolean = wantConnected && token == generation
}
