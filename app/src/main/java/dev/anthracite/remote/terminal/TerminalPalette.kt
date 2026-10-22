package dev.anthracite.remote.terminal

/**
 * The xterm 256-colour palette in integer form.
 *
 * A terminal needs stable, distinguishable colours independent of the Material
 * theme, so the palette is fixed rather than derived from the current theme. The
 * surrounding chrome still follows the app's design tokens.
 */
internal object TerminalPalette {
    const val DEFAULT_FOREGROUND = 0xffd7e2dc.toInt()
    const val DEFAULT_BACKGROUND = 0xff0e1512.toInt()
    const val CURSOR = 0xff8ccba3.toInt()

    private val ANSI = intArrayOf(
        0xff1c2321.toInt(), // black
        0xffcf5f5f.toInt(), // red
        0xff6fae7f.toInt(), // green
        0xffc8a45c.toInt(), // yellow
        0xff6f97c9.toInt(), // blue
        0xffa682c4.toInt(), // magenta
        0xff5fb3b3.toInt(), // cyan
        0xffc8d0cc.toInt(), // white
        0xff4a5450.toInt(), // bright black
        0xffe48585.toInt(), // bright red
        0xff8ccba3.toInt(), // bright green
        0xffe0c07a.toInt(), // bright yellow
        0xff8fb4e0.toInt(), // bright blue
        0xffc0a2e0.toInt(), // bright magenta
        0xff82d0d0.toInt(), // bright cyan
        0xfff0f3f0.toInt(), // bright white
    )

    fun foreground(index: Int): Int = if (index == TerminalStyle.DEFAULT) DEFAULT_FOREGROUND else palette(index)

    fun background(index: Int): Int = if (index == TerminalStyle.DEFAULT) DEFAULT_BACKGROUND else palette(index)

    private fun palette(index: Int): Int = when {
        index in 0..15 -> ANSI[index]
        index in 16..231 -> {
            val cube = index - 16
            val red = cube / 36
            val green = (cube % 36) / 6
            val blue = cube % 6
            rgb(ramp(red), ramp(green), ramp(blue))
        }
        index in 232..255 -> {
            val level = 8 + (index - 232) * 10
            rgb(level, level, level)
        }
        else -> DEFAULT_FOREGROUND
    }

    private fun ramp(level: Int): Int = if (level == 0) 0 else 55 + level * 40

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    /** Applies inverse video, which is how full-screen programs draw selection bars. */
    fun resolve(style: Int): Pair<Int, Int> {
        var fg = foreground(TerminalStyle.foreground(style))
        var bg = background(TerminalStyle.background(style))
        if (TerminalStyle.has(style, TerminalStyle.INVERSE)) {
            val swap = fg
            fg = bg
            bg = swap
        }
        if (TerminalStyle.has(style, TerminalStyle.DIM)) {
            fg = blend(fg, bg, 0.55f)
        }
        return fg to bg
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xff
        val red = (channel(from, 16) * (1 - amount) + channel(to, 16) * amount).toInt()
        val green = (channel(from, 8) * (1 - amount) + channel(to, 8) * amount).toInt()
        val blue = (channel(from, 0) * (1 - amount) + channel(to, 0) * amount).toInt()
        return rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
    }
}
