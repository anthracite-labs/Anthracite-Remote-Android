package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class InstallScriptTest {
    @Test
    fun `installer script checks pass`() {
        val script = findScript()
        val process = ProcessBuilder("bash", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    private fun findScript(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(dir, "app/src/test/shell/install-opencode-test.sh")
            if (candidate.isFile) return candidate
            val nested = File(dir, "src/test/shell/install-opencode-test.sh")
            if (nested.isFile) return nested
            dir = dir.parentFile ?: break
        }
        error("install-opencode-test.sh not found from ${System.getProperty("user.dir")}")
    }
}
