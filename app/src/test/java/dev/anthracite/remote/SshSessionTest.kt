package dev.anthracite.remote

import com.jcraft.jsch.JSch
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Exercises JSch against an in-process Apache MINA SSHD server. This is a JVM
 * integration test, not an Android device test. The server deliberately uses a
 * JDK ECDSA host key: Apache MINA's optional Ed25519 provider cannot reliably
 * advertise a JDK EdEC key in every JDK/provider combination. The Android path
 * is still exercised with Bouncy Castle X25519, and Ed25519 signing is verified
 * below without registering Bouncy Castle as a JCE provider.
 */
class SshSessionTest {
    @Test
    fun `bouncy castle lightweight Ed25519 and X25519 work without a jce provider`() {
        val seed = ByteArray(32) { it.toByte() }
        val payload = "anthracite Android SSH".toByteArray()
        val signer = com.jcraft.jsch.bc.SignatureEd25519()
        signer.init()
        signer.setPrvKey(seed)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = com.jcraft.jsch.bc.SignatureEd25519()
        verifier.init()
        verifier.setPubKey(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
        verifier.update(payload)
        // JSch's verifier receives the SSH wire signature (algorithm and raw
        // signature are both length-prefixed), whereas sign() exposes the raw
        // Ed25519 bytes used to build that wire value.
        val algorithm = "ssh-ed25519".toByteArray()
        val wireSignature = ByteBuffer.allocate(8 + algorithm.size + signature.size)
            .putInt(algorithm.size)
            .put(algorithm)
            .putInt(signature.size)
            .put(signature)
            .array()
        assertTrue(verifier.verify(wireSignature))

        val exchange = com.jcraft.jsch.bc.XDH()
        exchange.init("X25519", 32)
        assertEquals(32, exchange.q.size)
    }

    @Test
    fun `password login refuses unknown and changed host keys then creates a loopback forward`() {
        withAndroidAlgorithms {
            val attempts = AtomicInteger()
            val server = sshServer(ecdsaHostKey(), attempts)
            server.start()
            val client = SshClient()
            try {
                val port = server.port
                val unknown = client.connect(passwordTarget(port), null)
                assertTrue("expected unknown host key, got $unknown", unknown is ConnectOutcome.UnknownHostKey)
                // The host-key decision occurs before any authentication callback.
                assertEquals("unknown host key reached the password authenticator", 0, attempts.get())
                val presented = (unknown as ConnectOutcome.UnknownHostKey).key

                val connected = client.connect(passwordTarget(port), presented)
                assertTrue("password login failed: $connected", connected is ConnectOutcome.Connected)
                assertTrue("password authenticator was not invoked", attempts.get() > 0)
                val remote = ServerSocket()
                remote.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
                remote.soTimeout = 5_000
                try {
                    val assigned = client.forward(remote.localPort)
                    assertLoopbackOnly(assigned)
                    Socket(LOOPBACK, assigned).use { local ->
                        local.soTimeout = 5_000
                        remote.accept().use { accepted ->
                            local.getOutputStream().write(byteArrayOf(7))
                            local.getOutputStream().flush()
                            assertEquals("loopback forward did not deliver the test byte", 7, accepted.getInputStream().read())
                        }
                    }
                } finally {
                    remote.close()
                }
                client.disconnect()

                val wrong = client.connect(passwordTarget(port, "not-the-password"), presented)
                assertTrue("wrong password was accepted: $wrong", wrong is ConnectOutcome.AuthFailed)

                server.stop(true)
                val beforeChangedKey = attempts.get()
                val replacement = sshServer(ecdsaHostKey(), attempts)
                replacement.start()
                try {
                    val changed = client.connect(passwordTarget(replacement.port), presented)
                    assertTrue("changed host key was accepted: $changed", changed is ConnectOutcome.ChangedHostKey)
                    assertEquals("changed host key reached the password authenticator", beforeChangedKey, attempts.get())
                } finally {
                    replacement.stop(true)
                }
            } finally {
                client.disconnect()
                server.stop(true)
            }
        }
    }

    @Test
    fun `private key login is accepted only for the matching modern rsa key`() {
        val dir = Files.createTempDirectory("anthracite-ssh")
        val keyPath = dir.resolve("id_rsa")
        val generated = ProcessBuilder(
            "ssh-keygen", "-q", "-t", "rsa", "-b", "3072", "-N", "", "-f", keyPath.toString(),
        ).start()
        assertEquals(0, generated.waitFor())
        val privateKey = Files.readAllBytes(keyPath)
        val pairs = SecurityUtils.loadKeyPairIdentities(
            null,
            null,
            ByteArrayInputStream(privateKey),
            null,
        ) ?: error("OpenSSH private key was not parsed")
        val clientKey = pairs.iterator().next()
        withAndroidAlgorithms {
            val attempts = AtomicInteger()
            val server = sshServer(ecdsaHostKey(), attempts)
            server.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
                username == USER && KeyUtils.compareKeys(key, clientKey.public)
            }
            // Do not inherit MINA's permissive test-server defaults: this case
            // must accept only a valid public-key signature.
            server.userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            server.start()
            val client = SshClient()
            try {
                val unknown = client.connect(keyTarget(server.port, privateKey), null)
                assertTrue("expected unknown host key, got $unknown", unknown is ConnectOutcome.UnknownHostKey)
                val approved = (unknown as ConnectOutcome.UnknownHostKey).key
                val connected = client.connect(keyTarget(server.port, privateKey), approved)
                assertTrue("key login failed: $connected", connected is ConnectOutcome.Connected)
                client.disconnect()

                val other = Files.createTempDirectory("anthracite-ssh-other")
                val otherKey = other.resolve("id_rsa")
                val otherGen = ProcessBuilder(
                    "ssh-keygen", "-q", "-t", "rsa", "-b", "3072", "-N", "", "-f", otherKey.toString(),
                ).start()
                assertEquals(0, otherGen.waitFor())
                val rejected = client.connect(keyTarget(server.port, Files.readAllBytes(otherKey)), approved)
                assertTrue("unexpected key was accepted: $rejected", rejected is ConnectOutcome.AuthFailed)
            } finally {
                client.disconnect()
                server.stop(true)
            }
        }
    }

