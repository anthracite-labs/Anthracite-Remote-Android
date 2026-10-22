package dev.anthracite.remote

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

internal class SshClient {
    private var session: Session? = null
    private var localPort: Int = -1

    fun isConnected(): Boolean = session?.isConnected == true

    fun localForwardPort(): Int = localPort

    fun connect(target: SshTarget, approvedKey: ByteArray?): ConnectOutcome {
        disconnect()
        val gate = HostKeyGate(approvedKey)
        val jsch = JSch()
        if (target.privateKey != null) {
            jsch.addIdentity(
                "anthracite",
                target.privateKey,
                null,
                target.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        }
        val created = jsch.getSession(target.username, target.host, target.port)
        created.hostKeyRepository = gate
        created.setConfig(sessionConfig(target.privateKey != null))
        if (!target.password.isNullOrEmpty()) created.setPassword(target.password)
        created.userInfo = PasswordPrompts(target.password, target.passphrase)
        created.setServerAliveInterval(15_000)
        created.setServerAliveCountMax(3)
        created.timeout = CONNECT_TIMEOUT_MS
        created.setDaemonThread(true)
        return try {
            created.connect(CONNECT_TIMEOUT_MS)
            session = created
            ConnectOutcome.Connected(
                gate.presented?.let { sha256Fingerprint(it) } ?: "",
                gate.presented?.let { sshKeyType(it) } ?: "",
            )
        } catch (error: JSchChangedHostKeyException) {
            created.disconnect()
            hostKeyOutcome(gate, approvedKey, changed = true, error.message)
        } catch (error: JSchUnknownHostKeyException) {
            created.disconnect()
            hostKeyOutcome(gate, approvedKey, changed = false, error.message)
        } catch (error: JSchException) {
            created.disconnect()
            val message = error.message ?: "SSH failed"
            if (message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true)) {
                ConnectOutcome.AuthFailed(message)
            } else {
                ConnectOutcome.Failed(message)
            }
        }
    }

    fun forward(remotePort: Int): Int {
        val current = session ?: error("SSH is not connected")
        if (localPort > 0) {
            runCatching { current.delPortForwardingL(LOOPBACK, localPort) }
            localPort = -1
        }
        val assigned = current.setPortForwardingL(LOOPBACK, 0, LOOPBACK, remotePort)
        check(assigned in 1..65535) { "forwarder did not bind a local port" }
        localPort = assigned
        return assigned
    }

    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000): ExecResult {
        val current = session ?: error("SSH is not connected")
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
                drain(remoteOut, stdout, buffer)
                drain(remoteErr, stderr, buffer)
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
            drain(remoteOut, stdout, buffer)
            drain(remoteErr, stderr, buffer)
            var status = channel.exitStatus
            var spins = 0
            while (status == -1 && spins < 25) {
                Thread.sleep(20)
                status = channel.exitStatus
                spins++
            }
            return ExecResult(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            channel.disconnect()
        }
    }

    fun upload(bytes: ByteArray, remotePath: String) {
        val current = session ?: error("SSH is not connected")
        val sftp = current.openChannel("sftp") as ChannelSftp
        sftp.connect(CONNECT_TIMEOUT_MS)
        try {
            sftp.put(ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE)
            sftp.chmod(448, remotePath)
        } finally {
            sftp.disconnect()
        }
    }

    fun disconnect() {
        val current = session
        session = null
        if (current != null && localPort > 0) {
            runCatching { current.delPortForwardingL(LOOPBACK, localPort) }
        }
        localPort = -1
        current?.disconnect()
    }

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

    private fun drain(input: java.io.InputStream, output: ByteArrayOutputStream, buffer: ByteArray) {
        while (input.available() > 0) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val OUTPUT_CAP = 200_000
    }
}
