package dev.anthracite.remote.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.max

/**
 * Canvas renderer for a [TerminalSnapshot].
 *
 * Deliberate choices:
 * * No WebView and no new library: the emulator produces a cell grid, and this
 *   view draws runs of equal-styled cells, so a TUI repaint does not allocate a
 *   paragraph of Spannable text.
 * * Hostile output cannot change the clipboard. OSC 52 is blocked in the
 *   emulator; copying is only ever an explicit user action.
 * * The view reports its real cell grid back to the session, which sends an SSH
 *   `window-change` so the host program gets a genuine SIGWINCH.
 * * Accessibility: the terminal is a custom canvas, so it exposes a summary
 *   description and an explicit "copy screen" action, and all special keys are
 *   real focusable buttons in the activity. Drag-to-select is not implemented;
 *   the screen-copy action is the honest fallback.
 */
internal class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Listener {
        fun onGridChanged(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
        fun onInput(bytes: ByteArray)
        fun onUserScroll(offset: Int)
    }

    var listener: Listener? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = context.resources.displayMetrics.density * 13f
        color = TerminalPalette.DEFAULT_FOREGROUND
    }

    private val backgroundPaint = Paint().apply { color = TerminalPalette.DEFAULT_BACKGROUND }
    private val cursorPaint = Paint().apply { color = TerminalPalette.CURSOR }

    private var snapshot: TerminalSnapshot? = null
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private var gridCols = 0
    private var gridRows = 0
    private var scrollOffset = 0
    private var touchDownY = 0f
    private var touchMoved = false
    private var lastCols = 0
    private var lastRows = 0

    var keyModes: KeyModes = KeyModes()
        private set

    var ctrlLatched: Boolean = false
    var altLatched: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(TerminalPalette.DEFAULT_BACKGROUND)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Terminal"
    }

    fun update(snapshot: TerminalSnapshot, modes: KeyModes) {
        this.snapshot = snapshot
        this.keyModes = modes
        val scrollable = snapshot.scrollbackLines
        if (scrollOffset > scrollable) scrollOffset = scrollable
        invalidate()
    }

    fun scrollByLines(lines: Int) {
        val current = snapshot ?: return
        scrollOffset = (scrollOffset + lines).coerceIn(0, current.scrollbackLines)
        listener?.onUserScroll(scrollOffset)
        invalidate()
    }

    fun resetScroll() {
        scrollOffset = 0
        invalidate()
    }

    fun describeScreen(): String {
        val current = snapshot ?: return ""
        val builder = StringBuilder()
        for (row in 0 until current.rows) {
            val line = StringBuilder()
            var column = 0
            while (column < current.cols) {
                val cell = current.cell(row, column)
                val width = TerminalCell.width(cell)
                if (width == 0) {
                    column++
                    continue
                }
                line.append(TerminalCell.char(cell))
                current.combiningAt(row, column)?.let(line::append)
                column += if (width == 2) 2 else 1
            }
            builder.append(line.toString().trimEnd()).append('\n')
        }
        return builder.toString().trimEnd()
    }

    fun copyScreenToClipboard() {
        val text = describeScreen()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val metrics = textPaint.fontMetrics
        cellHeight = max(metrics.descent - metrics.ascent, 1f)
        cellWidth = max(textPaint.measureText("M"), 1f)
        baseline = -metrics.ascent
        gridCols = max((width / cellWidth).toInt(), 2)
        gridRows = max((height / cellHeight).toInt(), 2)
        setMeasuredDimension(width, height)
        contentDescription = "Terminal, $gridCols columns by $gridRows rows"

        if (gridCols != lastCols || gridRows != lastRows) {
            lastCols = gridCols
            lastRows = gridRows
            listener?.onGridChanged(gridCols, gridRows, width, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val current = snapshot ?: return
        val rowCount = minOf(current.rows, gridRows)
        val columnCount = minOf(current.cols, gridCols)
        val runText = StringBuilder()
        var runFg = 0
        var runBg = 0
        var runStart = 0

        fun flushRun(row: Int) {
            if (runText.isEmpty()) return
            textPaint.color = runFg
            canvas.drawText(runText.toString(), runStart * cellWidth, baseline + row * cellHeight, textPaint)
            runText.setLength(0)
        }

        for (row in 0 until rowCount) {
            val top = row * cellHeight
            for (column in 0 until columnCount) {
                val cell = current.cell(row, column)
                val width = TerminalCell.width(cell)
                if (width == 0) continue
                val style = TerminalCell.style(cell)
                val (fg, bg) = TerminalPalette.resolve(style)
                val char = TerminalCell.char(cell)
                val marks = current.combiningAt(row, column)
                if (width == 2 || marks != null) {
                    // Wide and clustered glyphs are drawn on their own with an
                    // explicit background so a fallback font cannot smear them.
                    flushRun(row)
                    val cellLeft = column * cellWidth
                    if (bg != TerminalPalette.DEFAULT_BACKGROUND) {
                        backgroundPaint.color = bg
                        canvas.drawRect(
                            cellLeft,
                            top,
                            cellLeft + cellWidth * width,
                            top + cellHeight,
                            backgroundPaint,
                        )
                        backgroundPaint.color = TerminalPalette.DEFAULT_BACKGROUND
                    }
                    textPaint.color = fg
                    canvas.drawText(char + (marks ?: ""), cellLeft, baseline + top, textPaint)
                    continue
                }
                if (runText.isEmpty()) {
                    runFg = fg
                    runBg = bg
                    runStart = column
                    if (bg != TerminalPalette.DEFAULT_BACKGROUND) {
                        backgroundPaint.color = bg
                        canvas.drawRect(
                            column * cellWidth,
                            top,
                            (column + 1) * cellWidth,
                            top + cellHeight,
                            backgroundPaint,
                        )
                        backgroundPaint.color = TerminalPalette.DEFAULT_BACKGROUND
                    }
                } else if (fg != runFg || bg != runBg) {
                    flushRun(row)
                    runFg = fg
                    runBg = bg
                    runStart = column
                    if (bg != TerminalPalette.DEFAULT_BACKGROUND) {
                        backgroundPaint.color = bg
                        canvas.drawRect(
                            column * cellWidth,
                            top,
                            (column + 1) * cellWidth,
                            top + cellHeight,
                            backgroundPaint,
                        )
                        backgroundPaint.color = TerminalPalette.DEFAULT_BACKGROUND
                    }
                }
                if (TerminalStyle.has(style, TerminalStyle.UNDERLINE) ||
                    TerminalStyle.has(style, TerminalStyle.STRIKE)
                ) {
                    flushRun(row)
                    val y = if (TerminalStyle.has(style, TerminalStyle.STRIKE)) {
                        top + cellHeight * 0.55f
                    } else {
                        top + cellHeight * 0.9f
                    }
                    cursorPaint.color = fg
                    canvas.drawRect(column * cellWidth, y, (column + 1) * cellWidth, y + 1.5f, cursorPaint)
                    cursorPaint.color = TerminalPalette.CURSOR
                }
                runText.append(char)
            }
            flushRun(row)
        }

        if (current.cursorVisible && current.cursorRow >= 0 && current.cursorCol >= 0) {
            val left = current.cursorCol * cellWidth
            val top = current.cursorRow * cellHeight
            cursorPaint.color = TerminalPalette.CURSOR
            canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
            val cell = current.cell(current.cursorRow, current.cursorCol)
            val char = TerminalCell.char(cell)
            if (char != ' ') {
                textPaint.color = TerminalPalette.DEFAULT_BACKGROUND
                canvas.drawText(
                    char.toString(),
                    left,
                    baseline + top,
                    textPaint,
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y
                touchMoved = false
                requestFocus()
                showKeyboard()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = touchDownY - event.y
                if (delta > cellHeight * 1.5f) {
                    touchMoved = true
                    scrollByLines(1)
                    touchDownY = event.y
                } else if (delta < -cellHeight * 1.5f) {
                    touchMoved = true
                    scrollByLines(-1)
                    touchDownY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved) {
                    val column = (event.x / cellWidth).toInt()
                    val row = (event.y / cellHeight).toInt()
                    if (scrollOffset == 0 && mouseEnabled()) sendMouse(column, row)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    private fun mouseEnabled(): Boolean = keyModes.mouseReporting != 0

    private fun sendMouse(column: Int, row: Int) {
        if (!mouseEnabled()) return
        val sgr = keyModes.mouseEncodingSgr
        listener?.onInput(TerminalKeys.mouse(0, column, row, press = true, sgr = sgr))
        listener?.onInput(TerminalKeys.mouse(0, column, row, press = false, sgr = sgr))
    }

    private fun showKeyboard() {
        val manager = context.getSystemService(InputMethodManager::class.java)
        manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val modifiers = modifiersOf(event)
        val effective = KeyModifiers(
            ctrl = modifiers.ctrl || ctrlLatched,
            alt = modifiers.alt || altLatched,
            shift = modifiers.shift,
        )
        val mapped = mapKey(keyCode)
        if (mapped != null) {
            emitKey(mapped, effective)
            resetLatches()
            return true
        }
        // A hardware keyboard delivers printable characters as key events; a soft
        // keyboard delivers them through the input connection. Handling both here
        // keeps one keystroke from producing two bytes.
        val unicode = event.unicodeChar
        if (unicode != 0 && (effective.ctrl || effective.alt)) {
            val char = unicode.toChar()
            if (effective.ctrl) {
                val bytes = TerminalKeys.control(char)
                if (bytes != null) {
                    listener?.onInput(bytes)
                    resetLatches()
                    return true
                }
            }
            listener?.onInput(TerminalKeys.text((if (effective.alt) "\u001b" else "") + char))
            resetLatches()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val mapped = mapKey(keyCode) ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        repeat(repeatCount.coerceIn(1, 64)) { emitKey(mapped, modifiersOf(event)) }
        return true
    }

    private fun modifiersOf(event: KeyEvent): KeyModifiers = KeyModifiers(
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
    )

    private fun emitKey(key: TerminalKey, modifiers: KeyModifiers) {
        listener?.onInput(TerminalKeys.sequence(key, modifiers, keyModes))
    }

    fun sendKey(key: TerminalKey, withCtrl: Boolean = false, withAlt: Boolean = false) {
        val modifiers = KeyModifiers(ctrl = withCtrl || ctrlLatched, alt = withAlt || altLatched)
        listener?.onInput(TerminalKeys.sequence(key, modifiers, keyModes))
        resetLatches()
    }

    fun sendControl(character: Char) {
        val bytes = TerminalKeys.control(character) ?: return
        listener?.onInput(bytes)
        resetLatches()
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        listener?.onInput(TerminalKeys.text(text))
    }

    fun sendPaste(text: String) {
        if (text.isEmpty()) return
        listener?.onInput(TerminalKeys.paste(text, keyModes))
    }

    private fun resetLatches() {
        ctrlLatched = false
        altLatched = false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty() && this@TerminalView.sendTextSafely(value)) return true
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 64)) {
                    listener?.onInput(byteArrayOf(0x7f))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val mapped = mapKey(event.keyCode)
                    if (mapped != null) {
                        emitKey(mapped, modifiersOf(event))
                        return true
                    }
                    if (event.unicodeChar != 0) {
                        val char = event.unicodeChar.toChar()
                        if (char == '\n') {
                            emitKey(TerminalKey.ENTER, modifiersOf(event))
                            return true
                        }
                        return this@TerminalView.sendTextSafely(char.toString())
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    private fun sendTextSafely(text: String): Boolean {
        if (ctrlLatched && text.length == 1) {
            val bytes = TerminalKeys.control(text[0])
            if (bytes != null) {
                listener?.onInput(bytes)
                resetLatches()
                return true
            }
        }
        val prefix = if (altLatched) "\u001b" else ""
        listener?.onInput(TerminalKeys.text(prefix + text))
        resetLatches()
        return true
    }

    private fun mapKey(keyCode: Int): TerminalKey? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
        KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
        KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.ARROW_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.ARROW_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.ARROW_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.ARROW_RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }

}
