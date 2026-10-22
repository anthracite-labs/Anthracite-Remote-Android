package dev.anthracite.remote

import com.jcraft.jsch.JSch
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises JSch against an in-process SSH server. This is not an Android device
 * test. Ed25519 and X25519 are forced through JSch's Bouncy Castle classes, which
 * is the path Android takes because it reports Java 8 and drops multi-release jars.
 */
class SshSessionTest {
    @Test
    fun `bouncy castle lightweight algorithms initialize without a jce provider`() {
        val signature = com.jcraft.jsch.bc.SignatureEd25519()
        signature.init()
        val exchange = com.jcraft.jsch.bc.XDH()
        exchange.init("X25519", 32)
        assertEquals(32, exchange.q.size)
    }

    @Test
    fun `password login, changed host key, and loopback forward`() {
        withAndroidAlgorithms {
            val attempts = AtomicInteger()
            val firstKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val server = sshServer(firstKey, attempts)
            server.start()
            val client = SshClient()
            try {
                val port = server.port
                val unknown = client.connect(passwordTarget(port), null)
                assertTrue("expected unknown host key, got $unknown", unknown is ConnectOutcome.UnknownHostKey)
                assertEquals(0, attempts.get())
                val presented = (unknown as ConnectOutcome.UnknownHostKey).key

                val connected = client.connect(passwordTarget(port), presented)
                assertTrue("password login failed: $connected", connected is ConnectOutcome.Connected)
                assertTrue(attempts.get() > 0)
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
                            assertEquals(7, accepted.getInputStream().read())
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
                val replacement = sshServer(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), attempts)
                replacement.start()
                try {
                    val changed = client.connect(passwordTarget(replacement.port), presented)
                    assertTrue("changed host key was accepted: $changed", changed is ConnectOutcome.ChangedHostKey)
                    assertEquals(beforeChangedKey, attempts.get())
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
    fun `private key login is accepted only for the matching key`() {
        val dir = Files.createTempDirectory("anthracite-ssh")
        val keyPath = dir.resolve("id_ed25519")
        val generated = ProcessBuilder(
            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", keyPath.toString(),
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
            val server = sshServer(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), attempts)
            server.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
                username == USER && KeyUtils.compareKeys(key, clientKey.public)
            }
            server.start()
            val client = SshClient()
            try {
                val unknown = client.connect(keyTarget(server.port, privateKey), null)
                assertTrue(unknown is ConnectOutcome.UnknownHostKey)
                val approved = (unknown as ConnectOutcome.UnknownHostKey).key
                val connected = client.connect(keyTarget(server.port, privateKey), approved)
                assertTrue("key login failed: $connected", connected is ConnectOutcome.Connected)
                client.disconnect()

                val other = Files.createTempDirectory("anthracite-ssh-other")
                val otherKey = other.resolve("id_ed25519")
                val otherGen = ProcessBuilder(
                    "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", otherKey.toString(),
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

    private fun sshServer(hostKey: java.security.KeyPair, attempts: AtomicInteger): SshServer {
        val server = SshServer.setUpDefaultServer()
        server.host = LOOPBACK
        server.port = 0
        server.keyPairProvider = object : KeyPairProvider {
            override fun loadKeys(session: SessionContext?): Iterable<java.security.KeyPair> = listOf(hostKey)
        }
        server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            attempts.incrementAndGet()
            username == USER && password == PASSWORD
        }
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        return server
    }

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

    private fun assertLoopbackOnly(port: Int) {
        val process = ProcessBuilder("ss", "-H", "-ltn").start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        val lines = output.lines().filter { it.contains(":$port") || it.contains(":$port ") }
        assertTrue("no listener for $port in [$output]", lines.isNotEmpty())
        assertTrue(lines.any { it.contains("$LOOPBACK:$port") })
        assertFalse(lines.any { it.contains("0.0.0.0:$port") || it.contains("*:$port") })
    }

    private fun withAndroidAlgorithms(block: () -> Unit) {
        val keys = listOf("kex", "server_host_key", "ssh-ed25519", "xdh")
        val saved = keys.associateWith { JSch.getConfig(it) }
        JSch.setConfig("kex", "curve25519-sha256,curve25519-sha256@libssh.org")
        JSch.setConfig("server_host_key", "ssh-ed25519")
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
