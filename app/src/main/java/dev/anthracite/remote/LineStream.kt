package dev.anthracite.remote

import java.io.ByteArrayOutputStream

/**
 * Splits streamed command output into complete lines. Bytes accumulate until a
 * LF arrives, so multi-byte UTF-8 characters split across network chunks decode
 * correctly. Trailing CR is removed; [flush] returns a final partial line.
 */
internal class LineStream {
    private val pending = ByteArrayOutputStream()

    fun accept(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        for (byte in chunk) {
            if (byte == NEWLINE) {
                lines += decode(pending.toByteArray())
                pending.reset()
            } else {
                pending.write(byte.toInt())
            }
        }
        return lines
    }

    fun flush(): List<String> {
        val rest = pending.toByteArray()
        pending.reset()
        val line = decode(rest)
        return if (line.isEmpty()) emptyList() else listOf(line)
    }

    private fun decode(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removeSuffix("\r")

    private companion object {
        val NEWLINE = '\n'.code.toByte()
    }
}
