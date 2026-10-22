package dev.anthracite.remote.terminal

/**
 * One terminal row.
 *
 * Wide glyphs occupy two cells: the leading cell carries width 2 and the next
 * cell is a width-0 continuation. Overwriting either half clears both, so a line
 * can never contain a half glyph after cursor movement or erase.
 *
 * Combining marks are kept in a side map instead of the char array because they
 * are rare: a renderer must draw `base + marks` as one cluster, and only the
 * cells that actually have marks pay for the map.
 */
internal class TerminalLine(cols: Int) {
    var chars: CharArray = CharArray(cols) { ' ' }
        private set

    var styles: IntArray = IntArray(cols) { TerminalStyle.PLAIN }
        private set

    var widths: ByteArray = ByteArray(cols) { 1 }
        private set

    private var combining: HashMap<Int, String>? = null

    var length: Int = cols
        private set

    fun charAt(col: Int): Char = if (col in 0 until length) chars[col] else ' '

    fun styleAt(col: Int): Int = if (col in 0 until length) styles[col] else TerminalStyle.PLAIN

    fun widthAt(col: Int): Int = if (col in 0 until length) widths[col].toInt() else 1

    fun combiningAt(col: Int): String? = combining?.get(col)

    fun combiningCount(): Int = combining?.size ?: 0

    fun setCell(col: Int, char: Char, width: Int, style: Int) {
        if (col !in 0 until length) return
        clearPartner(col)
        chars[col] = char
        styles[col] = style
        widths[col] = width.coerceIn(0, 2).toByte()
        if (width == 1) clearCombining(col)
        if (width == 2 && col + 1 < length) {
            clearPartner(col + 1)
            chars[col + 1] = ' '
            styles[col + 1] = style
            widths[col + 1] = 0
            clearCombining(col + 1)
        }
    }

    fun clear(col: Int) {
        if (col !in 0 until length) return
        clearPartner(col)
        chars[col] = ' '
        styles[col] = TerminalStyle.PLAIN
        widths[col] = 1
        clearCombining(col)
    }

    fun clearRange(from: Int, to: Int) {
        for (col in from.coerceAtLeast(0) until to.coerceAtMost(length)) clear(col)
    }

    fun clearAll() {
        clearRange(0, length)
        combining = null
    }

    /** Appends a combining mark to the cluster already drawn in [col]. */
    fun addCombining(col: Int, mark: String): Boolean {
        if (col !in 0 until length) return false
        val existing = combining ?: HashMap<Int, String>(4).also { combining = it }
        val prior = existing[col]
        if (prior != null && prior.length + mark.length > MAX_COMBINING_CHARS) return false
        if (prior == null && existing.size >= MAX_COMBINING_CELLS) return false
        existing[col] = (prior ?: "") + mark
        return true
    }

    fun copyFrom(other: TerminalLine) {
        val count = minOf(length, other.length)
        for (col in 0 until count) {
            chars[col] = other.chars[col]
            styles[col] = other.styles[col]
            widths[col] = other.widths[col]
        }
        if (count < length) clearRange(count, length)
        combining = other.combining?.let { HashMap(it) }
    }

    /** Returns the line trimmed of trailing blank cells, for accessibility and tests. */
    fun text(): String {
        var end = length
        while (end > 0 && chars[end - 1] == ' ' && widths[end - 1].toInt() != 2) end--
        val builder = StringBuilder(end + 8)
        var col = 0
        while (col < end) {
            val width = widths[col].toInt()
            if (width == 0) {
                col++
                continue
            }
            builder.append(chars[col])
            combining?.get(col)?.let(builder::append)
            col += if (width == 2) 2 else 1
        }
        return builder.toString()
    }

    fun resize(newCols: Int) {
        if (newCols == length) return
        val priorChars = chars
        val priorStyles = styles
        val priorWidths = widths
        val count = minOf(newCols, length)
        chars = CharArray(newCols) { ' ' }
        styles = IntArray(newCols) { TerminalStyle.PLAIN }
        widths = ByteArray(newCols) { 1 }
        for (col in 0 until count) {
            chars[col] = priorChars[col]
            styles[col] = priorStyles[col]
            widths[col] = priorWidths[col]
        }
        combining = combining?.filterKeys { it < count }?.takeIf { it.isNotEmpty() }?.let { HashMap(it) }
        length = newCols
        // A truncated wide glyph must not leave a dangling continuation cell.
        if (count in 1 until newCols && widths[count - 1].toInt() == 2) clear(count - 1)
        if (newCols > 0 && widths[newCols - 1].toInt() == 2) clear(newCols - 1)
    }

    private fun clearPartner(col: Int) {
        if (col !in 0 until length) return
        val width = widths[col].toInt()
        if (width == 0 && col > 0 && widths[col - 1].toInt() == 2) blank(col - 1)
        if (width == 2 && col + 1 < length && widths[col + 1].toInt() == 0) blank(col + 1)
    }

    private fun blank(col: Int) {
        chars[col] = ' '
        styles[col] = TerminalStyle.PLAIN
        widths[col] = 1
        clearCombining(col)
    }

    private fun clearCombining(col: Int) {
        combining?.remove(col)
    }

    private companion object {
        const val MAX_COMBINING_CHARS = 8
        const val MAX_COMBINING_CELLS = 128
    }
}

/**
 * The visible page of a terminal. A page owns its lines, cursor and the
 * scroll-region margins used by full-screen programs and by tmux.
 */
internal class TerminalPage(var cols: Int, var rows: Int) {
    var lines: ArrayList<TerminalLine> = ArrayList(rows)
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var style: Int = TerminalStyle.PLAIN
    var pendingWrap: Boolean = false
    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    init {
        reset(cols, rows)
    }

    fun reset(cols: Int, rows: Int) {
        this.cols = cols.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        lines = ArrayList(this.rows)
        for (index in 0 until this.rows) lines.add(TerminalLine(this.cols))
        cursorRow = 0
        cursorCol = 0
        style = TerminalStyle.PLAIN
        pendingWrap = false
        scrollTop = 0
        scrollBottom = this.rows - 1
    }

    fun line(row: Int): TerminalLine? = lines.getOrNull(row)

    fun resize(cols: Int, rows: Int) {
        val newCols = cols.coerceAtLeast(1)
        val newRows = rows.coerceAtLeast(1)
        for (line in lines) line.resize(newCols)
        while (lines.size > newRows) lines.removeAt(lines.size - 1)
        while (lines.size < newRows) lines.add(TerminalLine(newCols))
        this.cols = newCols
        this.rows = newRows
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        pendingWrap = false
        scrollTop = 0
        scrollBottom = newRows - 1
    }

    fun fullScrollRegion(): Boolean = scrollTop == 0 && scrollBottom == rows - 1

    fun isWithinRegion(row: Int): Boolean = row in scrollTop..scrollBottom

    fun clampCursor() {
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }
}
