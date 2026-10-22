package dev.anthracite.remote.files

import dev.anthracite.remote.ConnectOutcome
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.SshTarget
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SFTP behaviour against a real in-process server (Apache MINA SSHD), not a mock.
 *
 * What matters here is exactly what a phone cannot test on a desk: the byte cap
 * is enforced while streaming, uploads land under a unique temporary name and
 * never leave litter, an existing file is not replaced without consent, and a
 * symlink is removed as a link instead of being followed.
 */
class SftpBrowserTest {
    private lateinit var server: SshServer
    private lateinit var root: Path
    private lateinit var client: SshClient
    private lateinit var browser: SftpBrowser

    @Before
    fun setUp() {
        root = Files.createTempDirectory("anthracite-sftp-root")
        server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        // Same key type as SshSessionTest: a JDK ECDSA key is advertised
        // consistently by MINA on every JDK, without an optional provider.
        val hostKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        server.keyPairProvider = KeyPairProvider.wrap(hostKey)
        server.passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == USER && password == PASSWORD
        }
        server.userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(root)
        server.start()

        client = SshClient()
        val target = SshTarget(
            host = "127.0.0.1",
            port = server.port,
            username = USER,
            password = PASSWORD,
            privateKey = null,
            passphrase = null,
        )
        val first = client.connect(target, null)
        assertTrue("unexpected first handshake result: $first", first is ConnectOutcome.UnknownHostKey)
        val approved = client.connect(target, (first as ConnectOutcome.UnknownHostKey).key)
        assertTrue("connection failed: $approved", approved is ConnectOutcome.Connected)
        browser = SftpBrowser(client)
    }

    @After
    fun tearDown() {
        runCatching { client.disconnect() }
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    @Test
    fun `listing returns directories first and hides nothing silently`() {
        Files.write(root.resolve("beta.txt"), "b".toByteArray())
        Files.createDirectory(root.resolve("alpha"))
        Files.createDirectory(root.resolve("zeta"))
        Files.write(root.resolve("alpha.txt"), "a".toByteArray())

        val entries = browser.list("/")
        assertEquals(listOf("alpha", "zeta", "alpha.txt", "beta.txt"), entries.map { it.name })
        assertTrue(entries.first().directory)
        assertEquals("/alpha.txt", entries.first { it.name == "alpha.txt" }.path)
    }

    @Test
    fun `download refuses a file larger than the cap before reading it`() {
        Files.write(root.resolve("big.bin"), ByteArray(4096) { 7 })
        val failure = assertThrows(RemoteFileException::class.java) {
            browser.download("/big.bin", maxBytes = 1024)
        }
        assertTrue(failure.message.orEmpty().contains("transfer limit"))
    }

    @Test
    fun `download returns the bytes when the file fits`() {
        val payload = ByteArray(900) { (it % 251).toByte() }
        Files.write(root.resolve("fits.bin"), payload)
        assertArrayEquals(payload, browser.download("/fits.bin", maxBytes = 1024))
    }

    @Test
    fun `upload lands the content and leaves no temporary file behind`() {
        val payload = "hello from the phone".toByteArray()
        browser.upload("/uploaded.txt", payload, overwrite = false)

        assertArrayEquals(payload, Files.readAllBytes(root.resolve("uploaded.txt")))
        val leftovers = root.toFile().listFiles()
            ?.filter { it.name.contains("anthracite.tmp") }
            .orEmpty()
        assertTrue("temporary upload files were left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `concurrent uploads use separate temporary names and clean them up`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..2).map { number ->
                pool.submit<Boolean> {
                    start.await()
                    browser.upload("/concurrent-$number.txt", ByteArray(128 * 1024) { number.toByte() }, false)
                    true
                }
            }
            start.countDown()
            tasks.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
            for (number in 1..2) {
                assertArrayEquals(ByteArray(128 * 1024) { number.toByte() },
                    Files.readAllBytes(root.resolve("concurrent-$number.txt")))
            }
            assertTrue(root.toFile().listFiles().orEmpty().none { it.name.endsWith(".anthracite.tmp") })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `long unicode destination does not make the temporary name exceed filesystem byte limit`() {
        val name = "中".repeat(80) // 240 UTF-8 bytes, a legal ext4 filename
        browser.upload("/$name", byteArrayOf(42), overwrite = false)
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(root.resolve(name)))
        assertTrue(root.toFile().listFiles().orEmpty().none { it.name.endsWith(".anthracite.tmp") })
    }

    @Test
    fun `upload refuses to replace an existing file without consent`() {
        Files.write(root.resolve("keep.txt"), "original".toByteArray())
        assertThrows(RemoteFileException::class.java) {
            browser.upload("/keep.txt", "replacement".toByteArray(), overwrite = false)
        }
        assertEquals("original", String(Files.readAllBytes(root.resolve("keep.txt"))))
    }

    @Test
    fun `upload replaces an existing file when overwrite is allowed`() {
        Files.write(root.resolve("replace.txt"), "original".toByteArray())
        browser.upload("/replace.txt", "replacement".toByteArray(), overwrite = true)
        assertEquals("replacement", String(Files.readAllBytes(root.resolve("replace.txt"))))
    }

    @Test
    fun `deleting a symlink removes the link and not its target`() {
        val outside = Files.createTempDirectory("anthracite-sftp-outside")
        try {
            Files.write(outside.resolve("precious.txt"), "keep me".toByteArray())
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("precious.txt"))

            browser.delete("/link.txt", recursive = false)

            assertFalse(Files.exists(root.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue("the link target must survive", Files.exists(outside.resolve("precious.txt")))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes a tree but not a sibling`() {
        Files.createDirectories(root.resolve("tree/inner"))
        Files.write(root.resolve("tree/inner/file.txt"), "x".toByteArray())
        Files.write(root.resolve("sibling.txt"), "y".toByteArray())

        browser.delete("/tree", recursive = true)

        assertFalse(Files.exists(root.resolve("tree")))
        assertTrue(Files.exists(root.resolve("sibling.txt")))
    }

    @Test
    fun `the streaming cap aborts before the buffer grows past the limit`() {
        val oversized = ByteArray(64 * 1024) { 1 }
        assertThrows(RemoteFileException::class.java) {
            readBounded(ByteArrayInputStream(oversized), maxBytes = 4096)
        }
        val exact = ByteArray(4096) { 1 }
        assertArrayEquals(exact, readBounded(ByteArrayInputStream(exact), maxBytes = 4096L))
    }

    @Test
    fun `a stream that stalls with zero-length reads still terminates at the cap`() {
        // A read() of 0 is legal for some streams; it must not spin forever or
        // bypass the limit.
        var remaining = 8192
        val trickle = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                if (remaining == 8192) {
                    remaining -= 1
                    return 0
                }
                val count = minOf(len, remaining, 1024)
                remaining -= count
                return count
            }
        }
        assertThrows(RemoteFileException::class.java) { readBounded(trickle, maxBytes = 4096) }
    }

    @Test
    fun `a stream that never progresses is reported as stalled, not spun on`() {
        val stuck = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        val failure = assertThrows(RemoteFileException::class.java) {
            readBounded(stuck, maxBytes = 4096)
        }
        assertTrue(failure.message.orEmpty().contains("stalled"))
    }

    @Test
    fun `a missing path is reported, not invented`() {
        // stat() reports absence as null rather than inventing an entry.
        assertNull(browser.stat("/does-not-exist"))
        // ...while delete() says so explicitly.
        assertThrows(RemoteFileException::class.java) { browser.delete("/does-not-exist", false) }
    }

    private companion object {
        const val USER = "tester"
        const val PASSWORD = "CorrectHorse1"
    }
}
