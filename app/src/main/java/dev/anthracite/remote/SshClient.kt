package dev.anthracite.remote

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

internal data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: ByteArray?,
    val passphrase: String?,
)

internal sealed class ConnectOutcome {
    data class Connected(val fingerprint: String, val keyType: String) : ConnectOutcome()
    data class UnknownHostKey(val key: ByteArray, val fingerprint: String, val keyType: String) : ConnectOutcome()
    data class ChangedHostKey(
        val key: ByteArray,
        val fingerprint: String,
        val keyType: String,
        val savedFingerprint: String,
    ) : ConnectOutcome()
    data class AuthFailed(val message: String) : ConnectOutcome()
    data class Failed(val message: String) : ConnectOutcome()
}

internal data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val text: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * A single-session JSch facade.
 *
 * Session setup deliberately happens outside [stateLock], because network I/O can
 * block. The generation token makes a concurrent disconnect cancel both an
 * already-connected session and a session still negotiating its SSH banner.
 */
internal class SshClient {
    private val stateLock = Any()
    private val forwardingLock = Any()
    private var session: Session? = null
    private var connecting: Session? = null
    private var localPort: Int = -1
    private var generation = 0L

    fun isConnected(): Boolean = synchronized(stateLock) { session?.isConnected == true }

    fun localForwardPort(): Int = synchronized(stateLock) { localPort }

    fun connect(target: SshTarget, approvedKey: ByteArray?): ConnectOutcome {
        val prior = detachSessions()
        closeSessions(prior)
        val gate = HostKeyGate(approvedKey)
        val jsch = JSch()
        try {
            if (target.privateKey != null) {
                jsch.addIdentity(
                    "anthracite",
                    target.privateKey,
                    null,
                    target.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
                )
            }
        } catch (error: Exception) {
            return ConnectOutcome.Failed(error.message ?: "Could not load the SSH private key")
        }

        val created = try {
            jsch.getSession(target.username, target.host, target.port).apply {
                hostKeyRepository = gate
                setConfig(sessionConfig(target.privateKey != null))
                if (!target.password.isNullOrEmpty()) setPassword(target.password)
                userInfo = PasswordPrompts(target.password, target.passphrase)
                setServerAliveInterval(15_000)
                setServerAliveCountMax(3)
                timeout = CONNECT_TIMEOUT_MS
                setDaemonThread(true)
            }
        } catch (error: Exception) {
            return ConnectOutcome.Failed(error.message ?: "Could not create the SSH session")
        }

        val ticket = prior.generation
        if (!registerConnecting(created, ticket)) {
            created.disconnect()
            return ConnectOutcome.Failed("SSH connection cancelled")
        }
        return try {
            created.connect(CONNECT_TIMEOUT_MS)
            if (!promoteConnected(created, ticket)) {
                created.disconnect()
                ConnectOutcome.Failed("SSH connection cancelled")
            } else {
                ConnectOutcome.Connected(
                    gate.presented?.let { sha256Fingerprint(it) } ?: "",
                    gate.presented?.let { sshKeyType(it) } ?: "",
                )
            }
        } catch (error: JSchChangedHostKeyException) {
            created.disconnect()
            hostKeyOutcome(gate, approvedKey, changed = true, error.message)
        } catch (error: JSchUnknownHostKeyException) {
            created.disconnect()
            hostKeyOutcome(gate, approvedKey, changed = false, error.message)
        } catch (error: JSchException) {
            created.disconnect()
            connectFailure(error.message)
        } catch (error: Exception) {
            created.disconnect()
            ConnectOutcome.Failed(error.message ?: "SSH failed")
        } finally {
            clearConnecting(created)
        }
    }

    fun forward(remotePort: Int): Int = synchronized(forwardingLock) {
        require(remotePort in 1..65535) { "remote port is invalid" }
        val snapshot = currentSession()
        val current = snapshot.session ?: error("SSH is not connected")
        if (snapshot.port > 0) {
            runCatching { current.delPortForwardingL(LOOPBACK, snapshot.port) }
            synchronized(stateLock) {
                if (isCurrent(snapshot)) localPort = -1
            }
        }
        val assigned = current.setPortForwardingL(LOOPBACK, 0, LOOPBACK, remotePort)
        check(assigned in 1..65535) { "forwarder did not bind a local port" }
        val retained = synchronized(stateLock) {
            if (isCurrent(snapshot)) {
                localPort = assigned
                true
            } else {
                false
            }
        }
        if (!retained) {
            runCatching { current.delPortForwardingL(LOOPBACK, assigned) }
            error("SSH session was disconnected while creating the loopback forward")
        }
        assigned
    }

