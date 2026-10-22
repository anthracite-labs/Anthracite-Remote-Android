package dev.anthracite.remote.terminal

/** Logical keys the terminal understands, independent of the Android keycode table. */
internal enum class TerminalKey {
    ENTER,
    TAB,
    ESCAPE,
    BACKSPACE,
    DELETE,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    INSERT,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

internal data class KeyModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val any: Boolean get() = ctrl || alt || shift

    /** xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4). */
    val csiParameter: Int
        get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
}

internal data class KeyModes(
    val applicationCursorKeys: Boolean = false,
    val bracketedPaste: Boolean = false,
    /** Non-zero when the foreground program asked for mouse reports. */
    val mouseReporting: Int = 0,
    val mouseEncodingSgr: Boolean = false,
)

/**
 * Encodes keyboard input as the byte stream a real xterm would send.
 *
 * This is deliberately pure Kotlin so the mappings are unit-tested on the JVM:
 * a wrong arrow-key encoding breaks vim, htop, lazygit and every other TUI
 * without any Android device being involved.
 */
internal object TerminalKeys {
    fun sequence(key: TerminalKey, modifiers: KeyModifiers, modes: KeyModes): ByteArray {
        val prefix = if (modifiers.alt) ESC else ""
        val body = body(key, modifiers, modes)
        return (prefix + body).toByteArray(Charsets.ISO_8859_1)
    }

    private fun body(key: TerminalKey, modifiers: KeyModifiers, modes: KeyModes): String {
        val parameter = modifiers.csiParameter
        return when (key) {
            TerminalKey.ENTER -> "\r"
            TerminalKey.TAB -> if (modifiers.shift) CSI + "Z" else "\t"
            TerminalKey.ESCAPE -> ESC
            // A real terminal sends DEL for the backspace key; the PTY line
            // discipline maps it to the erase character.
            TerminalKey.BACKSPACE -> "\u007f"
            TerminalKey.DELETE -> modified("3~", parameter)
            TerminalKey.INSERT -> modified("2~", parameter)
            TerminalKey.PAGE_UP -> modified("5~", parameter)
            TerminalKey.PAGE_DOWN -> modified("6~", parameter)
            TerminalKey.HOME -> homeEnd(modes, modifiers, "H", "OH")
            TerminalKey.END -> homeEnd(modes, modifiers, "F", "OF")
            TerminalKey.ARROW_UP -> cursor(modes, modifiers, 'A')
            TerminalKey.ARROW_DOWN -> cursor(modes, modifiers, 'B')
            TerminalKey.ARROW_RIGHT -> cursor(modes, modifiers, 'C')
            TerminalKey.ARROW_LEFT -> cursor(modes, modifiers, 'D')
            TerminalKey.F1 -> function(modifiers, "P")
            TerminalKey.F2 -> function(modifiers, "Q")
            TerminalKey.F3 -> function(modifiers, "R")
            TerminalKey.F4 -> function(modifiers, "S")
            TerminalKey.F5 -> modified("15~", parameter)
            TerminalKey.F6 -> modified("17~", parameter)
            TerminalKey.F7 -> modified("18~", parameter)
            TerminalKey.F8 -> modified("19~", parameter)
            TerminalKey.F9 -> modified("20~", parameter)
            TerminalKey.F10 -> modified("21~", parameter)
            TerminalKey.F11 -> modified("23~", parameter)
            TerminalKey.F12 -> modified("24~", parameter)
        }
    }

    /**
     * Tilde-terminated keys take the modifier as a second parameter
     * (`CSI 3;5~` for Ctrl-Delete), not as a prefix. Getting this wrong makes
     * every modified paging key insert literal digits into a TUI.
     */
    private fun modified(body: String, parameter: Int): String =
        if (parameter == 1) "$CSI$body" else "$CSI${body.dropLast(1)};$parameter${body.last()}"

    private fun cursor(modes: KeyModes, modifiers: KeyModifiers, final: Char): String {
        if (modifiers.any) return "$CSI${modifiers.csiParameter}$final"
        return if (modes.applicationCursorKeys) "$SS3$final" else "$CSI$final"
    }

