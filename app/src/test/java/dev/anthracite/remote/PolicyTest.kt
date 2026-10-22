package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test
    fun `fingerprint matches OpenSSH SHA256 of the raw key blob`() {
        val blob = byteArrayOf(0, 0, 0, 0x0b) + "ssh-ed25519".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0x20) + ByteArray(32) { 'A'.code.toByte() }
        assertEquals("ssh-ed25519", sshKeyType(blob))
        assertEquals("SHA256:ycecTFElO4tMaWgqfHQDEUjUete0Tsl0wmvU/kkDXFc", sha256Fingerprint(blob))
    }

    @Test
    fun `host key decision refuses a changed key`() {
        val saved = byteArrayOf(1, 2, 3)
        val presented = byteArrayOf(1, 2, 4)
        assertEquals(HostKeyDecision.UNKNOWN, hostKeyDecision(null, presented))
        assertEquals(HostKeyDecision.MATCH, hostKeyDecision(saved, saved.copyOf()))
        assertEquals(HostKeyDecision.CHANGED, hostKeyDecision(saved, presented))
    }

    @Test
    fun `webview accepts only the explicit forwarded origin and websocket`() {
        assertEquals(WebNavigation.LOAD, classifyNavigation("http://127.0.0.1:4096/", 4096))
        assertEquals(WebNavigation.LOAD, classifyNavigation("ws://127.0.0.1:4096/events", 4096))
        assertTrue(loopbackHttp("http://127.0.0.1:4096/download", 4096))
        assertTrue(loopbackWebSocket("ws://127.0.0.1:4096/events", 4096))

        assertFalse(loopbackHttp("http://localhost:4096/session", 4096))
        assertFalse(loopbackHttp("http://[::1]:4096/session", 4096))
        assertFalse(loopbackWebSocket("wss://127.0.0.1:4096/events", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://localhost:4096/session", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://[::1]:4096/session", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://127.0.0.1:1/", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://user:secret@127.0.0.1:4096/", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("file:///data/data/private", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("javascript:alert(1)", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("intent://scan/#Intent;end", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://example.com/", 4096))
        assertEquals(WebNavigation.EXTERNAL, classifyNavigation("https://opencode.ai/docs", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("https://user@opencode.ai/docs", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://127.0.0.1:4096/redirect", 1))
        assertEquals(LOOPBACK, "127.0.0.1")
    }

    @Test
    fun `basic auth and tunnel readiness are restricted to an authenticated forwarded origin`() {
        assertTrue(authReleaseAllowed("http://127.0.0.1:4096/", null, "127.0.0.1", 4096))
        assertTrue(authReleaseAllowed("about:blank", "http://127.0.0.1:4096/session", "127.0.0.1", 4096))
        assertFalse(authReleaseAllowed("about:blank", "http://localhost:4096/session", "localhost", 4096))
        assertFalse(authReleaseAllowed("http://127.0.0.1:1/", "http://127.0.0.1:4096/", "127.0.0.1", 4096))
        assertFalse(authReleaseAllowed("http://127.0.0.1:4096/", null, "evil.example", 4096))
        assertFalse(authReleaseAllowed("https://evil.example/", "http://127.0.0.1:4096/", "127.0.0.1", 4096))
        assertFalse(authReleaseAllowed(null, null, "127.0.0.1", 4096))

        assertTrue(authenticatedTunnelReady(401, listOf("Basic realm=\"OpenCode\"")))
        assertTrue(authenticatedTunnelReady(401, listOf("Digest realm=\"x\", Basic realm=\"OpenCode\"")))
        assertTrue(authenticatedTunnelReady(401, listOf("basic realm=\"OpenCode\"")))
        assertFalse(authenticatedTunnelReady(200, listOf("Basic realm=\"OpenCode\"")))
        assertFalse(authenticatedTunnelReady(401, listOf("Digest realm=\"OpenCode\"")))
        assertFalse(authenticatedTunnelReady(302, emptyList()))
    }

    @Test
    fun `downloads do not redirect credentials outside the pinned origin`() {
        val origin = "http://127.0.0.1:4096/export"
        assertTrue(downloadRedirectAllowed(origin, "http://127.0.0.1:4096/export/archive.zip", 4096))
        assertFalse(downloadRedirectAllowed(origin, "http://evil.example/export", 4096))
        assertFalse(downloadRedirectAllowed(origin, "https://127.0.0.1:4096/export", 4096))
        assertFalse(downloadRedirectAllowed(origin, "http://localhost:4096/export", 4096))
        assertFalse(downloadRedirectAllowed(origin, "http://[::1]:4096/export", 4096))
        assertFalse(downloadRedirectAllowed(origin, "http://127.0.0.1:4097/export", 4096))
        assertFalse(downloadRedirectAllowed("https://example.com/", origin, 4096))
    }

    @Test
    fun `web authentication identity includes approved host key and remote identity`() {
        val firstKey = byteArrayOf(1, 2, 3)
        val replacementKey = byteArrayOf(1, 2, 4)
        val identity = tunnelIdentity("host.example", 22, "user", 4096, firstKey)
        assertEquals(identity, tunnelIdentity(" host.example ", 22, "user", 4096, firstKey.copyOf()))
        assertFalse(identity == tunnelIdentity("host.example", 22, "user", 4096, replacementKey))
        assertFalse(identity == tunnelIdentity("other.example", 22, "user", 4096, firstKey))
        assertFalse(identity == tunnelIdentity("host.example", 22, "other", 4096, firstKey))
        assertFalse(identity == tunnelIdentity("host.example", 22, "user", 4097, firstKey))
        assertFalse(identity == tunnelIdentity("host.example", 22, "user", 4096, null))
    }

    @Test
    fun `fields and remote paths reject unsafe values`() {
        assertEquals(22, parsePort("22"))
        assertEquals(null, parsePort("0"))
        assertEquals(null, parsePort("22a"))
        assertTrue(sshHostAllowed("server.example"))
        assertFalse(sshHostAllowed("bad host"))
        assertFalse(sshUsernameAllowed("root user"))
        assertTrue(webPasswordAccepted("CorrectHorse1"))
        assertFalse(webPasswordAccepted("short1"))
        assertEquals("token ***", redact("token CorrectHorse1", "CorrectHorse1"))
        assertTrue(uploadDirAllowed("/home/user/.local/share/anthracite/upload.abc"))
        assertFalse(uploadDirAllowed("/tmp/upload.abc/../../etc"))
        assertEquals("'/home/user/.local/share/anthracite/upload.abc'", shellQuote("/home/user/.local/share/anthracite/upload.abc"))
        assertNull(shellQuote("/tmp/../etc"))
    }
}
