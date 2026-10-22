package dev.anthracite.remote.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosixShellTest {
    @Test
    fun `bare tokens stay bare`() {
        assertEquals("/home/ada/project", PosixShell.quote("/home/ada/project"))
        assertEquals("git", PosixShell.quote("git"))
        assertEquals("v1.2.3-rc1", PosixShell.quote("v1.2.3-rc1"))
    }

    @Test
    fun `spaces and quotes are single quoted with the posix idiom`() {
        assertEquals("'my project'", PosixShell.quote("my project"))
        assertEquals("'it'\\''s'", PosixShell.quote("it's"))
        assertEquals("''", PosixShell.quote(""))
    }

    @Test
    fun `a nul byte cannot be quoted at all`() {
        val failure = runCatching { PosixShell.quote("a\u0000b") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `shell metacharacters are quoted, never passed through`() {
        for (value in listOf("\$(reboot)", "a;b", "a|b", "a&b", "a>b", "a`b`", "a\nb", "a\\b")) {
            val quoted = PosixShell.quote(value)
            assertTrue("expected quoting for $value", quoted.startsWith("'"))
        }
    }

    @Test
    fun `paths reject control characters and unbounded input`() {
        assertTrue(PosixShell.isQuotablePath("/srv/app"))
        assertTrue(PosixShell.isQuotablePath("relative/path"))
        assertFalse(PosixShell.isQuotablePath("/srv/app\nrm -rf /"))
        assertFalse(PosixShell.isQuotablePath(""))
        assertFalse(PosixShell.isQuotablePath("/" + "a".repeat(PosixShell.MAX_PATH)))
    }

    @Test
    fun `tool names cannot start with a dash`() {
        assertTrue(PosixShell.isToolName("anthracite-1"))
        assertFalse(PosixShell.isToolName("-rf"))
        assertFalse(PosixShell.isToolName("a b"))
        assertFalse(PosixShell.isToolName(""))
    }

    @Test
    fun `git refs follow the conservative grammar`() {
        assertTrue(PosixShell.isGitRef("main"))
        assertTrue(PosixShell.isGitRef("feature/login-v2"))
        assertFalse(PosixShell.isGitRef("-f"))
        assertFalse(PosixShell.isGitRef("a..b"))
        assertFalse(PosixShell.isGitRef("a b"))
        assertFalse(PosixShell.isGitRef("refs/heads/main.lock"))
        assertFalse(PosixShell.isGitRef("a:b"))
    }

    @Test
    fun `commitish accepts hex, HEAD and refs but not options`() {
        assertTrue(PosixShell.isCommitish("HEAD"))
        assertTrue(PosixShell.isCommitish("deadbeef"))
        assertTrue(PosixShell.isCommitish("main"))
        assertFalse(PosixShell.isCommitish("--upload-pack=evil"))
        assertFalse(PosixShell.isCommitish("HEAD~1; rm -rf /"))
    }

    @Test
    fun `container ids and kubernetes names are separate grammars`() {
        assertTrue(PosixShell.isContainerId("3f2a1b"))
        assertTrue(PosixShell.isContainerId("my_app.web-1"))
        assertFalse(PosixShell.isContainerId("a b"))
        assertFalse(PosixShell.isContainerId("$(id)"))

        assertTrue(PosixShell.isKubernetesName("default"))
        assertTrue(PosixShell.isKubernetesName("kube-system"))
        assertFalse(PosixShell.isKubernetesName("-bad"))
        assertFalse(PosixShell.isKubernetesName("Bad"))
        assertFalse(PosixShell.isKubernetesName("a".repeat(300)))

        assertTrue(PosixShell.isKubernetesResource("deployment/api"))
        assertFalse(PosixShell.isKubernetesResource("deployment/api/extra"))
    }

    @Test
    fun `package names reject shell metacharacters`() {
        assertTrue(PosixShell.isPackageName("python3-dev"))
        assertTrue(PosixShell.isPackageName("g++"))
        assertFalse(PosixShell.isPackageName("pkg;reboot"))
        assertFalse(PosixShell.isPackageName("-pkg"))
    }

    @Test
    fun `executable tokens cannot carry arguments`() {
        assertTrue(PosixShell.isExecutableToken("/usr/bin/tmux"))
        assertTrue(PosixShell.isExecutableToken("tmux"))
        assertFalse(PosixShell.isExecutableToken("tmux -L socket"))
        assertFalse(PosixShell.isExecutableToken("tmux;id"))
    }
}