    private fun homeEnd(modes: KeyModes, modifiers: KeyModifiers, normal: String, application: String): String {
        if (modifiers.any) return "$CSI${modifiers.csiParameter}$normal"
        return if (modes.applicationCursorKeys) "$SS3$application" else "$CSI$normal"
    }

    /**
     * F1–F4 always use the SS3 form xterm sends them in; the application cursor
     * key mode applies to the cursor keys, not to the function keys.
     */
    private fun function(modifiers: KeyModifiers, final: Char): String =
        if (modifiers.any) "$CSI" + "1;" + modifiers.csiParameter + final else SS3 + final

    /**
     * Ctrl+letter and Ctrl+symbol mapping. Returns null for combinations that do
     * not have a C0 control equivalent, so the caller can fall back to text.
     */
    fun control(character: Char): ByteArray? {
        val lower = character.lowercaseChar()
        val code = when (lower) {
            in 'a'..'z' -> lower.code - 'a'.code + 1
            '@', ' ' -> 0
            '[', '{' -> 27
            '\\', '|' -> 28
            ']', '}' -> 29
            '^', '~' -> 30
            '_', '/' -> 31
            '?' -> 127
            else -> return null
        }
        return byteArrayOf(code.toByte())
    }

    /**
     * Encodes typed text (soft-keyboard commit, hardware key characters).
     * Newlines become CR because a PTY in canonical mode expects CR for Enter.
     */
    fun text(input: CharSequence): ByteArray {
        val builder = StringBuilder(input.length)
        for (char in input) {
            when {
                char == '\r' -> Unit
                char == '\n' -> builder.append('\r')
                char.code < 0x20 -> Unit
                char.code == 0x7f -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Encodes a paste.
     *
     * Pasted content is untrusted: escape characters and all other C0 controls
     * except tab and newline are stripped so a copied chunk of terminal output
     * cannot replay control sequences into the shell. When the foreground
     * program enabled bracketed paste, the payload is wrapped so the program can
     * treat it as literal text.
     */
    fun paste(input: CharSequence, modes: KeyModes): ByteArray {
        val builder = StringBuilder(input.length + 16)
        for (char in input) {
            when {
                char == '\r' -> builder.append('\r')
                char == '\n' -> builder.append('\r')
                char == '\t' -> builder.append('\t')
                char.code < 0x20 -> Unit
                // U+009B and friends are C1 controls; drop them as well.
                char.code in 0x80..0x9f -> Unit
                else -> builder.append(char)
            }
        }
        val body = builder.toString()
        val payload = if (modes.bracketedPaste) CSI + "200~" + body + CSI + "201~" else body
        return payload.toByteArray(Charsets.UTF_8)
    }

    /**
     * Mouse report. `column` and `row` are 0-based cell coordinates.
     * `button` uses xterm numbering: 0 left, 1 middle, 2 right, 64/65 wheel.
     */
    fun mouse(button: Int, column: Int, row: Int, press: Boolean, sgr: Boolean): ByteArray {
        val col = column.coerceAtLeast(0)
        val line = row.coerceAtLeast(0)
        if (sgr) {
            val final = if (press) 'M' else 'm'
            return (CSI + "<" + button + ";" + col + ";" + line + final).toByteArray(Charsets.US_ASCII)
        }
        val safeCol = col.coerceAtMost(222)
        val safeRow = line.coerceAtMost(222)
        val code = button + if (press) 0 else 3
        return byteArrayOf(
            0x1b,
            '['.code.toByte(),
            'M'.code.toByte(),
            (32 + code).toByte(),
            (33 + safeCol).toByte(),
            (33 + safeRow).toByte(),
        )
    }

    /** Ctrl-C as a byte, which is what an interactive program actually observes. */
    val INTERRUPT: ByteArray = byteArrayOf(0x03)

    /** Ctrl-D, end of input for a shell. */
    val END_OF_INPUT: ByteArray = byteArrayOf(0x04)

    private const val ESC = "\u001b"
    private const val CSI = "\u001b["
    private const val SS3 = "\u001bO"
}
