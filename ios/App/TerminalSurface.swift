import UIKit
import SwiftUI
import AnthraciteKit

/// Draws a `TerminalScreen` snapshot with CoreText and forwards keyboard input.
///
/// The renderer never interprets the stream: the emulator has already decided
/// what a cell is, and this view only paints it.
final class TerminalSurfaceView: UIView, UIKeyInput {
    var screen: TerminalScreen?
    var onInput: ((Data) -> Void)?
    var onResize: ((Int, Int) -> Void)?
    var modifiers: KeyModifiers = []

    private let font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
    private lazy var cellWidth: CGFloat = ("M" as NSString).size(withAttributes: [.font: font]).width
    private lazy var cellHeight: CGFloat = font.lineHeight + 2
    private var palette: [UIColor] = TerminalSurfaceView.makePalette()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        backgroundColor = UIColor(red: 0.05, green: 0.06, blue: 0.08, alpha: 1)
        isMultipleTouchEnabled = true
        accessibilityTraits = .updatesFrequently
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override var canBecomeFirstResponder: Bool { true }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let screen else { return }
        let columns = max(20, Int(bounds.width / cellWidth))
        let rows = max(5, Int(bounds.height / cellHeight))
        if columns != screen.columns || rows != screen.rows {
            onResize?(columns, rows)
        }
    }

    func redraw() {
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setFillColor(backgroundColor?.cgColor ?? UIColor.black.cgColor)
        context.fill(bounds)
        guard let screen else { return }
        let snapshot = screen.snapshot()
        let visible = snapshot.line
        let originY = bounds.minY

        for (rowIndex, line) in visible.enumerated() {
            var column = 0
            while column < line.count {
                let cell = line[column]
                if cell.width == 0 { column += 1; continue }
                let style = cell.style
                var text = String(Unicode.Scalar(cell.scalar) ?? " ")
                var runEnd = column + max(1, cell.width)
                // Collect a run of cells with the same style so CoreText draws
                // whole words rather than one glyph at a time.
                while runEnd < line.count, line[runEnd].style == style, line[runEnd].width != 0 {
                    if let scalar = Unicode.Scalar(line[runEnd].scalar) { text.append(Character(scalar)) }
                    runEnd += line[runEnd].width == 2 ? 2 : 1
                }
                let x = CGFloat(column) * cellWidth
                let y = originY + CGFloat(rowIndex) * cellHeight
                var foreground = colour(style.foreground, isForeground: true)
                var background = colour(style.background, isForeground: false)
                if style.reverse {
                    swap(&foreground, &background)
                }
                let width = CGFloat(runEnd - column) * cellWidth
                if background != UIColor.clear {
                    context.setFillColor(background.cgColor)
                    context.fill(CGRect(x: x, y: y, width: width, height: cellHeight))
                }
                var attributes: [NSAttributedString.Key: Any] = [
                    .font: style.bold ? UIFont.monospacedSystemFont(ofSize: 13, weight: .bold) : font,
                    .foregroundColor: foreground,
                ]
                if style.underline {
                    attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
                }
                if style.italic {
                    attributes[.obliqueness] = 0.18
                }
                (text as NSString).draw(
                    at: CGPoint(x: x, y: y + 1),
                    withAttributes: attributes
                )
                column = runEnd
            }
        }

        if snapshot.cursorVisible {
            let cursorRect = CGRect(
                x: CGFloat(snapshot.cursorColumn) * cellWidth,
                y: originY + CGFloat(snapshot.cursorRow) * cellHeight,
                width: cellWidth,
                height: cellHeight
            )
            context.setFillColor(UIColor(white: 0.85, alpha: 0.55).cgColor)
            context.fill(cursorRect)
        }
    }

    private func colour(_ value: TerminalColor, isForeground: Bool) -> UIColor {
        switch value {
        case .default:
            return isForeground ? UIColor(white: 0.88, alpha: 1) : .clear
        case .indexed(let index):
            return palette[Int(index)]
        case .rgb(let red, let green, let blue):
            return UIColor(
                red: CGFloat(red) / 255,
                green: CGFloat(green) / 255,
                blue: CGFloat(blue) / 255,
                alpha: 1
            )
        }
    }

    static func makePalette() -> [UIColor] {
        let base: [(CGFloat, CGFloat, CGFloat)] = [
            (0.05, 0.06, 0.08), (0.80, 0.25, 0.25), (0.30, 0.75, 0.35), (0.80, 0.65, 0.20),
            (0.30, 0.50, 0.90), (0.70, 0.40, 0.85), (0.30, 0.75, 0.80), (0.80, 0.80, 0.80),
            (0.35, 0.36, 0.40), (1.00, 0.45, 0.45), (0.55, 0.95, 0.55), (1.00, 0.90, 0.45),
            (0.55, 0.70, 1.00), (0.90, 0.65, 1.00), (0.55, 0.95, 1.00), (1.00, 1.00, 1.00),
        ]
        var colours = base.map { UIColor(red: $0.0, green: $0.1, blue: $0.2, alpha: 1) }
        for index in 0..<216 {
            let red = CGFloat(index / 36) / 5
            var green = CGFloat((index % 36) / 6) / 5
            let blue = CGFloat(index % 6) / 5
            green = max(0, min(1, green))
            colours.append(UIColor(red: red, green: green, blue: blue, alpha: 1))
        }
        for step in 0..<24 {
            let level = CGFloat(step) / 25 + 0.03
            colours.append(UIColor(white: level, alpha: 1))
        }
        return colours
    }

    // MARK: UIKeyInput

    var hasText: Bool { true }

    func insertText(_ text: String) {
        guard !text.isEmpty else { return }
        if modifiers.contains(.ctrl), let first = text.first {
            onInput?(TerminalKeys.sequence(for: .character(String(first)), modifiers: [.ctrl], modes: KeyModes()))
            return
        }
        if modifiers.contains(.alt) {
            for character in text {
                onInput?(Data([0x1B]))
                onInput?(Data(String(character).utf8))
            }
            return
        }
        onInput?(Data(text.utf8))
    }

    func deleteBackward() {
        onInput?(TerminalKeys.sequence(for: .backspace, modifiers: modifiers, modes: KeyModes()))
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        for press in presses {
            guard let key = press.key else { continue }
            var mods = KeyModifiers()
            if key.modifierFlags.contains(.control) { mods.insert(.ctrl) }
            if key.modifierFlags.contains(.alternate) { mods.insert(.alt) }
            if key.modifierFlags.contains(.shift) { mods.insert(.shift) }
            if let terminalKey = TerminalSurfaceView.map(key.keyCode, modifiers: key.modifierFlags) {
                let modes = KeyModes(
                    applicationCursorKeys: screen?.snapshot().applicationCursorKeys ?? false,
                    bracketedPaste: screen?.snapshot().bracketedPaste ?? false
                )
                onInput?(TerminalKeys.sequence(for: terminalKey, modifiers: mods, modes: modes))
            } else if !key.charactersIgnoringModifiers.isEmpty {
                insertText(key.charactersIgnoringModifiers)
            }
        }
    }

    static func map(_ code: UIKeyboardHIDUsage, modifiers: UIKeyModifierFlags) -> TerminalKey? {
        switch code {
        case .keyboardUpArrow: return .arrowUp
        case .keyboardDownArrow: return .arrowDown
        case .keyboardLeftArrow: return .arrowLeft
        case .keyboardRightArrow: return .arrowRight
        case .keyboardEscape: return .escape
        case .keyboardTab: return .tab
        case .keyboardReturnOrEnter: return .enter
        case .keyboardDeleteOrBackspace: return .backspace
        case .keyboardDeleteForward: return .delete
        case .keyboardHome: return .home
        case .keyboardEnd: return .end
        case .keyboardPageUp: return .pageUp
        case .keyboardPageDown: return .pageDown
        case .keyboardInsert: return .insert
        case .keyboardF1: return .f1
        case .keyboardF2: return .f2
        case .keyboardF3: return .f3
        case .keyboardF4: return .f4
        case .keyboardF5: return .f5
        case .keyboardF6: return .f6
        case .keyboardF7: return .f7
        case .keyboardF8: return .f8
        case .keyboardF9: return .f9
        case .keyboardF10: return .f10
        case .keyboardF11: return .f11
        case .keyboardF12: return .f12
        default: return nil
        }
    }
}

struct TerminalSurface: UIViewRepresentable {
    let tab: TerminalTab
    let model: AppModel

    func makeUIView(context: Context) -> TerminalSurfaceView {
        let view = TerminalSurfaceView()
        view.screen = tab.screen
        view.onInput = { [weak model] data in
            Task { @MainActor in model?.send(data, to: tab) }
        }
        view.onResize = { [weak model] columns, rows in
            Task { @MainActor in model?.resize(tab, columns: columns, rows: rows) }
        }
        DispatchQueue.main.async { view.becomeFirstResponder() }
        return view
    }

    func updateUIView(_ view: TerminalSurfaceView, context: Context) {
        view.screen = tab.screen
        view.redraw()
        view.accessibilityLabel = "Terminal: \(tab.title)"
    }
}
