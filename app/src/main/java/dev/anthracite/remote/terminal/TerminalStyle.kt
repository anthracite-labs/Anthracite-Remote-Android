package dev.anthracite.remote.terminal

/**
 * Packed SGR attribute used by a terminal cell.
 *
 * The record is an [Int] rather than an object so a full screen of cells can be
 * snapshot without per-cell allocation:
 *
 * ```
 * bit  0..6   flags (bold, dim, italic, underline, blink, inverse, strike)
 * bit  7..22  foreground: 0..255 palette index, or [DEFAULT]
 * bit 23..38  background: 0..255 palette index, or [DEFAULT]
 * ```
 *
 * 24-bit colour from SGR `38;2;r;g;b` is reduced to the 256-colour cube at parse
 * time. Rendering therefore never needs a colour allocator or a per-cell object
 * graph, and the emulator stays usable on low-end devices with large scrollback.
 */
internal object TerminalStyle {
    const val DEFAULT: Int = 256

    const val BOLD: Int = 1
    const val DIM: Int = 2
    const val ITALIC: Int = 4
    const val UNDERLINE: Int = 8
    const val BLINK: Int = 16
    const val INVERSE: Int = 32
    const val STRIKE: Int = 64

    private const val FG_SHIFT = 7
    private const val BG_SHIFT = 23
    private const val PALETTE_MASK = 0xffff

    val PLAIN: Int = pack(DEFAULT, DEFAULT, 0)

    fun pack(fg: Int, bg: Int, flags: Int): Int =
        (flags and 0x7f) or
            ((fg and PALETTE_MASK) shl FG_SHIFT) or
            ((bg and PALETTE_MASK) shl BG_SHIFT)

    fun foreground(style: Int): Int = (style ushr FG_SHIFT) and PALETTE_MASK

    fun background(style: Int): Int = (style ushr BG_SHIFT) and PALETTE_MASK

    fun flags(style: Int): Int = style and 0x7f

    fun has(style: Int, flag: Int): Boolean = (style and flag) != 0

    fun withForeground(style: Int, fg: Int): Int =
        pack(fg, background(style), flags(style))

    fun withBackground(style: Int, bg: Int): Int =
        pack(foreground(style), bg, flags(style))

    fun withFlags(style: Int, flags: Int): Int =
        pack(foreground(style), background(style), flags)

    /** Resets colour state but keeps the intensity/underline flags, as xterm does. */
    fun resetColors(style: Int): Int = pack(DEFAULT, DEFAULT, flags(style))

    /**
     * Reduces a 24-bit colour to the xterm 256-colour palette: the 6x6x6 colour
     * cube with a 5-level ramp. The result is visually close and needs no extra
     * state in the cell.
     */
    fun toPalette(red: Int, green: Int, blue: Int): Int {
        fun level(value: Int): Int {
            val clamped = value.coerceIn(0, 255)
            return ((clamped * 5 + 127) / 255).coerceIn(0, 5)
        }
        return 16 + 36 * level(red) + 6 * level(green) + level(blue)
    }
}

/**
 * A single character cell as delivered to the renderer.
 *
 * `width` is 1 for a normal cell, 2 for the leading cell of a double-width glyph
 * and 0 for the continuation cell that the glyph covers.
 */
internal object TerminalCell {
    private const val WIDTH_SHIFT = 16
    private const val STYLE_SHIFT = 18

    private const val BLANK_CHAR = ' '

    fun pack(char: Char, width: Int, style: Int): Long =
        (char.code.toLong() and 0xffffL) or
            ((width.coerceIn(0, 3).toLong()) shl WIDTH_SHIFT) or
            ((style.toLong() and 0xffffffffL) shl STYLE_SHIFT)

    fun char(cell: Long): Char = (cell and 0xffffL).toInt().toChar()

    fun width(cell: Long): Int = ((cell ushr WIDTH_SHIFT) and 0x3L).toInt()

    fun style(cell: Long): Int = ((cell ushr STYLE_SHIFT) and 0xffffffffL).toInt()

    fun isBlank(cell: Long): Boolean = char(cell) == BLANK_CHAR && style(cell) == TerminalStyle.PLAIN

    val BLANK: Long = pack(BLANK_CHAR, 1, TerminalStyle.PLAIN)
}
