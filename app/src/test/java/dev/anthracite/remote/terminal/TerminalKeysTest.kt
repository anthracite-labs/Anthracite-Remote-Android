package dev.anthracite.remote.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    private val normal = KeyModes()
    private val application = KeyModes(applicationCursorKeys = true)

    private fun key(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers(), modes: KeyModes = normal): String =
        String(TerminalKeys.sequence(key, modifiers, modes), Charsets.ISO_8859_1)

    private fun pasted(text: String, bracketed: Boolean = false): String =
        String(TerminalKeys.paste(text, KeyModes(bracketedPaste = bracketed)), Charsets.UTF_8)

    @Test
    fun `arrows follow the cursor key mode the program requested`() {
        assertEquals("\u001b[A", key(TerminalKey.ARROW_UP))
        assertEquals("\u001bOA", key(TerminalKey.ARROW_UP, modes = application))
        assertEquals("\u001bOC", key(TerminalKey.ARROW_RIGHT, modes = application))
        assertEquals("\u001b[F", key(TerminalKey.END))
        assertEquals("\u001bOF", key(TerminalKey.END, modes = application))
    }

    @Test
    fun `modified arrows use the xterm modifier parameter`() {
        assertEquals("\u001b[1;5C", key(TerminalKey.ARROW_RIGHT, KeyModifiers(ctrl = true)))
        assertEquals("\u001b[1;2C", key(TerminalKey.ARROW_RIGHT, KeyModifiers(shift = true)))
        assertEquals("\u001b[1;3C", key(TerminalKey.ARROW_RIGHT, KeyModifiers(alt = true)))
        assertEquals("\u001b[1;6C", key(TerminalKey.ARROW_RIGHT, KeyModifiers(ctrl = true, shift = true)))
    }

    @Test
    fun `function keys use their fixed sequences`() {
        assertEquals("\u001bOP", key(TerminalKey.F1))
        assertEquals("\u001bOS", key(TerminalKey.F4))
        assertEquals("\u001b[15~", key(TerminalKey.F5))
        assertEquals("\u001b[17~", key(TerminalKey.F6))
        assertEquals("\u001b[20~", key(TerminalKey.F9))
        assertEquals("\u001b[24~", key(TerminalKey.F12))
        assertEquals("\u001b[1;5P", key(TerminalKey.F1, KeyModifiers(ctrl = true)))
    }

    @Test
    fun `modified tilde keys are a second parameter, not a prefix`() {
        assertEquals("\u001b[3~", key(TerminalKey.DELETE))
        assertEquals("\u001b[3;5~", key(TerminalKey.DELETE, KeyModifiers(ctrl = true)))
        assertEquals("\u001b[5;3~", key(TerminalKey.PAGE_UP, KeyModifiers(alt = true)))
    }

    @Test
    fun `editing keys use the bytes a shell expects`() {
        assertEquals("\r", key(TerminalKey.ENTER))
        assertEquals("\u007f", key(TerminalKey.BACKSPACE))
        assertEquals("\t", key(TerminalKey.TAB))
        assertEquals("\u001b[Z", key(TerminalKey.TAB, KeyModifiers(shift = true)))
        assertEquals("\u001b", key(TerminalKey.ESCAPE))
        assertEquals("\u0003", String(TerminalKeys.INTERRUPT))
        assertEquals("\u0004", String(TerminalKeys.END_OF_INPUT))
    }

    @Test
    fun `alt is a modifier parameter where the key has one, an escape prefix otherwise`() {
        // xterm: Alt+Up is CSI 1;3A in both cursor key modes, and the application
        // mode does not stack a second ESC in front of it.
        assertEquals("\u001b[1;3A", key(TerminalKey.ARROW_UP, KeyModifiers(alt = true)))
        assertEquals("\u001b[1;3A", key(TerminalKey.ARROW_UP, KeyModifiers(alt = true), application))
        // Home and End carry the parameter too, in the same 1;<modifier> shape.
        assertEquals("\u001b[1;3F", key(TerminalKey.END, KeyModifiers(alt = true), application))
        // Backspace has no parameter form, so Alt stays the classic ESC prefix.
        assertEquals("\u001b\u007f", key(TerminalKey.BACKSPACE, KeyModifiers(alt = true)))
    }

    @Test
    fun `control characters follow the c0 table`() {
        assertEquals(0x03.toByte(), TerminalKeys.control('c')?.single())
        assertEquals(0x01.toByte(), TerminalKeys.control('a')?.single())
        assertEquals(0x00.toByte(), TerminalKeys.control(' ')?.single())
        assertEquals(0x1b.toByte(), TerminalKeys.control('[')?.single())
        assertEquals(0x7f.toByte(), TerminalKeys.control('?')?.single())
        assertEquals(0x03.toByte(), TerminalKeys.control('C')?.single())
        assertNull(TerminalKeys.control('1'))
        assertNull(TerminalKeys.control('é'))
    }

    @Test
    fun `typed text becomes cr for enter and drops other controls`() {
        assertEquals("a\rb", String(TerminalKeys.text("a\nb"), Charsets.UTF_8))
        assertEquals("ab", String(TerminalKeys.text("a\u0003b"), Charsets.UTF_8))
        assertEquals("é中", String(TerminalKeys.text("é中"), Charsets.UTF_8))
    }

    @Test
    fun `paste is bracketed only when the program asked for it`() {
        assertEquals("hello", pasted("hello"))
        assertEquals("\u001b[200~hello\u001b[201~", pasted("hello", bracketed = true))
    }

    @Test
    fun `paste strips escape and control characters that could execute on their own`() {
        val raw = "ls\u001b[2J\u0003world\u0007end"
        val plain = pasted(raw)
        assertFalse(plain.contains('\u001b'))
        assertFalse(plain.contains('\u0003'))
        assertFalse(plain.contains('\u0007'))
        assertTrue(plain.contains("ls"))
        assertTrue(plain.contains("world"))

        val bracketed = pasted(raw, bracketed = true)
        assertTrue(bracketed.startsWith("\u001b[200~"))
        assertTrue(bracketed.endsWith("\u001b[201~"))
        // The payload cannot contain a second terminator, so it cannot end early.
        assertEquals(1, bracketed.split("\u001b[201~").size - 1)
    }

    @Test
    fun `mouse reports match the requested encoding`() {
        assertEquals("\u001b[<0;4;9M", String(TerminalKeys.mouse(0, 4, 9, press = true, sgr = true)))
        assertEquals("\u001b[<0;4;9m", String(TerminalKeys.mouse(0, 4, 9, press = false, sgr = true)))
        val x10 = TerminalKeys.mouse(0, 4, 9, press = true, sgr = false)
        assertEquals(6, x10.size)
        assertEquals(0x1b.toByte(), x10[0])
        assertEquals('M'.code.toByte(), x10[2])
    }

    @Test
    fun `mouse coordinates are clamped into a byte-safe range`() {
        val clamped = String(TerminalKeys.mouse(0, -5, 5000, press = true, sgr = true))
        assertFalse(clamped.contains("-"))
        assertTrue(clamped.startsWith("\u001b[<0;0;"))

        val x10 = TerminalKeys.mouse(0, 5000, 5000, press = false, sgr = false)
        assertEquals(6, x10.size)
        // The X10 form cannot carry a coordinate above 222; it is clamped, not wrapped.
        // Byte 0xFF is -1 once widened, so read it as unsigned.
        assertEquals(33 + 222, x10[4].toInt() and 0xFF)
    }
}
