package dev.anthracite.remote.host

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionJobsTest {
    private fun status(vararg lines: String) = ProvisionJobs.parseStatus(lines.joinToString("\n"))

    @Test
    fun `a running job with no live process is reported as interrupted`() {
        val parsed = status(
            "status=RUNNING",
            "step=2",
            "exit_code=-1",
            "pid=4242",
            "process_alive=no",
            "start_ticks_match=yes",
            "result_present=no",
        )
        assertEquals(JobState.INTERRUPTED, parsed.state)
        assertEquals("2", parsed.step)
        assertEquals("4242", parsed.pid)
        assertFalse(parsed.processAlive)
    }

    @Test
    fun `a reused pid is an interruption even when a process is alive`() {
        assertEquals(
            JobState.INTERRUPTED,
            ProvisionJobs.reconcile(JobState.RUNNING, alive = true, ticksMatch = false),
        )
        assertEquals(
            JobState.RUNNING,
            ProvisionJobs.reconcile(JobState.RUNNING, alive = true, ticksMatch = true),
        )
        assertEquals(
            JobState.RUNNING,
            ProvisionJobs.reconcile(JobState.RUNNING, alive = true, ticksMatch = null),
        )
    }

    @Test
    fun `terminal states are never rewritten`() {
        assertEquals(JobState.SUCCEEDED, ProvisionJobs.reconcile(JobState.SUCCEEDED, false, null))
        assertEquals(JobState.FAILED, ProvisionJobs.reconcile(JobState.FAILED, false, null))
        assertEquals(JobState.CANCELLED, ProvisionJobs.reconcile(JobState.CANCELLED, false, null))
    }

    @Test
    fun `unknown state is explicit rather than guessed`() {
        assertEquals(JobState.UNKNOWN, status("status=SOMETHING_NEW").state)
        assertEquals(JobState.LOCKED, status("status=LOCKED").state)
    }

    @Test
    fun `the log tail is base64 decoded and bounded from the end`() {
        val encoded = Base64.getEncoder().encodeToString("last line\n".toByteArray())
        val parsed = status(
            "status=FAILED",
            "exit_code=1",
            "reason=install_failed",
            "log_bytes=9",
            "log_tail_b64=$encoded",
        )
        assertEquals("last line\n", parsed.logTail)
        assertEquals(9, parsed.logBytes)
        assertEquals("install_failed", parsed.reason)

        val huge = "x".repeat(ProvisionJobs.LOG_TAIL_LIMIT + 500)
        val hugeEncoded = Base64.getEncoder().encodeToString(huge.toByteArray())
        val bigTail = status("log_tail_b64=$hugeEncoded")
        assertEquals(ProvisionJobs.LOG_TAIL_LIMIT, bigTail.logTail.length)
        assertTrue(bigTail.rawLogTruncated)
    }

    @Test
    fun `corrupt log data cannot break status parsing`() {
        val parsed = status("status=RUNNING", "process_alive=yes", "log_tail_b64=***not-base64***")
        assertEquals("", parsed.logTail)
        assertFalse(parsed.rawLogTruncated)
    }

    @Test
    fun `job commands are fixed and quoted`() {
        val id = "0123456789abcdef"
        assertEquals(".anthracite/jobs/$id", ProvisionJobs.jobDirectory(id))
        assertTrue(ProvisionJobs.mkdirCommand(id).contains("\$HOME/.anthracite/jobs/$id"))
        assertTrue(ProvisionJobs.launchCommand(id, useSetsid = true).contains("setsid sh ./"))
        assertTrue(ProvisionJobs.launchCommand(id, useSetsid = false).contains("nohup sh ./"))
        assertEquals("kill -TERM 4242", ProvisionJobs.cancelCommand("4242"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a job id outside the grammar is refused`() {
        ProvisionJobs.jobDirectory("../../etc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pid outside the grammar is refused`() {
        ProvisionJobs.cancelCommand("1; rm -rf /")
    }

    @Test
    fun `generated job ids match the grammar the runner accepts`() {
        repeat(32) {
            val id = ProvisionJobs.newJobId()
            assertEquals(16, id.length)
            assertTrue(id.all { char -> char in '0'..'9' || char in 'a'..'f' })
        }
    }

    @Test
    fun `the redactor removes credentials that host output echoed`() {
        val text = """
            token=ghp_abcdefghijklmnopqrstuvwxyz0123
            password: hunter2
            github_pat_11ABCDEFG0123456789_abcdefghijklmnop
            api_key = 1234567890
            harmless line
        """.trimIndent()
        val redacted = LogRedactor.redact(text, extraSecrets = listOf("super-secret-value"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("ghp_"))
        assertFalse(redacted.contains("github_pat_"))
        assertFalse(redacted.contains("1234567890"))
        assertTrue(redacted.contains("harmless line"))
        assertTrue(redacted.contains(LogRedactor.REDACTED))
    }

    @Test
    fun `short secrets are not treated as replaceable substrings`() {
        assertEquals("abc", LogRedactor.redact("abc", extraSecrets = listOf("ab")))
        assertNull(ProvisionJobs.parseStatus("").pid)
    }
}
