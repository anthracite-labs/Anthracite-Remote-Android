import Foundation

/// A terminal colour: the default, one of 256 palette entries, or true colour.
public enum TerminalColor: Equatable, Sendable {
    case `default`
    case indexed(UInt8)
    case rgb(UInt8, UInt8, UInt8)
}

/// Visual attributes of a cell. Nothing here is interpreted as markup.
public struct TerminalStyle: Equatable, Sendable {
    public var foreground: TerminalColor = .default
    public var background: TerminalColor = .default
    public var bold = false
    public var faint = false
    public var italic = false
    public var underline = false
    public var reverse = false

    public init() {}

    public init(
        foreground: TerminalColor = .default,
        background: TerminalColor = .default,
        bold: Bool = false,
        faint: Bool = false,
        italic: Bool = false,
        underline: Bool = false,
        reverse: Bool = false
    ) {
        self.foreground = foreground
        self.background = background
        self.bold = bold
        self.faint = faint
        self.italic = italic
        self.underline = underline
        self.reverse = reverse
    }

    public var isEmpty: Bool {
        foreground == .default && background == .default && !bold && !faint && !italic
            && !underline && !reverse
    }
}

/// One grid cell. `width == 0` marks the right half of a double-width glyph.
public struct TerminalCell: Equatable, Sendable {
    public var scalar: UInt32
    public var width: Int
    public var style: TerminalStyle

    public static let blank = TerminalCell(scalar: 32, width: 1, style: TerminalStyle())
}

/// An immutable view for the renderer.
public struct TerminalSnapshot: Sendable {
    public let columns: Int
    public let rows: Int
    public let cursorRow: Int
    public let cursorColumn: Int
    public let cursorVisible: Bool
    public let title: String
    public let altScreen: Bool
    public let bracketedPaste: Bool
    public let applicationCursorKeys: Bool
    public let scrollback: [[TerminalCell]]
    public let line: [[TerminalCell]]

    public var allLines: [[TerminalCell]] { scrollback + line }
}

/// A bounded VT100/xterm emulator.
///
/// Input bytes are untrusted. Every sequence is length-capped, every buffer is
/// bounded, and unknown sequences are consumed and ignored rather than guessed
/// at. OSC 52 (clipboard write) is never applied, OSC 8 hyperlinks are surfaced
/// to the UI but never opened, and titles have control characters removed.
///
/// Like the Android implementation this is deliberately single-threaded: the
/// owner must call `feed` and `snapshot` from the same actor/serial queue.
public final class TerminalScreen {
    public private(set) var columns: Int
    public private(set) var rows: Int
    public private(set) var title = ""

    /// Called when a program tried to write the clipboard through OSC 52.
    public var onBlockedClipboardWrite: (() -> Void)?
    /// Replies for terminal queries (device attributes, cursor position).
    public var onReply: ((Data) -> Void)?
    /// A validated `https` hyperlink. Never opened automatically.
    public var onHyperlink: ((String) -> Void)?
    /// The bell. The UI decides whether to make a sound.
    public var onBell: (() -> Void)?

    private var primary: [[TerminalCell]]
    private var alternate: [[TerminalCell]]
    private var scrollback: [[TerminalCell]] = []
    private let scrollbackLimit: Int

    private var row = 0
    private var column = 0
    private var pendingWrap = false
    private var altActive = false
    private var cursorVisible = true
    private var applicationCursorKeys = false
    private var bracketedPaste = false
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var scrollTop = 0
    private var scrollBottom = 0

    private var style = TerminalStyle()
    private var savedRow = 0
    private var savedColumn = 0
    private var savedStyle = TerminalStyle()

    private enum ParseState {
        case ground
        case escape
        case csi
        case osc
        case string
    }

    private var state: ParseState = .ground
    private var params: [Int] = []
    private var currentParam: Int = -1
    private var privateMarker: UInt8 = 0
    private var intermediate: UInt8 = 0
    private var stringKind: UInt8 = 0
    private var stringBuffer: [UInt8] = []
    private var stringOverflowed = false

    private var utf8Buffer: [UInt8] = []
    private var utf8Expected = 0

