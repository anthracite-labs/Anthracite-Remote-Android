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
    fun `webview loads only the forwarded loopback origin`() {
        assertEquals(WebNavigation.LOAD, classifyNavigation("http://127.0.0.1:4096/", 4096))
        assertEquals(WebNavigation.LOAD, classifyNavigation("http://localhost:4096/session", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://127.0.0.1:1/", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("http://user:secret@127.0.0.1:4096/", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("file:///data/data/private", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("javascript:alert(1)", 4096))
        assertEquals(WebNavigation.BLOCK, classifyNavigation("intent://scan/#Intent;end", 4096))
        assertEquals(WebNavigation.EXTERNAL, classifyNavigation("https://opencode.ai/docs", 4096))
        assertEquals(WebNavigation.EXTERNAL, classifyNavigation("http://example.com/", 4096))
        assertEquals(LOOPBACK, "127.0.0.1")
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
