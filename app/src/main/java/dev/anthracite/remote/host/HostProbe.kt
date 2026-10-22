package dev.anthracite.remote.host

import dev.anthracite.remote.SshClient

/**
 * Runs the shipped read-only probe.
 *
 * The script is sent on stdin (`sh -s`) instead of being written to the host, so
 * a probe leaves no file behind. Nothing in it installs, escalates or writes.
 */
internal class HostProbe(private val ssh: SshClient, private val script: String) {
    fun run(timeoutMs: Long = TIMEOUT_MS): HostFacts {
        val result = ssh.exec("sh -s", stdin = script.toByteArray(Charsets.UTF_8), timeoutMs = timeoutMs)
        if (result.stdout.isBlank()) {
            throw IllegalStateException(
                "The host returned no facts (exit ${result.exitCode}): ${result.text.take(200)}",
            )
        }
        val facts = HostFactsParser.parse(result.stdout, System.currentTimeMillis())
        if (!facts.complete) {
            throw IllegalStateException(
                "The probe did not finish; the result would be incomplete. ${result.text.take(200)}",
            )
        }
        return facts
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