    private static let maxParams = 16
    private static let maxString = 2048

    public init(columns: Int = 80, rows: Int = 24, scrollbackLimit: Int = 2000) {
        self.columns = max(2, min(columns, 400))
        self.rows = max(2, min(rows, 200))
        self.scrollbackLimit = max(0, min(scrollbackLimit, 10000))
        self.primary = Array(repeating: Self.blankLine(columns), count: rows)
        self.alternate = Array(repeating: Self.blankLine(columns), count: rows)
        self.scrollBottom = rows - 1
    }

    // MARK: - Public API

    public func feed(_ data: Data) {
        feed(Array(data))
    }

    public func feed(_ bytes: [UInt8]) {
        for byte in bytes {
            switch state {
            case .ground:
                ground(byte)
            case .escape:
                escape(byte)
            case .csi:
                csi(byte)
            case .osc, .string:
                stringByte(byte)
            }
        }
    }

    public func resize(columns newColumns: Int, rows newRows: Int) {
        let targetColumns = max(2, min(newColumns, 400))
        let targetRows = max(2, min(newRows, 200))
        if targetColumns != columns {
            columns = targetColumns
            primary = primary.map { line in Self.refit(line, to: columns) }
            alternate = alternate.map { line in Self.refit(line, to: columns) }
            scrollback = scrollback.map { line in Self.refit(line, to: columns) }
        }
        if targetRows != rows {
            if targetRows < rows {
                let drop = rows - targetRows
                if altActive {
                    alternate.removeFirst(min(drop, alternate.count))
                    while alternate.count < targetRows { alternate.append(Self.blankLine(columns)) }
                } else {
                    // The disappearing lines above the cursor belong in the
                    // scrollback: they were output, not noise.
                    for line in primary.prefix(min(drop, row)) { appendScrollback(line) }
                    primary.removeFirst(min(drop, primary.count))
                    while primary.count < targetRows { primary.append(Self.blankLine(columns)) }
                    row = max(0, min(row - drop, targetRows - 1))
                }
            } else {
                let add = targetRows - rows
                if altActive {
                    for _ in 0..<add { alternate.append(Self.blankLine(columns)) }
                } else {
                    for _ in 0..<add { primary.append(Self.blankLine(columns)) }
                }
            }
            rows = targetRows
        }
        row = min(row, rows - 1)
        column = min(column, columns - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    public func snapshot() -> TerminalSnapshot {
        TerminalSnapshot(
            columns: columns,
            rows: rows,
            cursorRow: row,
            cursorColumn: column,
            cursorVisible: cursorVisible,
            title: title,
            altScreen: altActive,
            bracketedPaste: bracketedPaste,
            applicationCursorKeys: applicationCursorKeys,
            scrollback: scrollback,
            line: altActive ? alternate : primary
        )
    }

    /// The plain text of a line, trailing blanks removed. Used by tests and the
    /// accessibility layer; never by the renderer.
    public func text(row index: Int) -> String {
        let grid = altActive ? alternate : primary
        guard index >= 0 && index < grid.count else { return "" }
        var scalars = String.UnicodeScalarView()
        for cell in grid[index] where cell.width != 0 {
            if let scalar = Unicode.Scalar(cell.scalar) { scalars.append(scalar) }
        }
        return String(scalars).replacingOccurrences(of: "\u{0}", with: " ")
            .replacingOccurrences(of: " ", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Grid access

    private var grid: [[TerminalCell]] {
        get { altActive ? alternate : primary }
        set { if altActive { alternate = newValue } else { primary = newValue } }
    }

    private func lineAt(_ index: Int) -> [TerminalCell] {
        let grid = altActive ? alternate : primary
        guard index >= 0 && index < grid.count else { return Self.blankLine(columns) }
        return grid[index]
    }

    private func setCell(_ index: Int, column col: Int, cell: TerminalCell) {
        guard col >= 0 && col < columns else { return }
        if altActive {
            alternate[index][col] = cell
        } else {
            primary[index][col] = cell
        }
    }

    private func cellAt(_ index: Int, column col: Int) -> TerminalCell {
        guard col >= 0 && col < columns else { return .blank }
        let grid = altActive ? alternate : primary
        guard index >= 0 && index < grid.count else { return .blank }
        return grid[index][col]
    }

    private static func blankLine(_ columns: Int) -> [TerminalCell] {
        Array(repeating: TerminalCell.blank, count: max(2, columns))
    }

    private static func refit(_ line: [TerminalCell], to columns: Int) -> [TerminalCell] {
        var result = line
        if result.count > columns {
            result.removeLast(result.count - columns)
        } else {
            result.append(contentsOf: Array(repeating: TerminalCell.blank, count: columns - result.count))
        }
        // Never leave a wide glyph's right half without its left half.
        var index = 0
        while index < result.count {
            if result[index].width == 0 {
                result[index] = .blank
            }
            index += 1
        }
        return result
    }

    // MARK: - UTF-8

    private func ground(_ byte: UInt8) {
        switch byte {
        case 0x1B:
            state = .escape
        case 0x18, 0x1A:
            state = .ground
        case 0x00...0x06, 0x0E...0x17, 0x19, 0x1C...0x1F:
            break
        case 0x07:
            onBell?()
        case 0x08:
            pendingWrap = false
            column = max(0, column - 1)
        case 0x09:
            pendingWrap = false
            column = min(columns - 1, (column / 8 + 1) * 8)
        case 0x0A, 0x0B, 0x0C:
            lineFeed()
        case 0x0D:
            pendingWrap = false
            column = 0
        default:
            if byte < 0x80 {
                put(scalar: UInt32(byte))
            } else {
                utf8(byte)
            }
        }
    }

    private func utf8(_ byte: UInt8) {
        if utf8Expected == 0 {
            switch byte {
            case 0xC2...0xDF: utf8Expected = 2
            case 0xE0...0xEF: utf8Expected = 3
            case 0xF0...0xF4: utf8Expected = 4
            default:
                // A stray continuation byte or an over-long form: drop it rather
                // than inventing a character.
                return
            }
            utf8Buffer = [byte]
            return
        }
        guard (byte & 0xC0) == 0x80 else {
            utf8Buffer = []
            utf8Expected = 0
            return
        }
        utf8Buffer.append(byte)
        guard utf8Buffer.count == utf8Expected else { return }
        let bytes = utf8Buffer
        utf8Buffer = []
        utf8Expected = 0
        guard let decoded = String(bytes: bytes, encoding: .utf8), let scalar = decoded.unicodeScalars.first else {
            return
        }
        put(scalar: scalar.value)
    }

    // MARK: - Writing

    private func put(scalar value: UInt32) {
        let width = TerminalWidth.of(value)
        if width == 0 {
            // Combining marks are attached to the previous cell by the renderer;
            // the grid keeps the base scalar only.
            return
        }
        if pendingWrap && autowrap {
            pendingWrap = false
            column = 0
            lineFeed()
        }
        if width == 2 && column == columns - 1 {
            if autowrap {
                column = 0
                lineFeed()
            } else {
                return
            }
        }
        if insertMode {
            shiftCellsRight(at: row, from: column, by: width)
        }
        setCell(row, column: column, cell: TerminalCell(scalar: value, width: width, style: style))
        if width == 2 {
            setCell(row, column: column + 1, cell: TerminalCell(scalar: 0, width: 0, style: style))
        }
        column += width
        if column >= columns {
            column = columns - 1
            pendingWrap = autowrap
        }
    }

    private func shiftCellsRight(at index: Int, from start: Int, by count: Int) {
        guard start < columns else { return }
        var line = lineAt(index)
        let keep = Array(line[0..<start])
        var moved = Array(line[start..<columns])
        if count < moved.count {
            moved.removeLast(count)
            moved.insert(contentsOf: Array(repeating: TerminalCell.blank, count: count), at: 0)
        } else {
            moved = Array(repeating: TerminalCell.blank, count: columns - start)
        }
        line = keep + moved
        if altActive { alternate[index] = line } else { primary[index] = line }
    }

    private func lineFeed() {
        pendingWrap = false
        if row == scrollBottom {
            scrollUp(1)
        } else if row < rows - 1 {
            row += 1
        }
    }

    private func scrollUp(_ count: Int) {
        guard count > 0 else { return }
        for _ in 0..<count {
            var updated = altActive ? alternate : primary
            if !altActive && scrollTop == 0 && !updated.isEmpty {
                appendScrollback(updated[0])
            }
            updated.remove(at: scrollTop)
            updated.insert(Self.blankLine(columns), at: scrollBottom)
            if altActive { alternate = updated } else { primary = updated }
        }
    }

    private func scrollDown(_ count: Int) {
        guard count > 0 else { return }
        for _ in 0..<count {
            var updated = altActive ? alternate : primary
            updated.remove(at: scrollBottom)
            updated.insert(Self.blankLine(columns), at: scrollTop)
            if altActive { alternate = updated } else { primary = updated }
        }
    }

    private func appendScrollback(_ line: [TerminalCell]) {
        guard scrollbackLimit > 0 else { return }
        scrollback.append(line)
        if scrollback.count > scrollbackLimit {
            scrollback.removeFirst(scrollback.count - scrollbackLimit)
        }
    }

    private func eraseLine(_ mode: Int) {
        switch mode {
        case 0:
            for col in column..<columns { setCell(row, column: col, cell: blank()) }
        case 1:
            for col in 0...min(column, columns - 1) { setCell(row, column: col, cell: blank()) }
        case 2:
            for col in 0..<columns { setCell(row, column: col, cell: blank()) }
        default:
            break
        }
    }

    private func eraseDisplay(_ mode: Int) {
        switch mode {
        case 0:
            eraseLine(0)
            if row + 1 < rows {
                for index in (row + 1)..<rows {
                    for col in 0..<columns { setCell(index, column: col, cell: blank()) }
                }
            }
        case 1:
            eraseLine(1)
            for index in 0..<row {
                for col in 0..<columns { setCell(index, column: col, cell: blank()) }
            }
        case 2:
            for index in 0..<rows {
                for col in 0..<columns { setCell(index, column: col, cell: blank()) }
            }
        case 3:
            scrollback.removeAll()
        default:
            break
        }
    }

    private func blank() -> TerminalCell {
        TerminalCell(scalar: 32, width: 1, style: TerminalStyle(background: style.background))
    }

    private func insertLines(_ count: Int) {
        guard row >= scrollTop && row <= scrollBottom else { return }
        let limit = min(count, scrollBottom - row + 1)
        for _ in 0..<limit {
            var updated = altActive ? alternate : primary
            updated.remove(at: scrollBottom)
            updated.insert(Self.blankLine(columns), at: row)
            if altActive { alternate = updated } else { primary = updated }
        }
    }

    private func deleteLines(_ count: Int) {
        guard row >= scrollTop && row <= scrollBottom else { return }
        let limit = min(count, scrollBottom - row + 1)
        for _ in 0..<limit {
            var updated = altActive ? alternate : primary
            updated.remove(at: row)
            updated.insert(Self.blankLine(columns), at: scrollBottom)
            if altActive { alternate = updated } else { primary = updated }
        }
    }

    private func deleteCharacters(_ count: Int) {
        guard column < columns else { return }
        var line = lineAt(row)
        let limit = min(count, columns - column)
        line.removeSubrange(column..<(column + limit))
        line.append(contentsOf: Array(repeating: TerminalCell.blank, count: limit))
        if altActive { alternate[row] = line } else { primary[row] = line }
    }

    private func insertCharacters(_ count: Int) {
        shiftCellsRight(at: row, from: column, by: count)
    }

    private func eraseCharacters(_ count: Int) {
        let limit = min(count, columns - column)
        guard limit > 0 else { return }
        for offset in 0..<limit { setCell(row, column: column + offset, cell: blank()) }
    }

    // MARK: - Escape and CSI

    private func escape(_ byte: UInt8) {
        state = .ground
        switch byte {
        case UInt8(ascii: "["):
            state = .csi
            params = []
            currentParam = -1
            privateMarker = 0
            intermediate = 0
        case UInt8(ascii: "]"):
            state = .osc
            stringKind = UInt8(ascii: "]")
            stringBuffer = []
            stringOverflowed = false
        case UInt8(ascii: "P"), UInt8(ascii: "X"), UInt8(ascii: "^"), UInt8(ascii: "_"):
            state = .string
            stringKind = byte
            stringBuffer = []
            stringOverflowed = false
        case UInt8(ascii: "7"):
            saveCursor()
        case UInt8(ascii: "8"):
            restoreCursor()
        case UInt8(ascii: "D"):
            lineFeed()
        case UInt8(ascii: "E"):
            column = 0
            lineFeed()
        case UInt8(ascii: "M"):
            if row == scrollTop {
                scrollDown(1)
            } else if row > 0 {
                row -= 1
            }
        case UInt8(ascii: "c"):
            reset()
        default:
            break
        }
    }

    private func reset() {
        primary = Array(repeating: Self.blankLine(columns), count: rows)
        alternate = primary
        row = 0
        column = 0
        style = TerminalStyle()
        altActive = false
        cursorVisible = true
        autowrap = true
        originMode = false
        insertMode = false
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    private func csi(_ byte: UInt8) {
        switch byte {
        case 0x30...0x39:
            let digit = Int(byte - 0x30)
            currentParam = min(currentParam < 0 ? 0 : currentParam, 9999)
            currentParam = currentParam * 10 + digit
            if currentParam > 9999 { currentParam = 9999 }
        case UInt8(ascii: ";"):
            finishParam()
        case UInt8(ascii: "?"), UInt8(ascii: ">"), UInt8(ascii: "!"):
            if params.isEmpty && currentParam < 0 { privateMarker = byte }
        case 0x20...0x2F:
            intermediate = byte
        case 0x40...0x7E:
            finishParam()
            state = .ground
            execute(byte)
        default:
            state = .ground
        }
    }

    private func finishParam() {
        if params.count < Self.maxParams {
            params.append(currentParam)
        }
        currentParam = -1
    }

    private func param(_ index: Int, default fallback: Int) -> Int {
        guard index < params.count else { return fallback }
        let value = params[index]
        return value < 0 ? fallback : value
    }

    private func execute(_ byte: UInt8) {
        switch byte {
        case UInt8(ascii: "A"):
            pendingWrap = false
            row = max(originMode ? scrollTop : 0, row - max(1, param(0, default: 1)))
        case UInt8(ascii: "B"):
            pendingWrap = false
            row = min(originMode ? scrollBottom : rows - 1, row + max(1, param(0, default: 1)))
        case UInt8(ascii: "C"):
            pendingWrap = false
            column = min(columns - 1, column + max(1, param(0, default: 1)))
        case UInt8(ascii: "D"):
            pendingWrap = false
            column = max(0, column - max(1, param(0, default: 1)))
        case UInt8(ascii: "E"):
            column = 0
            row = min(rows - 1, row + max(1, param(0, default: 1)))
        case UInt8(ascii: "F"):
            column = 0
            row = max(0, row - max(1, param(0, default: 1)))
        case UInt8(ascii: "G"):
        column = min(columns - 1, max(0, max(1, param(0, default: 1)) - 1))
        case UInt8(ascii: "H"), UInt8(ascii: "f"):
            let targetRow = max(1, param(0, default: 1)) - 1
            let targetColumn = max(1, param(1, default: 1)) - 1
            let base = originMode ? scrollTop : 0
            row = min(max(base, base + targetRow), originMode ? scrollBottom : rows - 1)
            column = min(columns - 1, targetColumn)
            pendingWrap = false
        case UInt8(ascii: "J"):
            eraseDisplay(param(0, default: 0))
        case UInt8(ascii: "K"):
            eraseLine(param(0, default: 0))
        case UInt8(ascii: "L"):
            insertLines(max(1, param(0, default: 1)))
        case UInt8(ascii: "M"):
            deleteLines(max(1, param(0, default: 1)))
        case UInt8(ascii: "P"):
            deleteCharacters(max(1, param(0, default: 1)))
        case UInt8(ascii: "@"):
            insertCharacters(max(1, param(0, default: 1)))
        case UInt8(ascii: "X"):
            eraseCharacters(max(1, param(0, default: 1)))
        case UInt8(ascii: "S"):
            scrollUp(max(1, param(0, default: 1)))
        case UInt8(ascii: "T"):
            scrollDown(max(1, param(0, default: 1)))
        case UInt8(ascii: "d"):
            row = min(rows - 1, max(0, max(1, param(0, default: 1)) - 1))
        case UInt8(ascii: "m"):
            selectGraphicRendition()
        case UInt8(ascii: "h"), UInt8(ascii: "l"):
            setMode(byte == UInt8(ascii: "h"))
        case UInt8(ascii: "r"):
            let top = max(1, param(0, default: 1)) - 1
            let bottom = min(rows, param(1, default: rows)) - 1
            if top < bottom {
                scrollTop = top
                scrollBottom = bottom
                row = originMode ? scrollTop : 0
                column = 0
            }
        case UInt8(ascii: "s"):
            saveCursor()
        case UInt8(ascii: "u"):
            restoreCursor()
        case UInt8(ascii: "n"):
            deviceStatus()
        case UInt8(ascii: "c"):
            if param(0, default: 0) == 0 { onReply?(Data("\u{1B}[?1;2c".utf8)) }
        case UInt8(ascii: "t"):
            break
        default:
            break
        }
    }

    private func setMode(_ enabled: Bool) {
        for value in params {
            switch value {
            case 1:
                applicationCursorKeys = enabled
            case 6:
                originMode = enabled
                row = enabled ? scrollTop : 0
                column = 0
            case 7:
                autowrap = enabled
            case 25:
                cursorVisible = enabled
            case 47, 1047:
                switchScreen(toAlternate: enabled && !altActive, clearAlternate: false)
            case 1049:
                switchScreen(toAlternate: enabled && !altActive, clearAlternate: true)
            case 1000, 1002, 1003:
                break
            case 2004:
                bracketedPaste = enabled
            default:
                break
            }
        }
    }

    private func switchScreen(toAlternate alternate: Bool, clearAlternate: Bool) {
        if alternate {
            savedRow = row
            savedColumn = column
            if clearAlternate {
                self.alternate = Array(repeating: Self.blankLine(columns), count: rows)
            }
            altActive = true
            row = 0
            column = 0
        } else {
            altActive = false
            row = min(savedRow, rows - 1)
            column = min(savedColumn, columns - 1)
        }
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    private func deviceStatus() {
        switch param(0, default: 0) {
        case 5:
            onReply?(Data("\u{1B}[0n".utf8))
        case 6:
            let reply = "\u{1B}[\(row + 1);\(column + 1)R"
            onReply?(Data(reply.utf8))
        default:
            break
        }
    }

    private func selectGraphicRendition() {
        if params.isEmpty {
            style = TerminalStyle()
            return
        }
        var index = 0
        while index < params.count {
            let value = params[index]
            switch value {
            case -1, 0:
                style = TerminalStyle()
            case 1:
                style.bold = true
                style.faint = false
            case 2:
                style.faint = true
                style.bold = false
            case 3:
                style.italic = true
            case 4:
                style.underline = true
            case 7:
                style.reverse = true
            case 21, 22:
                style.bold = false
                style.faint = false
            case 23:
                style.italic = false
            case 24:
                style.underline = false
            case 27:
                style.reverse = false
            case 30...37:
                style.foreground = .indexed(UInt8(value - 30))
            case 39:
                style.foreground = .default
            case 40...47:
                style.background = .indexed(UInt8(value - 40))
            case 49:
                style.background = .default
            case 90...97:
                style.foreground = .indexed(UInt8(value - 90 + 8))
            case 100...107:
                style.background = .indexed(UInt8(value - 100 + 8))
            case 38, 48:
                let isForeground = value == 38
                if index + 1 < params.count && params[index + 1] == 5 && index + 2 < params.count {
                    let colour = TerminalColor.indexed(UInt8(clamping: max(0, params[index + 2])))
                    if isForeground { style.foreground = colour } else { style.background = colour }
                    index += 2
                } else if index + 1 < params.count && params[index + 1] == 2 && index + 4 < params.count {
                    let colour = TerminalColor.rgb(
                        UInt8(clamping: max(0, params[index + 2])),
                        UInt8(clamping: max(0, params[index + 3])),
                        UInt8(clamping: max(0, params[index + 4]))
                    )
                    if isForeground { style.foreground = colour } else { style.background = colour }
                    index += 4
                }
            default:
                break
            }
            index += 1
        }
    }

    // MARK: - Strings (OSC / DCS / PM / APC)

    private func stringByte(_ byte: UInt8) {
        if byte == 0x07 && stringKind == UInt8(ascii: "]") {
            finishString()
            return
        }
        if byte == 0x1B {
            // Expect ST; consume the backslash (or hand a new escape back).
            finishString()
            state = .escape
            return
        }
        if stringBuffer.count < Self.maxString {
            stringBuffer.append(byte)
        } else {
            stringOverflowed = true
        }
    }

    private func finishString() {
        let payload = String(decoding: stringBuffer, as: UTF8.self)
        let kind = stringKind
        let overflowed = stringOverflowed
        stringBuffer = []
        stringOverflowed = false
        state = .ground
        guard !overflowed else { return }
        if kind == UInt8(ascii: "]") {
            handleOSC(payload)
        }
        // DCS, PM and APC payloads are consumed and discarded on purpose: a
        // hostile stream must not be able to drive the renderer through a
        // parallel protocol.
    }

    private func handleOSC(_ payload: String) {
        let split = payload.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
        guard let codeText = split.first, let code = Int(codeText) else { return }
        let body = split.count > 1 ? String(split[1]) : ""
        switch code {
        case 0, 2:
            let cleaned = body.filter { !$0.isNewline && $0.asciiValue.map { $0 >= 0x20 && $0 != 0x7F } ?? true }
            title = String(cleaned.prefix(120))
        case 52:
            // Clipboard write. Never applied. The user copies explicitly.
            onBlockedClipboardWrite?()
        case 8:
            // `OSC 8 ; params ; URI ST`, where params may be empty.
            let parts = body.split(separator: ";", omittingEmptySubsequences: false)
            guard parts.count >= 2, let raw = parts.last.map(String.init), !raw.isEmpty else { return }
            if let url = URL(string: raw), url.scheme?.lowercased() == "https", url.host != nil {
                onHyperlink?(raw)
            }
        default:
            break
        }
    }

    // MARK: - Cursor save/restore

    private func saveCursor() {
        savedRow = row
        savedColumn = column
        savedStyle = style
    }

    private func restoreCursor() {
        row = min(max(0, savedRow), rows - 1)
        column = min(max(0, savedColumn), columns - 1)
        style = savedStyle
        pendingWrap = false
    }
}

/// East-Asian width, close enough for a terminal grid: the ranges that matter in
/// practice, with everything else defaulting to one column.
public enum TerminalWidth {
    public static func of(_ scalar: UInt32) -> Int {
        if scalar == 0 { return 0 }
        if scalar < 0x20 { return 0 }
        if (0x300...0x36F).contains(scalar) { return 0 }        // combining marks
        if (0x200B...0x200F).contains(scalar) { return 0 }      // zero-width spaces
        if (0xFE00...0xFE0F).contains(scalar) { return 0 }      // variation selectors
        switch scalar {
        case 0x1100...0x115F, 0x2E80...0x303E, 0x3041...0x33FF, 0x3400...0x4DBF,
             0x4E00...0x9FFF, 0xA000...0xA4CF, 0xAC00...0xD7A3, 0xF900...0xFAFF,
             0xFE30...0xFE4F, 0xFF00...0xFF60, 0xFFE0...0xFFE6,
             0x1F300...0x1F64F, 0x1F900...0x1F9FF, 0x20000...0x3FFFD:
            return 2
        default:
            return 1
        }
    }
}
