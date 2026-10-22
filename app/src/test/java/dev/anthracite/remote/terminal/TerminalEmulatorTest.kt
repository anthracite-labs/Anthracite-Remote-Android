package dev.anthracite.remote.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator behaviour that a user can observe: what is on screen, what is in the
 * scrollback, and which requests the terminal refuses to honour.
 */
class TerminalEmulatorTest {
    private class Recorder : TerminalEmulator.Listener {
        val titles = mutableListOf<String>()
        val clipboardWrites = mutableListOf<Unit>()
        val hyperlinks = mutableListOf<String>()
        var bells = 0
        val replies = mutableListOf<String>()

        override fun onTitle(title: String) {
            titles += title
        }

        override fun onBlockedClipboardWrite() {
            clipboardWrites += Unit
        }

        override fun onHyperlink(uri: String) {
            hyperlinks += uri
        }

        override fun onBell() {
            bells++
        }

        override fun onReply(bytes: ByteArray) {
            replies += String(bytes)
        }
    }

    private fun emulator(cols: Int = 20, rows: Int = 4, scrollback: Int = 100) =
        TerminalEmulator(cols, rows, scrollback).also { it.listener = Recorder() }

    private fun TerminalEmulator.recorder(): Recorder = listener as Recorder

    @Test
    fun `plain text lands on the screen and wraps at the right edge`() {
        val emu = emulator(cols = 10, rows = 3)
        emu.feed("abcdefghijklmno")
        assertEquals("abcdefghij", emu.lineText(0))
        assertEquals("klmno", emu.lineText(1))
    }

    @Test
    fun `carriage return and newline behave separately`() {
        val emu = emulator(cols = 20, rows = 3)
        emu.feed("one\r\ntwo\r\nthree")
        assertEquals("one", emu.lineText(0))
        assertEquals("two", emu.lineText(1))
        assertEquals("three", emu.lineText(2))
    }

    @Test
    fun `cursor addressing writes at the requested cell`() {
        val emu = emulator(cols = 20, rows = 4)
        emu.feed("\u001b[2;3HX")
        assertEquals("  X", emu.lineText(1))
        emu.feed("\u001b[1;1HY")
        assertEquals("Y", emu.lineText(0))
        assertEquals("  X", emu.lineText(1))
    }

    @Test
    fun `erase in line and display do not leave stale glyphs`() {
        val emu = emulator(cols = 10, rows = 3)
        emu.feed("hello world")
        emu.feed("\r\u001b[K")
        assertEquals("", emu.lineText(1))
        assertEquals("hello worl", emu.lineText(0).take(10).trimEnd())
    }

    @Test
    fun `scrolling pushes lines into bounded scrollback`() {
        val emu = emulator(cols = 10, rows = 2, scrollback = 3)
        emu.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        // Four lines scrolled off a two-row screen; the cap keeps the newest three.
        assertEquals(3, emu.scrollbackLines)
        assertTrue(emu.screenText().contains("5"))
        val top = emu.snapshot(scrollOffset = 3).cell(0, 0)
        assertEquals('2', TerminalCell.char(top))
    }

    @Test
    fun `the alternate screen is separate and restored`() {
        val emu = emulator(cols = 10, rows = 3)
        emu.feed("primary")
        emu.feed("\u001b[?1049h")
        assertTrue(emu.isAlternateScreen)
        emu.feed("alternate")
        assertTrue(emu.screenText().contains("alternate"))
        emu.feed("\u001b[?1049l")
        assertFalse(emu.isAlternateScreen)
        assertTrue(emu.screenText().contains("primary"))
    }

    @Test
    fun `modes are reported so the keyboard can encode keys correctly`() {
        val emu = emulator()
        assertFalse(emu.usesApplicationCursorKeys)
        assertFalse(emu.acceptsBracketedPaste)
        emu.feed("\u001b[?1h\u001b[?2004h")
        assertTrue(emu.usesApplicationCursorKeys)
        assertTrue(emu.acceptsBracketedPaste)
        emu.feed("\u001b[?1l\u001b[?2004l")
        assertFalse(emu.usesApplicationCursorKeys)
        assertFalse(emu.acceptsBracketedPaste)

        emu.feed("\u001b[?1000h")
        assertEquals(1000, emu.mouseReporting)
        emu.feed("\u001b[?1000l\u001b[?1006h")
        assertEquals(0, emu.mouseReporting)
        assertTrue(emu.mouseEncodingSgr)
    }