    /**
     * Verifies the remote WebUI through authenticated SSH before [forward] creates
     * a device-loopback listener. This closes the interval in which another app
     * could otherwise reach an unauthenticated remote control plane via a newly
     * created local forward.
     */
    fun probeRemoteLoopbackHttp(remotePort: Int, timeoutMs: Long = HTTP_PROBE_TIMEOUT_MS): RemoteHttpProbe {
        require(remotePort in 1..65535) { "remote port is invalid" }
        val current = currentSession().session ?: error("SSH is not connected")
        val channel = current.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost(LOOPBACK)
        channel.setPort(remotePort)
        channel.setOrgIPAddress(LOOPBACK)
        channel.setOrgPort(0)
        val input = channel.inputStream
        val output = channel.outputStream
        val response = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            channel.connect(minOf(CONNECT_TIMEOUT_MS.toLong(), timeoutMs).toInt())
            output.write(
                "GET / HTTP/1.1\r\nHost: $LOOPBACK\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                drain(input, response, buffer)
                val complete = completeHttpHeaders(response.toByteArray())
                if (complete > 0) return parseHttpProbe(response.toByteArray(), complete)
                if (response.size() > HTTP_HEADER_CAP || (channel.isClosed && input.available() == 0)) break
                Thread.sleep(20)
            }
            return RemoteHttpProbe(-1, emptyList())
        } finally {
            channel.disconnect()
        }
    }

    /**
     * Runs one command and returns its complete output.
     *
     * [onOutput] is an additive observer for long-running commands: it receives
     * raw byte chunks on the calling (I/O) thread as they arrive, before the
     * caps and result assembly below. It never changes the returned
     * [ExecResult], the output caps, or the timeout behavior, and an exception
     * thrown by the listener cannot break the channel loop. Redacting secrets
     * from observed chunks is the caller's responsibility.
     */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000, onOutput: ((ByteArray) -> Unit)? = null): ExecResult {
        val current = currentSession().session ?: error("SSH is not connected")
        val channel = current.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val remoteOut = channel.inputStream
        val remoteErr = channel.errStream
        val remoteIn = if (stdin != null) channel.outputStream else null
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            remoteIn?.use {
                it.write(stdin)
                it.flush()
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val buffer = ByteArray(4096)
            while (!channel.isClosed) {
                drain(remoteOut, stdout, buffer, onOutput)
                drain(remoteErr, stderr, buffer, onOutput)
                if (stdout.size() > OUTPUT_CAP || stderr.size() > OUTPUT_CAP) {
                    channel.disconnect()
                    error("remote output exceeded ${OUTPUT_CAP} bytes")
                }
                if (System.nanoTime() > deadline) {
                    channel.disconnect()
                    error("remote command timed out")
                }
                Thread.sleep(30)
            }
            drain(remoteOut, stdout, buffer, onOutput)
            drain(remoteErr, stderr, buffer, onOutput)
            var status = channel.exitStatus
            var spins = 0
            while (status == -1 && spins < 25) {
                Thread.sleep(20)
                status = channel.exitStatus
                spins++
            }
            return ExecResult(
                status,
                String(stdout.toByteArray(), Charsets.UTF_8),
                String(stderr.toByteArray(), Charsets.UTF_8),
            )
        } finally {
            channel.disconnect()
        }
    }

    fun upload(bytes: ByteArray, remotePath: String) {
        val current = currentSession().session ?: error("SSH is not connected")
        val sftp = current.openChannel("sftp") as ChannelSftp
        sftp.connect(CONNECT_TIMEOUT_MS)
        try {
            sftp.put(ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE)
            sftp.chmod(448, remotePath)
        } finally {
            sftp.disconnect()
        }
    }

    /** Safe to invoke concurrently with connect, forwarding, or channel I/O. */
    fun disconnect() {
        closeSessions(detachSessions())
    }

    private fun connectFailure(message: String?): ConnectOutcome {
        val value = message ?: "SSH failed"
        return if (value.contains("Auth fail", ignoreCase = true) || value.contains("Auth cancel", ignoreCase = true)) {
            ConnectOutcome.AuthFailed(value)
        } else {
            ConnectOutcome.Failed(value)
        }
    }

    private fun detachSessions(): SessionState = synchronized(stateLock) {
        generation += 1
        SessionState(session, connecting, localPort, generation).also {
            session = null
            connecting = null
            localPort = -1
        }
    }

    private fun closeSessions(state: SessionState) {
        val active = state.session
        if (active != null && state.port > 0) {
            runCatching { active.delPortForwardingL(LOOPBACK, state.port) }
        }
        active?.disconnect()
        if (state.connecting != null && state.connecting !== active) state.connecting.disconnect()
    }

    private fun registerConnecting(created: Session, ticket: Long): Boolean = synchronized(stateLock) {
        if (ticket != generation) return@synchronized false
        connecting = created
        true
    }

    private fun promoteConnected(created: Session, ticket: Long): Boolean = synchronized(stateLock) {
        if (ticket != generation || connecting !== created) return@synchronized false
        connecting = null
        session = created
        true
    }

    private fun clearConnecting(created: Session) {
        synchronized(stateLock) {
            if (connecting === created) connecting = null
        }
    }

    private fun currentSession(): SessionState = synchronized(stateLock) {
        val active = session
        check(active?.isConnected == true) { "SSH is not connected" }
        SessionState(active, null, localPort, generation)
    }

    private fun isCurrent(snapshot: SessionState): Boolean =
        session === snapshot.session && generation == snapshot.generation && session?.isConnected == true

    private fun hostKeyOutcome(
        gate: HostKeyGate,
        approvedKey: ByteArray?,
        changed: Boolean,
        fallback: String?,
    ): ConnectOutcome {
        val presented = gate.presented
            ?: return ConnectOutcome.Failed(fallback ?: "host key was not presented")
        val fingerprint = sha256Fingerprint(presented)
        val type = sshKeyType(presented)
        return if (changed || (approvedKey != null && !approvedKey.contentEquals(presented))) {
            ConnectOutcome.ChangedHostKey(presented, fingerprint, type, approvedKey?.let { sha256Fingerprint(it) } ?: "")
        } else {
            ConnectOutcome.UnknownHostKey(presented, fingerprint, type)
        }
    }

    private fun completeHttpHeaders(bytes: ByteArray): Int {
        for (index in 3 until bytes.size) {
            if (
                bytes[index - 3] == '\r'.code.toByte() &&
                bytes[index - 2] == '\n'.code.toByte() &&
                bytes[index - 1] == '\r'.code.toByte() &&
                bytes[index] == '\n'.code.toByte()
            ) return index + 1
        }
        for (index in 1 until bytes.size) {
            if (bytes[index - 1] == '\n'.code.toByte() && bytes[index] == '\n'.code.toByte()) return index + 1
        }
        return -1
    }

    private fun parseHttpProbe(bytes: ByteArray, headerEnd: Int): RemoteHttpProbe {
        val lines = String(bytes, 0, headerEnd, Charsets.US_ASCII).split("\r\n", "\n")
        val status = lines.firstOrNull()
            ?.trim()
            ?.let { Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s|$)").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
            ?: -1
        val challenges = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || !line.substring(0, separator).equals("WWW-Authenticate", ignoreCase = true)) null
            else line.substring(separator + 1).trim()
        }
        return RemoteHttpProbe(status, challenges)
    }

    private fun drain(
        input: java.io.InputStream,
        output: ByteArrayOutputStream,
        buffer: ByteArray,
        onOutput: ((ByteArray) -> Unit)? = null,
    ) {
        while (input.available() > 0) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            if (onOutput != null && read > 0) {
                // Observation must never break the command loop; the listener
                // receives a private copy because [buffer] is reused.
                runCatching { onOutput(buffer.copyOf(read)) }
            }
        }
    }

    private fun sessionConfig(hasKey: Boolean): Properties = Properties().apply {
        setProperty("StrictHostKeyChecking", "yes")
        setProperty("HashKnownHosts", "no")
        setProperty("enable_auth_none", "no")
        setProperty(
            "PreferredAuthentications",
            if (hasKey) "publickey,keyboard-interactive,password" else "keyboard-interactive,password",
        )
    }

    private class HostKeyGate(private val approved: ByteArray?) : HostKeyRepository {
        var presented: ByteArray? = null

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            presented = key.copyOf()
            return when (hostKeyDecision(approved, key)) {
                HostKeyDecision.MATCH -> HostKeyRepository.OK
                HostKeyDecision.UNKNOWN -> HostKeyRepository.NOT_INCLUDED
                HostKeyDecision.CHANGED -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "anthracite"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class PasswordPrompts(
        private val password: String?,
        private val passphrase: String?,
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = !password.isNullOrEmpty()
        override fun getPassphrase(): String? = passphrase
        override fun promptPassphrase(message: String?): Boolean = !passphrase.isNullOrEmpty()
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            val secret = password ?: return null
            return Array(prompt?.size ?: 1) { secret }
        }
    }

    private data class SessionState(
        val session: Session?,
        val connecting: Session?,
        val port: Int,
        val generation: Long,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val HTTP_PROBE_TIMEOUT_MS = 5_000L
        const val HTTP_HEADER_CAP = 16 * 1024
        const val OUTPUT_CAP = 200_000
    }
}