    @Test
    fun `basic auth readiness is checked through SSH before a local forward exists`() {
        withAndroidAlgorithms {
            val server = sshServer(ecdsaHostKey(), AtomicInteger())
            val remote = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
            server.start()
            val client = SshClient()
            val responder = thread(start = true, isDaemon = true, name = "basic-auth-webui") {
                remote.accept().use { socket ->
                    socket.soTimeout = 5_000
                    // The complete request body is irrelevant to this security
                    // check; accepting the direct-tcpip connection proves that no
                    // device-loopback listener was required for the probe.
                    socket.getInputStream().read()
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 401 Unauthorized\r\n" +
                                    "WWW-Authenticate: Basic realm=\"OpenCode\"\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        output.flush()
                    }
                }
            }
            try {
                val unknown = client.connect(passwordTarget(server.port), null) as? ConnectOutcome.UnknownHostKey
                assertNotNull("server did not present a host key", unknown)
                assertTrue(client.connect(passwordTarget(server.port), unknown!!.key) is ConnectOutcome.Connected)
                val probe = client.probeRemoteLoopbackHttp(remote.localPort)
                assertTrue(authenticatedTunnelReady(probe.statusCode, probe.authenticateHeaders))
                assertEquals(-1, client.localForwardPort())
                responder.join(5_000)
                assertFalse("remote Basic-auth responder did not finish", responder.isAlive)
            } finally {
                client.disconnect()
                remote.close()
                server.stop(true)
            }
        }
    }

    @Test
    fun `approved session terminates and reconnects with the same host key`() {
        withAndroidAlgorithms {
            val server = sshServer(ecdsaHostKey(), AtomicInteger())
            server.start()
            val client = SshClient()
            try {
                val unknown = client.connect(passwordTarget(server.port), null) as? ConnectOutcome.UnknownHostKey
                assertNotNull("server did not present a host key", unknown)
                val approved = unknown!!.key
                assertTrue(client.connect(passwordTarget(server.port), approved) is ConnectOutcome.Connected)
                client.disconnect()
                assertFalse(client.isConnected())
                assertEquals(-1, client.localForwardPort())
                assertTrue(client.connect(passwordTarget(server.port), approved) is ConnectOutcome.Connected)
            } finally {
                client.disconnect()
                server.stop(true)
            }
        }
    }

    @Test
    fun `explicit disconnect cancels an in flight SSH handshake`() {
        val listener = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        val accepted = CountDownLatch(1)
        val releasePeer = CountDownLatch(1)
        val result = AtomicReference<ConnectOutcome?>()
        val peer = thread(start = true, isDaemon = true, name = "stalled-ssh-peer") {
            try {
                listener.accept().use {
                    accepted.countDown()
                    releasePeer.await(5, TimeUnit.SECONDS)
                }
            } finally {
                listener.close()
            }
        }
        val client = SshClient()
        val connector = thread(start = true, name = "stalled-ssh-client") {
            result.set(client.connect(passwordTarget(listener.localPort), null))
        }
        try {
            assertTrue("client did not begin the SSH handshake", accepted.await(5, TimeUnit.SECONDS))
            client.disconnect()
            releasePeer.countDown()
            connector.join(5_000)
            assertFalse("connect did not stop after disconnect", connector.isAlive)
            assertFalse(client.isConnected())
            assertTrue("expected cancelled handshake failure, got ${result.get()}", result.get() is ConnectOutcome.Failed)
        } finally {
            releasePeer.countDown()
            listener.close()
            connector.join(1_000)
            peer.join(1_000)
            client.disconnect()
        }
    }

    @Test
    fun `exec streams output lines to the additive listener without changing the result`() {
        val stderrLine = "warn: streamed-stderr"
        val stdoutLines = listOf("step: download", "step: verify-hash", "ok: done")
        withAndroidAlgorithms {
            val server = sshServer(ecdsaHostKey(), AtomicInteger())
            server.commandFactory = CommandFactory { _, _ -> streamingCommand(stdoutLines, stderrLine) }
            server.start()
            val client = SshClient()
            try {
                val unknown = client.connect(passwordTarget(server.port), null) as? ConnectOutcome.UnknownHostKey
                assertNotNull("server did not present a host key", unknown)
                assertTrue(client.connect(passwordTarget(server.port), unknown!!.key) is ConnectOutcome.Connected)

                val stream = LineStream()
                val received = CopyOnWriteArrayList<String>()
                val result = client.exec("stream-test", timeoutMs = 15_000) { chunk -> received.addAll(stream.accept(chunk)) }
                received.addAll(stream.flush())

                assertEquals(0, result.exitCode)
                assertEquals(
                    "streamed stdout lines in order",
                    stdoutLines,
                    received.filterNot { it == stderrLine },
                )
                assertTrue("streamed stderr line", received.contains(stderrLine))
                stdoutLines.forEach { assertTrue("result stdout is complete", result.stdout.contains(it)) }
                assertTrue("result stderr is complete", result.stderr.contains(stderrLine))

                // The default (no listener) path returns an identical result.
                val silent = client.exec("stream-test", timeoutMs = 15_000)
                assertEquals(result.stdout, silent.stdout)
                assertEquals(result.stderr, silent.stderr)
                assertEquals(result.exitCode, silent.exitCode)
            } finally {
                client.disconnect()
                server.stop(true)
            }
        }
    }

    private fun streamingCommand(stdoutLines: List<String>, stderrLine: String) = object : Command {
        private var output: OutputStream? = null
        private var error: OutputStream? = null
        private var exit: ExitCallback? = null

        override fun setInputStream(`in`: InputStream) = Unit
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) { error = err }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channelSession: ChannelSession?, env: Environment?) {
            val out = output
            if (out == null) {
                exit?.onExit(1)
                return
            }
            stdoutLines.forEach { line -> out.write((line + "\n").toByteArray(Charsets.UTF_8)) }
            out.flush()
            error?.let { err ->
                err.write((stderrLine + "\n").toByteArray(Charsets.UTF_8))
                err.flush()
            }
            exit?.onExit(0)
        }

        override fun destroy(channelSession: ChannelSession?) = Unit
    }

    private fun sshServer(hostKey: KeyPair, attempts: AtomicInteger): SshServer {
        val server = SshServer.setUpDefaultServer()
        server.host = LOOPBACK
        server.port = 0
        server.keyPairProvider = KeyPairProvider.wrap(hostKey)
        server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            attempts.incrementAndGet()
            username == USER && password == PASSWORD
        }
        // SshServer.setUpDefaultServer is intentionally convenient for examples
        // and may offer its built-in auth methods. Restrict this harness to the
        // mechanism under test so a no-auth/default method cannot make a password
        // assertion pass without ever consulting PasswordAuthenticator.
        server.userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        return server
    }

    private fun ecdsaHostKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun passwordTarget(port: Int, password: String = PASSWORD) = SshTarget(
        host = LOOPBACK,
        port = port,
        username = USER,
        password = password,
        privateKey = null,
        passphrase = null,
    )

    private fun keyTarget(port: Int, privateKey: ByteArray) = SshTarget(
        host = LOOPBACK,
        port = port,
        username = USER,
        password = null,
        privateKey = privateKey,
        passphrase = null,
    )

    @Test
    fun `ss listener parsing distinguishes loopback, IPv4 mapped IPv6, and wildcard bindings`(){
        val port = 43121
        assertEquals(LOOPBACK, parseSsLocalAddress("LISTEN 0 50 127.0.0.1:43121 0.0.0.0:*", port))
        assertEquals("::1", parseSsLocalAddress("LISTEN 0 50 [::1]:43121 [::]:*", port))
        assertEquals("::ffff:127.0.0.1", parseSsLocalAddress("LISTEN 0 50 [::ffff:127.0.0.1]:43121 *:*", port))
        assertEquals("0.0.0.0", parseSsLocalAddress("LISTEN 0 50 0.0.0.0:43121 0.0.0.0:*", port))
        assertEquals("::", parseSsLocalAddress("LISTEN 0 50 [::]:43121 [::]:*", port))
        assertEquals(null, parseSsLocalAddress("LISTEN 0 50 127.0.0.1:43122 0.0.0.0:*", port))
    }

    private fun assertLoopbackOnly(port: Int) {
        val process = ProcessBuilder("ss", "-H", "-ltn").start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("ss command failed", 0, process.waitFor())

        // 'ss' may show 127.0.0.1 as [::ffff:127.0.0.1] on Linux.
        // Inspect the Local Address column, not text fragments from other columns.
        val addresses = output.lineSequence().mapNotNull { parseSsLocalAddress(it, port) }.toList()
        assertTrue("no listener for $port in [$output]", addresses.isNotEmpty())
        val loopbacks = setOf(LOOPBACK, "::1", "::ffff:$LOOPBACK")
        assertTrue("listener was exposed beyond loopback: $addresses", addresses.all { it in loopbacks })
    }

    private fun parseSsLocalAddress(line: String, port: Int): String? {
        val local = line.trim().split(Regex("\\s+")).getOrNull(3) ?: return null
        val suffix = ":$port"
        if (!local.endsWith(suffix)) return null
        return local.removeSuffix(suffix).removePrefix("[").removeSuffix("]").lowercase()
    }

    private fun withAndroidAlgorithms(block: () -> Unit) {
        val keys = listOf("kex", "server_host_key", "ssh-ed25519", "xdh")
        val saved = keys.associateWith { JSch.getConfig(it) }
        JSch.setConfig("kex", "curve25519-sha256,curve25519-sha256@libssh.org")
        JSch.setConfig("server_host_key", "ecdsa-sha2-nistp256")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        try {
            block()
        } finally {
            saved.forEach { (key, value) ->
                if (value != null) JSch.setConfig(key, value)
            }
        }
    }

    private companion object {
        const val USER = "tester"
        const val PASSWORD = "CorrectHorse1"
    }
}