    @Test
    fun `osc 52 clipboard writes are refused and reported`() {
        val emu = emulator()
        emu.feed("\u001b]52;c;SGVsbG8=\u0007")
        assertEquals(1, emu.recorder().clipboardWrites.size)
    }

    @Test
    fun `only https hyperlinks reach the ui and nothing is opened`() {
        val emu = emulator()
        emu.feed("\u001b]8;;https://example.com/docs\u0007text\u001b]8;;\u0007")
        assertEquals(listOf("https://example.com/docs"), emu.recorder().hyperlinks)
        emu.feed("\u001b]8;;javascript:alert(1)\u0007x\u001b]8;;\u0007")
        assertEquals(1, emu.recorder().hyperlinks.size)
        emu.feed("\u001b]8;;http://plain.example\u0007x\u001b]8;;\u0007")
        assertEquals(1, emu.recorder().hyperlinks.size)
    }

    @Test
    fun `titles are sanitised and capped`() {
        val emu = emulator()
        emu.feed("\u001b]0;my\u0007title\u0007")
        assertEquals("my", emu.recorder().titles.last())
        emu.feed("\u001b]2;" + "x".repeat(5000) + "\u0007")
        assertTrue(emu.terminalTitle.length <= 120)
    }

    @Test
    fun `device attributes and cursor position queries are answered`() {
        val emu = emulator()
        emu.feed("\u001b[6n")
        assertTrue(emu.recorder().replies.any { it.startsWith("\u001b[") && it.endsWith("R") })
        emu.feed("\u001b[c")
        assertTrue(emu.recorder().replies.any { it.contains("?") })
    }

    @Test
    fun `a split utf8 sequence is decoded once the rest arrives`() {
        val emu = emulator()
        val bytes = "é".toByteArray(Charsets.UTF_8)
        emu.feed(byteArrayOf(bytes[0]))
        assertEquals("", emu.lineText(0).trim())
        emu.feed(byteArrayOf(bytes[1]))
        assertEquals("é", emu.lineText(0).trim())
    }

    @Test
    fun `an invalid utf8 byte becomes a replacement character instead of corrupting state`() {
        val emu = emulator()
        emu.feed(byteArrayOf(0xff.toByte(), 'a'.code.toByte()))
        assertEquals("\uFFFD", emu.lineText(0).substring(0, 1))
        assertEquals('a', emu.lineText(0)[1])
    }

    @Test
    fun `reset clears the screen and the modes`() {
        val emu = emulator()
        emu.feed("text\u001b[?1h")
        emu.reset()
        assertEquals("", emu.screenText().trim())
        assertFalse(emu.usesApplicationCursorKeys)
    }

    @Test
    fun `scroll regions keep lines above the region in place`() {
        val emu = emulator(cols = 10, rows = 4)
        emu.feed("prompt\r\nline1\r\nline2\r\nline3")
        emu.feed("\u001b[2;4r") // scroll rows 2..4 only
        emu.feed("\u001b[4;1H\n")
        assertEquals("prompt", emu.lineText(0).trim())
    }

    @Test
    fun `wide characters occupy two cells and are not split`() {
        val emu = emulator(cols = 6, rows = 2)
        emu.feed("中")
        assertEquals(2, emu.snapshot().cell(0, 0).let { TerminalCell.width(it) })
        assertEquals(0, emu.snapshot().cell(0, 1).let { TerminalCell.width(it) })
    }

    @Test
    fun `bounded parsing ignores an oversized osc payload without hanging`() {
        val emu = emulator()
        val payload = "\u001b]2;" + "a".repeat(5000)
        emu.feed(payload + "\u0007" + "after")
        assertTrue(emu.screenText().contains("after"))
    }

    @Test
    fun `resize keeps the visible content and clamps the grid`() {
        val emu = emulator(cols = 10, rows = 3)
        emu.feed("abc\r\ndef")
        emu.resize(4, 2)
        assertEquals(4, emu.cols)
        assertEquals(2, emu.rows)
        assertEquals("abc", emu.lineText(0).trim())
        emu.resize(1, 1)
        assertEquals(2, emu.cols)
        assertEquals(2, emu.rows)
    }
}
