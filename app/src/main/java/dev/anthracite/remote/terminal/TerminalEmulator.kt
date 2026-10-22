package dev.anthracite.remote.terminal

/**
 * A bounded, allocation-light VT100/xterm emulator.
 *
 * Design rules that the security model depends on:
 *
 * * Input bytes are untrusted. Every sequence is length-capped, every buffer is
 *   bounded, and unknown or unsupported sequences are consumed and ignored
 *   rather than interpreted.
 * * OSC 52 (clipboard write) is never applied. A blocked attempt is reported so
 *   the UI can tell the user; the phone clipboard is only ever changed by an
 *   explicit user action.
 * * OSC 8 hyperlinks are never opened automatically and only `https` targets are
 *   surfaced; `javascript:`, `file:`, `data:` and control characters are dropped.
 * * Titles and notification text are stripped of control characters and capped.
 * * DCS/PM/APC payloads are consumed and discarded, so a hostile stream cannot
 *   drive the renderer through a parallel protocol.
 *
 * The emulator is not reentrant: the SSH reader thread calls [feed] and the
 * renderer calls [snapshot] on the UI thread, so every public method that
 * touches state is synchronized on the instance.
 */
internal class TerminalEmulator(
    cols: Int = DEFAULT_COLS,
    rows: Int = DEFAULT_ROWS,
    private val scrollbackLimit: Int = DEFAULT_SCROLLBACK,
) {
    /** Safety-relevant terminal events. Nothing here is allowed to fail output rendering. */
    interface Listener {
        /** Reply bytes for a terminal query (device attributes, cursor position). */
        fun onReply(bytes: ByteArray) = Unit

        fun onTitle(title: String) = Unit

        fun onBell() = Unit

        /** A program tried to write the phone clipboard through OSC 52. */
        fun onBlockedClipboardWrite() = Unit

        /** A validated `https` hyperlink from OSC 8. Never opened automatically. */
        fun onHyperlink(uri: String) = Unit
    }

    var listener: Listener = object : Listener {}

    private val primary = TerminalPage(cols, rows)
    private val alternate = TerminalPage(cols, rows)
    private val scrollback = ArrayDeque<TerminalLine>()
    private var altActive = false
    private var saved = SavedCursor()

    private var state = GROUND
    private val params = IntArray(MAX_PARAMS) { -1 }
    private var paramCount = 0
    private var paramValue = -1
    private var privateMarker = 0
    private var intermediate = 0
    private var stringKind = 0
    private var stringOverflowed = false
    private val stringBuffer = StringBuilder(64)

    private var tabStops = BooleanArray(cols)
    private var insertMode = false
    private var newlineMode = false
    private var applicationCursorKeys = false
    private var bracketedPaste = false
    private var cursorVisible = true
    private var autowrap = true
    private var originMode = false
    private var mouseMode = MOUSE_NONE
    private var mouseEncoding = MOUSE_ENCODING_X10
    private var title = ""
    private var revision = 0L

    // Incremental UTF-8 decoder state.
    private val pending = ByteArray(4)
    private var pendingCount = 0
    private var pendingExpected = 0

    var cols: Int = cols.coerceAtLeast(MIN_SIZE)
        private set
    var rows: Int = rows.coerceAtLeast(MIN_SIZE)
        private set

    init {
        resetTabStops()
        primary.style = TerminalStyle.PLAIN
    }

    val active: TerminalPage get() = if (altActive) alternate else primary

    val isAlternateScreen: Boolean get() = altActive

    val usesApplicationCursorKeys: Boolean get() = applicationCursorKeys

    val acceptsBracketedPaste: Boolean get() = bracketedPaste

    val mouseReporting: Int get() = mouseMode

    val mouseEncodingSgr: Boolean get() = mouseEncoding == MOUSE_ENCODING_SGR

    val isCursorVisible: Boolean get() = cursorVisible

    val terminalTitle: String get() = title

    val scrollbackLines: Int get() = scrollback.size

    val changeRevision: Long get() = revision

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Synchronized
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xff
            index++
            if (pendingCount > 0) {
                if (byte and 0xc0 != 0x80) {
                    // Incomplete sequence: emit a replacement and reprocess this byte.
                    pendingCount = 0
                    emitCodepoint(REPLACEMENT)
                    index--
                    continue
                }
                pending[pendingCount++] = byte.toByte()
                if (pendingCount == pendingExpected + 1) {
                    val cp = decodePending()
                    pendingCount = 0
                    emitCodepoint(cp)
                }
                continue
            }
            when {
                byte < 0x80 -> emitCodepoint(byte)
                byte in 0xc2..0xdf -> startSequence(byte, 1)
                byte in 0xe0..0xef -> startSequence(byte, 2)
                byte in 0xf0..0xf4 -> startSequence(byte, 3)
                else -> emitCodepoint(REPLACEMENT)
            }
        }
        revision++
    }

    @Synchronized
    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    /** Drops a split UTF-8 sequence so a reconnect cannot leak it into the next screen. */
    @Synchronized
    fun resetDecoder() {
        pendingCount = 0
    }

    private fun startSequence(byte: Int, continuation: Int) {
        pendingCount = 0
        pending[0] = byte.toByte()
        pendingCount = 1
        pendingExpected = continuation
    }

    private fun decodePending(): Int {
        val size = pendingCount
        val first = pending[0].toInt() and 0xff
        var cp = when (size) {
            2 -> first and 0x1f
            3 -> first and 0x0f
            4 -> first and 0x07
            else -> return REPLACEMENT
        }
        for (index in 1 until size) {
            cp = (cp shl 6) or (pending[index].toInt() and 0x3f)
        }
        val minimum = when (size) {
            2 -> 0x80
            3 -> 0x800
            4 -> 0x10000
            else -> 0
        }
        if (cp < minimum || cp > 0x10ffff || cp in 0xd800..0xdfff) return REPLACEMENT
        return cp
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun emitCodepoint(cp: Int) {
        when (state) {
            OSC_STRING -> {
                when {
                    cp == 0x07 -> finishOsc()
                    cp == 0x18 || cp == 0x1a -> abortString()
                    cp == 0x1b -> state = STRING_ESCAPE
                    cp >= 0x20 && cp != 0x7f -> appendString(cp)
                }
                return
            }
            DCS_STRING -> {
                when {
                    cp == 0x1b -> state = STRING_ESCAPE
                    cp == 0x18 || cp == 0x1a -> abortString()
                    cp == 0x07 -> abortString()
                }
                return
            }
            STRING_ESCAPE -> {
                if (cp == '\\'.code) {
                    if (stateBeforeString == OSC_STRING) finishOsc() else abortString()
                } else {
                    abortString()
                    if (cp == '['.code) beginCsi() else if (cp == 0x1b) state = ESCAPE else state = GROUND
                }
                return
            }
            ESCAPE -> {
                escapeChar(cp)
                return
            }
            ESCAPE_INTERMEDIATE -> {
                if (intermediate == '#'.code && cp == '8'.code) fillWithE()
                state = GROUND
                intermediate = 0
                return
            }
            CSI_PARAM, CSI_INTERMEDIATE -> {
                csiChar(cp)
                return
            }
            CSI_IGNORE -> {
                if (cp in 0x40..0x7e) state = GROUND
                return
            }
        }
        groundChar(cp)
    }

    private fun groundChar(cp: Int) {
        if (cp < 0x20) {
            control(cp)
            return
        }
        if (cp == 0x7f) return
        if (cp in 0x80..0x9f) {
            when (cp) {
                0x9b -> beginCsi()
                0x9d -> beginString(OSC_STRING)
                0x90 -> beginString(DCS_STRING)
                0x9c -> state = GROUND
                else -> Unit
            }
            return
        }
        putCodepoint(cp)
    }

    private fun control(cp: Int) {
        val page = active
        when (cp) {
            0x07 -> listener.onBell()
            0x08 -> {
                page.pendingWrap = false
                if (page.cursorCol > 0) page.cursorCol--
            }
            0x09 -> {
                page.pendingWrap = false
                page.cursorCol = nextTabStop(page.cursorCol)
            }
            0x0a, 0x0b, 0x0c -> {
                lineFeed()
                if (newlineMode) page.cursorCol = 0
            }
            0x0d -> {
                page.cursorCol = 0
                page.pendingWrap = false
            }
            0x18, 0x1a -> state = GROUND
            0x1b -> {
                state = ESCAPE
                intermediate = 0
            }
            else -> Unit
        }
    }

    private fun escapeChar(cp: Int) {
        when (cp) {
            '['.code -> beginCsi()
            ']'.code -> beginString(OSC_STRING)
            'P'.code, '^'.code, '_'.code, 'X'.code -> beginString(DCS_STRING)
            '7'.code -> {
                saveCursor()
                state = GROUND
            }
            '8'.code -> {
                restoreCursor()
                state = GROUND
            }
            'D'.code -> {
                lineFeed()
                state = GROUND
            }
            'E'.code -> {
                active.cursorCol = 0
                lineFeed()
                state = GROUND
            }
            'M'.code -> {
                reverseIndex()
                state = GROUND
            }
            'H'.code -> {
                setTabStop()
                state = GROUND
            }
            'c'.code -> {
                hardReset()
                state = GROUND
            }
            '='.code, '>'.code -> state = GROUND
            '('.code, ')'.code, '*'.code, '+'.code, '#'.code, '%'.code -> {
                intermediate = cp
                state = ESCAPE_INTERMEDIATE
            }
            else -> state = GROUND
        }
    }

    private fun beginCsi() {
        state = CSI_PARAM
        paramCount = 0
        paramValue = -1
        privateMarker = 0
        intermediate = 0
        for (index in params.indices) params[index] = -1
    }

    private fun csiChar(cp: Int) {
        when {
            cp in '0'.code..'9'.code -> {
                if (paramCount < params.size) {
                    val digit = cp - '0'.code
                    paramValue = if (paramValue < 0) digit else (paramValue * 10 + digit).coerceAtMost(MAX_PARAM_VALUE)
                }
                state = CSI_PARAM
            }
            cp == ';'.code || cp == ':'.code -> {
                pushParam()
                state = CSI_PARAM
            }
            cp in 0x3c..0x3f -> {
                privateMarker = cp
                state = CSI_PARAM
            }
            cp in 0x20..0x2f -> {
                intermediate = cp
                state = CSI_INTERMEDIATE
            }
            cp in 0x40..0x7e -> {
                pushParam()
                state = GROUND
                dispatchCsi(cp)
            }
            else -> state = CSI_IGNORE
        }
    }

    private fun pushParam() {
        if (paramCount < params.size) params[paramCount] = paramValue
        paramCount++
        paramValue = -1
    }

    private fun param(index: Int, fallback: Int): Int {
        val value = params.getOrElse(index) { -1 }
        return if (value < 0) fallback else value
    }

    private fun count(index: Int): Int {
        val value = params.getOrElse(index) { -1 }
        return when {
            value < 0 -> 1
            value == 0 -> 1
            else -> value.coerceAtMost(MAX_PARAM_VALUE)
        }
    }

    private fun dispatchCsi(final: Int) {
        if (intermediate != 0) {
            // The only intermediate form this emulator honours is DECSTR (`CSI ! p`).
            if (intermediate == '!'.code && final == 'p'.code) softReset()
            return
        }
        val page = active
        when (final.toChar()) {
            'A' -> moveCursor(-count(0), 0)
            'B' -> moveCursor(count(0), 0)
            'C' -> moveCursor(0, count(0))
            'D' -> moveCursor(0, -count(0))
            'E' -> moveCursor(count(0), -page.cursorCol)
            'F' -> moveCursor(-count(0), -page.cursorCol)
            'G', '`' -> setColumn(count(0) - 1)
            'H', 'f' -> setCursor(param(0, 1) - 1, param(1, 1) - 1)
            'I' -> {
                page.pendingWrap = false
                repeat(count(0)) { page.cursorCol = nextTabStop(page.cursorCol) }
            }
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'L' -> insertLines(count(0))
            'M' -> deleteLines(count(0))
            'P' -> deleteChars(count(0))
            '@' -> insertChars(count(0))
            'S' -> scrollUp(count(0))
            'T' -> scrollDown(count(0))
            'X' -> eraseChars(count(0))
            'Z' -> {
                page.pendingWrap = false
                repeat(count(0)) { page.cursorCol = previousTabStop(page.cursorCol) }
            }
            'b' -> {
                val last = if (page.cursorCol > 0) page.line(page.cursorRow)?.let { line ->
                    line.charAt(page.cursorCol - 1)
                } else null
                if (last != null) repeat(count(0).coerceAtMost(cols)) { putCodepoint(last.code) }
            }
            'c' -> deviceAttributes()
            'd' -> setCursor(count(0) - 1, page.cursorCol)
            'g' -> clearTabStops(param(0, 0))
            'h' -> setModes(true)
            'l' -> setModes(false)
            'm' -> if (privateMarker == 0 && intermediate == 0) applySgr()
            'n' -> deviceStatusReport()
            'r' -> setScrollRegion()
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Cursor, erase, scroll
    // ------------------------------------------------------------------

    private fun moveCursor(rowDelta: Int, colDelta: Int) {
        val page = active
        page.pendingWrap = false
        val minRow = if (originMode) page.scrollTop else 0
        val maxRow = if (originMode) page.scrollBottom else rows - 1
        page.cursorRow = (page.cursorRow + rowDelta).coerceIn(minRow, maxRow)
        page.cursorCol = (page.cursorCol + colDelta).coerceIn(0, cols - 1)
    }

    private fun setCursor(row: Int, col: Int) {
        val page = active
        page.pendingWrap = false
        val minRow = if (originMode) page.scrollTop else 0
        val maxRow = if (originMode) page.scrollBottom else rows - 1
        val target = if (originMode) minRow + row else row
        page.cursorRow = target.coerceIn(minRow, maxRow)
        page.cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun setColumn(col: Int) {
        active.pendingWrap = false
        active.cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun eraseDisplay(mode: Int) {
        val page = active
        when (mode) {
            0 -> {
                eraseLine(0)
                for (row in page.cursorRow + 1 until rows) page.line(row)?.clearAll()
            }
            1 -> {
                for (row in 0 until page.cursorRow) page.line(row)?.clearAll()
                eraseLine(1)
            }
            2 -> for (row in 0 until rows) page.line(row)?.clearAll()
            3 -> {
                scrollback.clear()
                for (row in 0 until rows) page.line(row)?.clearAll()
            }
        }
        page.pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val page = active
        val line = page.line(page.cursorRow) ?: return
        when (mode) {
            0 -> line.clearRange(page.cursorCol, cols)
            1 -> line.clearRange(0, page.cursorCol + 1)
            2 -> line.clearAll()
        }
        page.pendingWrap = false
    }

    private fun eraseChars(num: Int) {
        val page = active
        val line = page.line(page.cursorRow) ?: return
        line.clearRange(page.cursorCol, (page.cursorCol + num).coerceAtMost(cols))
        page.pendingWrap = false
    }

    private fun insertChars(num: Int) {
        val page = active
        val line = page.line(page.cursorRow) ?: return
        val amount = num.coerceAtMost(cols - page.cursorCol)
        if (amount <= 0) return
        var col = cols - 1
        while (col >= page.cursorCol + amount) {
            line.setCell(col, line.charAt(col - amount), line.widthAt(col - amount), line.styleAt(col - amount))
            line.combiningAt(col - amount)?.let { line.addCombining(col, it) }
            col--
        }
        line.clearRange(page.cursorCol, page.cursorCol + amount)
        page.pendingWrap = false
    }

    private fun deleteChars(num: Int) {
        val page = active
        val line = page.line(page.cursorRow) ?: return
        val amount = num.coerceAtMost(cols - page.cursorCol)
        if (amount <= 0) return
        var col = page.cursorCol
        while (col < cols) {
            val source = col + amount
            if (source < cols) {
                line.setCell(col, line.charAt(source), line.widthAt(source), line.styleAt(source))
                line.combiningAt(source)?.let { line.addCombining(col, it) }
            } else {
                line.clear(col)
            }
            col++
        }
        page.pendingWrap = false
    }

    private fun insertLines(num: Int) {
        val page = active
        if (!page.isWithinRegion(page.cursorRow)) return
        val amount = num.coerceAtMost(page.scrollBottom - page.cursorRow + 1)
        repeat(amount) {
            page.lines.removeAt(page.scrollBottom)
            page.lines.add(page.cursorRow, TerminalLine(cols))
        }
        page.pendingWrap = false
    }

    private fun deleteLines(num: Int) {
        val page = active
        if (!page.isWithinRegion(page.cursorRow)) return
        repeat(num.coerceAtMost(page.scrollBottom - page.cursorRow + 1)) {
            page.lines.removeAt(page.cursorRow)
            page.lines.add(page.scrollBottom, TerminalLine(cols))
        }
        page.pendingWrap = false
    }

    private fun lineFeed() {
        val page = active
        page.pendingWrap = false
        if (page.cursorRow == page.scrollBottom) scrollUp(1) else if (page.cursorRow < rows - 1) page.cursorRow++
    }

    private fun reverseIndex() {
        val page = active
        page.pendingWrap = false
        if (page.cursorRow == page.scrollTop) scrollDown(1) else if (page.cursorRow > 0) page.cursorRow--
    }

    private fun scrollUp(num: Int) {
        val page = active
        val amount = num.coerceAtMost(page.scrollBottom - page.scrollTop + 1)
        repeat(amount) {
            val removed = page.lines.removeAt(page.scrollTop)
            page.lines.add(page.scrollBottom, TerminalLine(cols))
            if (!altActive && page.fullScrollRegion() && page.scrollTop == 0) {
                scrollback.addLast(removed)
                while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
            }
        }
    }

    private fun scrollDown(num: Int) {
        val page = active
        val amount = num.coerceAtMost(page.scrollBottom - page.scrollTop + 1)
        repeat(amount) {
            page.lines.removeAt(page.scrollBottom)
            page.lines.add(page.scrollTop, TerminalLine(cols))
        }
    }

    private fun setScrollRegion() {
        val page = active
        val top = param(0, 1) - 1
        val bottom = param(1, rows) - 1
        if (top < 0 || bottom >= rows || top >= bottom) {
            page.scrollTop = 0
            page.scrollBottom = rows - 1
        } else {
            page.scrollTop = top
            page.scrollBottom = bottom
        }
        setCursor(0, 0)
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private fun putCodepoint(cp: Int) {
        val page = active
        val width = characterWidth(cp)
        if (width == 0) {
            val target = if (page.pendingWrap) page.cursorCol else page.cursorCol - 1
            if (target >= 0) {
                val line = page.line(page.cursorRow)
                line?.addCombining(target, String(Character.toChars(cp)))
            }
            return
        }
        if (page.pendingWrap) {
            page.pendingWrap = false
            page.cursorCol = 0
            lineFeed()
        }
        if (width == 2 && page.cursorCol + 2 > cols) {
            if (!autowrap) return
            page.cursorCol = 0
            lineFeed()
        }
        val line = page.line(page.cursorRow) ?: return
        if (insertMode) insertChars(width)
        val chars = Character.toChars(cp)
        line.setCell(page.cursorCol, chars[0], width, page.style)
        if (chars.size > 1) line.addCombining(page.cursorCol, String(chars, 1, 1))
        if (width == 2) {
            page.cursorCol += 2
            if (page.cursorCol >= cols) {
                page.cursorCol = cols - 1
                if (autowrap) page.pendingWrap = true
            }
        } else {
            if (page.cursorCol == cols - 1) {
                if (autowrap) page.pendingWrap = true
            } else {
                page.cursorCol++
            }
        }
        if (width > 0) revision++
    }

    // ------------------------------------------------------------------
    // Modes and attributes
    // ------------------------------------------------------------------

    private fun setModes(enabled: Boolean) {
        if (privateMarker == '?'.code) {
            for (index in 0 until paramCount) {
                val mode = params.getOrElse(index) { -1 }
                when (mode) {
                    1 -> applicationCursorKeys = enabled
                    6 -> {
                        originMode = enabled
                        setCursor(0, 0)
                    }
                    7 -> autowrap = enabled
                    25 -> cursorVisible = enabled
                    47, 1047 -> setAlternate(enabled, clearOnEnter = false)
                    1049 -> {
                        if (enabled) {
                            saveCursor()
                            setAlternate(true, clearOnEnter = true)
                        } else {
                            setAlternate(false, clearOnEnter = false)
                            restoreCursor()
                        }
                    }
                    1000 -> mouseMode = if (enabled) MOUSE_X10 else MOUSE_NONE
                    1002, 1003 -> mouseMode = if (enabled) MOUSE_DRAG else MOUSE_NONE
                    1006, 1016 -> mouseEncoding = if (enabled) MOUSE_ENCODING_SGR else MOUSE_ENCODING_X10
                    2004 -> bracketedPaste = enabled
                    else -> Unit
                }
            }
            return
        }
        for (index in 0 until paramCount) {
            when (params.getOrElse(index) { -1 }) {
                4 -> insertMode = enabled
                20 -> newlineMode = enabled
                else -> Unit
            }
        }
    }

    private fun setAlternate(enabled: Boolean, clearOnEnter: Boolean) {
        if (enabled == altActive) return
        altActive = enabled
        if (enabled) {
            if (clearOnEnter) alternate.reset(cols, rows)
            alternate.cursorRow = 0
            alternate.cursorCol = 0
        }
    }

    private fun applySgr() {
        val page = active
        if (paramCount == 0) {
            page.style = TerminalStyle.PLAIN
            return
        }
        var index = 0
        while (index < paramCount) {
            val code = params.getOrElse(index) { -1 }
            index++
            when {
                code < 0 || code == 0 -> page.style = TerminalStyle.PLAIN
                code == 1 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.BOLD)
                code == 2 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.DIM)
                code == 3 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.ITALIC)
                code == 4 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.UNDERLINE)
                code == 5 || code == 6 ->
                    page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.BLINK)
                code == 7 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.INVERSE)
                code == 8 -> Unit
                code == 9 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) or TerminalStyle.STRIKE)
                code == 21 || code == 22 ->
                    page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and (TerminalStyle.BOLD or TerminalStyle.DIM).inv())
                code == 23 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and TerminalStyle.ITALIC.inv())
                code == 24 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and TerminalStyle.UNDERLINE.inv())
                code == 25 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and TerminalStyle.BLINK.inv())
                code == 27 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and TerminalStyle.INVERSE.inv())
                code == 28 -> Unit
                code == 29 -> page.style = TerminalStyle.withFlags(page.style, TerminalStyle.flags(page.style) and TerminalStyle.STRIKE.inv())
                code in 30..37 -> page.style = TerminalStyle.withForeground(page.style, code - 30)
                code == 39 -> page.style = TerminalStyle.withForeground(page.style, TerminalStyle.DEFAULT)
                code in 40..47 -> page.style = TerminalStyle.withBackground(page.style, code - 40)
                code == 49 -> page.style = TerminalStyle.withBackground(page.style, TerminalStyle.DEFAULT)
                code in 90..97 -> page.style = TerminalStyle.withForeground(page.style, code - 90 + 8)
                code in 100..107 -> page.style = TerminalStyle.withBackground(page.style, code - 100 + 8)
                code == 38 || code == 48 -> {
                    val parsed = parseExtendedColor(index)
                    index = parsed.next
                    when (parsed.kind) {
                        COLOR_PALETTE -> {
                            if (parsed.value >= 0) {
                                page.style = if (code == 38) {
                                    TerminalStyle.withForeground(page.style, parsed.value)
                                } else {
                                    TerminalStyle.withBackground(page.style, parsed.value)
                                }
                            }
                        }
                        COLOR_RGB -> {
                            val palette = TerminalStyle.toPalette(parsed.red, parsed.green, parsed.blue)
                            page.style = if (code == 38) {
                                TerminalStyle.withForeground(page.style, palette)
                            } else {
                                TerminalStyle.withBackground(page.style, palette)
                            }
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private fun parseExtendedColor(start: Int): ExtendedColor {
        if (start >= paramCount) return ExtendedColor(COLOR_NONE, 0, 0, 0, start)
        val mode = params[start]
        return when (mode) {
            EXT_MODE_PALETTE -> {
                val value = params.getOrElse(start + 1) { -1 }
                ExtendedColor(COLOR_PALETTE, value.coerceIn(-1, 255), 0, 0, start + 2)
            }
            EXT_MODE_RGB -> {
                // Both `38;2;r;g;b` and `38:2::r:g:b` reduce to this form, where an
                // empty colour-space id in the colon form is the -1 placeholder.
                var base = start + 1
                if (base < paramCount && params[base] < 0) base++
                if (base + 2 >= paramCount) {
                    // Not enough components: consume what is present.
                    return ExtendedColor(COLOR_NONE, 0, 0, 0, paramCount)
                }
                val red = params.getOrElse(base) { 0 }.coerceIn(0, 255)
                val green = params.getOrElse(base + 1) { 0 }.coerceIn(0, 255)
                val blue = params.getOrElse(base + 2) { 0 }.coerceIn(0, 255)
                ExtendedColor(COLOR_RGB, 0, red, green, blue, base + 3)
            }
            else -> ExtendedColor(COLOR_NONE, 0, 0, 0, start + 1)
        }
    }

    private fun deviceAttributes() {
        val reply = if (privateMarker == '>'.code) "\u001b[>0;276;0c" else "\u001b[?62;22c"
        listener.onReply(reply.toByteArray(Charsets.US_ASCII))
    }

    private fun deviceStatusReport() {
        val requested = param(0, 0)
        when (requested) {
            5 -> listener.onReply("\u001b[0n".toByteArray(Charsets.US_ASCII))
            6 -> {
                val page = active
                val row = (page.cursorRow + 1).coerceAtLeast(1)
                val col = (page.cursorCol + 1).coerceAtLeast(1)
                listener.onReply("\u001b[$row;${col}R".toByteArray(Charsets.US_ASCII))
            }
        }
    }

    private fun saveCursor() {
        val page = active
        saved = SavedCursor(page.cursorRow, page.cursorCol, page.style, page.pendingWrap, originMode)
    }

    private fun restoreCursor() {
        val page = active
        page.cursorRow = saved.row.coerceIn(0, rows - 1)
        page.cursorCol = saved.col.coerceIn(0, cols - 1)
        page.style = saved.style
        page.pendingWrap = saved.pendingWrap
        originMode = saved.originMode
    }

    private fun softReset() {
        val page = active
        page.style = TerminalStyle.PLAIN
        insertMode = false
        originMode = false
        autowrap = true
        applicationCursorKeys = false
        cursorVisible = true
        page.scrollTop = 0
        page.scrollBottom = rows - 1
        page.pendingWrap = false
    }

    private fun hardReset() {
        primary.reset(cols, rows)
        alternate.reset(cols, rows)
        altActive = false
        scrollback.clear()
        resetTabStops()
        saved = SavedCursor()
        insertMode = false
        newlineMode = false
        applicationCursorKeys = false
        bracketedPaste = false
        cursorVisible = true
        autowrap = true
        originMode = false
        mouseMode = MOUSE_NONE
        mouseEncoding = MOUSE_ENCODING_X10
        state = GROUND
        pendingCount = 0
        revision++
    }

    private fun fillWithE() {
        val page = active
        for (row in 0 until rows) {
            val line = page.line(row) ?: continue
            line.clearAll()
            for (col in 0 until cols) line.setCell(col, 'E', 1, TerminalStyle.PLAIN)
        }
        page.cursorRow = 0
        page.cursorCol = 0
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private fun resetTabStops() {
        tabStops = BooleanArray(cols) { it % TAB_WIDTH == 0 }
    }

    private fun setTabStop() {
        if (active.cursorCol in tabStops.indices) tabStops[active.cursorCol] = true
    }

    private fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> if (active.cursorCol in tabStops.indices) tabStops[active.cursorCol] = false
            3 -> tabStops = BooleanArray(cols)
        }
    }

    private fun nextTabStop(from: Int): Int {
        for (col in from + 1 until cols) if (col in tabStops.indices && tabStops[col]) return col
        return cols - 1
    }

    private fun previousTabStop(from: Int): Int {
        for (col in from - 1 downTo 0) if (col in tabStops.indices && tabStops[col]) return col
        return 0
    }

    // ------------------------------------------------------------------
    // Strings (OSC / DCS)
    // ------------------------------------------------------------------

    private var stateBeforeString = 0

    private fun beginString(kind: Int) {
        stringKind = kind
        stateBeforeString = kind
        stringBuffer.setLength(0)
        stringOverflowed = false
        state = kind
        intermediate = 0
    }

    private fun appendString(cp: Int) {
        if (stringKind != OSC_STRING) return
        if (stringBuffer.length >= MAX_OSC_CHARS) {
            stringOverflowed = true
            return
        }
        stringBuffer.appendCodePoint(cp)
    }

    private fun abortString() {
        stringBuffer.setLength(0)
        stringOverflowed = false
        state = GROUND
    }

    private fun finishOsc() {
        if (stringKind == OSC_STRING && !stringOverflowed) handleOsc(stringBuffer.toString())
        stringBuffer.setLength(0)
        stringOverflowed = false
        state = GROUND
    }

    private fun handleOsc(payload: String) {
        val separator = payload.indexOf(';')
        if (separator <= 0) return
        val code = payload.substring(0, separator).trim()
        val body = payload.substring(separator + 1)
        when (code) {
            "0", "1", "2" -> {
                val clean = sanitizeText(body, MAX_TITLE_CHARS)
                title = clean
                listener.onTitle(clean)
            }
            "8" -> {
                // Hyperlinks never activate automatically; only the validated
                // https form reaches the UI, which still requires a user action.
                val uri = body.substringAfter(';', "")
                sanitizeHyperlink(uri)?.let(listener::onHyperlink)
            }
            "52" -> listener.onBlockedClipboardWrite()
            else -> Unit
        }
    }

    private fun sanitizeText(value: String, limit: Int): String {
        val builder = StringBuilder(minOf(value.length, limit))
        for (char in value) {
            if (builder.length >= limit) break
            if (char.code < 0x20 || char.code == 0x7f) continue
            builder.append(char)
        }
        return builder.toString().trim()
    }

    // ------------------------------------------------------------------
    // View of the screen
    // ------------------------------------------------------------------

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceAtLeast(MIN_SIZE)
        val targetRows = newRows.coerceAtLeast(MIN_SIZE)
        if (targetCols == cols && targetRows == rows) return
        primary.resize(targetCols, targetRows)
        alternate.resize(targetCols, targetRows)
        for (line in scrollback) line.resize(targetCols)
        cols = targetCols
        rows = targetRows
        resetTabStops()
        revision++
    }

    /**
     * Copies the visible page for rendering. [scrollOffset] is the number of
     * history lines the user has scrolled back; the live screen is 0.
     */
    @Synchronized
    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot {
        val offset = scrollOffset.coerceIn(0, scrollback.size)
        val page = active
        val cells = Array(rows) { LongArray(cols) }
        var combining: HashMap<Int, String>? = null
        for (row in 0 until rows) {
            val line = lineFor(row, offset)
            val target = cells[row]
            if (line == null) {
                java.util.Arrays.fill(target, TerminalCell.BLANK)
                continue
            }
            for (col in 0 until cols) {
                target[col] = TerminalCell.pack(line.charAt(col), line.widthAt(col), line.styleAt(col))
                val marks = line.combiningAt(col)
                if (!marks.isNullOrEmpty()) {
                    val map = combining ?: HashMap<Int, String>(8).also { combining = it }
                    map[row * cols + col] = marks
                }
            }
        }
        return TerminalSnapshot(
            cols = cols,
            rows = rows,
            cells = cells,
            combining = combining ?: emptyMap(),
            cursorRow = if (offset == 0) page.cursorRow else -1,
            cursorCol = if (offset == 0) page.cursorCol else -1,
            cursorVisible = cursorVisible && offset == 0,
            alternate = altActive,
            scrollbackLines = scrollback.size,
            scrollOffset = offset,
        )
    }

    private fun lineFor(row: Int, offset: Int): TerminalLine? {
        val absolute = row - offset
        if (absolute >= 0) return active.line(absolute)
        val index = scrollback.size + absolute
        return scrollback.getOrNull(index)
    }

    /** Live screen text with trailing blanks trimmed; used by tests and accessibility summaries. */
    @Synchronized
    fun screenText(scrollOffset: Int = 0): String {
        val offset = scrollOffset.coerceIn(0, scrollback.size)
        return (0 until rows).joinToString("\n") { row ->
            lineFor(row, offset)?.text() ?: ""
        }
    }

    @Synchronized
    fun lineText(row: Int): String = active.line(row)?.text() ?: ""

    @Synchronized
    fun reset() = hardReset()

    fun characterWidth(cp: Int): Int {
        if (cp == 0 || cp == 0x7f) return 0
        if (cp < 0x20) return 0
        if (cp in 0x200b..0x200f || cp == 0xfeff) return 0
        val type = Character.getType(cp)
        if (
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return 0
        }
        return if (isWide(cp)) 2 else 1
    }

    private fun isWide(cp: Int): Boolean {
        if (Character.isIdeographic(cp)) return true
        return when (cp) {
            in 0x1100..0x115f,
            in 0x2e80..0x303e,
            in 0x3041..0x33ff,
            in 0xa960..0xa97f,
            in 0xac00..0xd7a3,
            in 0xfe10..0xfe19,
            in 0xfe30..0xfe6f,
            in 0xff00..0xff60,
            in 0xffe0..0xffe6,
            in 0x1f300..0x1f64f,
            in 0x1f680..0x1f6ff,
            in 0x1f7e0..0x1f7eb,
            in 0x1f90c..0x1f9ff,
            in 0x1fa70..0x1faff,
            in 0x20000..0x3fffd,
            -> true
            else -> false
        }
    }

    private data class SavedCursor(
        val row: Int = 0,
        val col: Int = 0,
        val style: Int = TerminalStyle.PLAIN,
        val pendingWrap: Boolean = false,
        val originMode: Boolean = false,
    )

    private data class ExtendedColor(
        val kind: Int,
        val value: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val next: Int,
    )

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_SCROLLBACK = 2000
        const val MIN_SIZE = 2
        const val MAX_PARAMS = 24
        const val MAX_PARAM_VALUE = 65_535
        const val MAX_OSC_CHARS = 2048
        const val MAX_TITLE_CHARS = 120
        const val MAX_HYPERLINK_CHARS = 512
        const val TAB_WIDTH = 8
        const val REPLACEMENT = 0xfffd

        const val GROUND = 0
        const val ESCAPE = 1
        const val ESCAPE_INTERMEDIATE = 2
        const val CSI_PARAM = 3
        const val CSI_INTERMEDIATE = 4
        const val CSI_IGNORE = 5
        const val OSC_STRING = 6
        const val DCS_STRING = 7
        const val STRING_ESCAPE = 8

        const val MOUSE_NONE = 0
        const val MOUSE_X10 = 1
        const val MOUSE_DRAG = 2
        const val MOUSE_ENCODING_X10 = 0
        const val MOUSE_ENCODING_SGR = 1

        const val COLOR_NONE = 0
        const val COLOR_PALETTE = 1
        const val COLOR_RGB = 2

        const val EXT_MODE_PALETTE = 5
        const val EXT_MODE_RGB = 2
    }
}

/** Immutable render input produced by [TerminalEmulator.snapshot]. */
internal class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    val cells: Array<LongArray>,
    private val combining: Map<Int, String>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val alternate: Boolean,
    val scrollbackLines: Int,
    val scrollOffset: Int,
) {
    fun cell(row: Int, col: Int): Long =
        cells.getOrNull(row)?.getOrNull(col) ?: TerminalCell.BLANK

    fun combiningAt(row: Int, col: Int): String? = combining[row * cols + col]
}

/**
 * Validates an OSC 8 hyperlink. Only `https` is accepted, the payload is capped,
 * and every control or whitespace character is rejected so a link cannot be used
 * to smuggle a second URI or to spoof the visible target.
 */
internal fun sanitizeHyperlink(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > 512) return null
    if (value.any { char -> char.code <= 0x20 || char.code == 0x7f }) return null
    val lower = value.lowercase()
    if (!lower.startsWith("https://")) return null
    return value
}
