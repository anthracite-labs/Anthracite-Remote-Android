import Foundation

/// Logical keys, independent of any platform key code table.
public enum TerminalKey: Equatable, Sendable {
    case enter, tab, escape, backspace, delete
    case arrowUp, arrowDown, arrowLeft, arrowRight
    case home, end, pageUp, pageDown, insert
    case f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12
    /// A printable character from the keyboard, already resolved by the input system.
    case character(String)
}

public struct KeyModifiers: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let ctrl = KeyModifiers(rawValue: 1)
    public static let alt = KeyModifiers(rawValue: 2)
    public static let shift = KeyModifiers(rawValue: 4)

    /// xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4).
    public var csiParameter: Int {
        var value = 1
        if contains(.shift) { value += 1 }
        if contains(.alt) { value += 2 }
        if contains(.ctrl) { value += 4 }
        return value
    }
}

public struct KeyModes: Sendable {
    public var applicationCursorKeys: Bool
    public var bracketedPaste: Bool
    public var mouseReporting: Int
    public var mouseEncodingSgr: Bool

    public init(
        applicationCursorKeys: Bool = false,
        bracketedPaste: Bool = false,
        mouseReporting: Int = 0,
        mouseEncodingSgr: Bool = false
    ) {
        self.applicationCursorKeys = applicationCursorKeys
        self.bracketedPaste = bracketedPaste
        self.mouseReporting = mouseReporting
        self.mouseEncodingSgr = mouseEncodingSgr
    }
}

/// Encodes keyboard input as the byte stream a real xterm would send.
///
/// This is pure Swift so every mapping is unit tested: a wrong arrow-key encoding
/// breaks vim, htop and every other TUI without any device being involved.
public enum TerminalKeys {
    private static let esc = "\u{1B}"
    private static let csi = "\u{1B}["

    public static func sequence(for key: TerminalKey, modifiers: KeyModifiers, modes: KeyModes) -> Data {
        // xterm encodes Alt inside the CSI modifier parameter. Only keys without
        // a parameter form take the ESC prefix; prefixing as well would deliver
        // two encodings of one keystroke.
        let described = described(key: key, modifiers: modifiers, modes: modes)
        let prefix = (modifiers.contains(.alt) && !hasParameterForm(key)) ? esc : ""
        return Data((prefix + described).utf8)
    }

    private static func hasParameterForm(_ key: TerminalKey) -> Bool {
        switch key {
        case .enter, .tab, .escape, .backspace:
            return false
        default:
            return true
        }
    }

    private static func described(key: TerminalKey, modifiers: KeyModifiers, modes: KeyModes) -> String {
        let parameter = modifiers.csiParameter
        switch key {
        case .enter:
            return "\r"
        case .tab:
            return modifiers.contains(.shift) ? csi + "Z" : "\t"
        case .escape:
            return esc
        case .backspace:
            // A real terminal sends DEL; the PTY line discipline maps it.
            return "\u{7F}"
        case .delete:
            return modified("3~", parameter)
        case .insert:
            return modified("2~", parameter)
        case .pageUp:
            return modified("5~", parameter)
        case .pageDown:
            return modified("6~", parameter)
        case .home:
            return cursor(modes: modes, modifiers: modifiers, final: "H")
        case .end:
            return cursor(modes: modes, modifiers: modifiers, final: "F")
        case .arrowUp:
            return cursor(modes: modes, modifiers: modifiers, final: "A")
        case .arrowDown:
            return cursor(modes: modes, modifiers: modifiers, final: "B")
        case .arrowRight:
            return cursor(modes: modes, modifiers: modifiers, final: "C")
        case .arrowLeft:
            return cursor(modes: modes, modifiers: modifiers, final: "D")
        case .f1: return function(modifiers: modifiers, final: "P")
        case .f2: return function(modifiers: modifiers, final: "Q")
        case .f3: return function(modifiers: modifiers, final: "R")
        case .f4: return function(modifiers: modifiers, final: "S")
        case .f5: return modified("15~", parameter)
        case .f6: return modified("17~", parameter)
        case .f7: return modified("18~", parameter)
        case .f8: return modified("19~", parameter)
        case .f9: return modified("20~", parameter)
        case .f10: return modified("21~", parameter)
        case .f11: return modified("23~", parameter)
        case .f12: return modified("24~", parameter)
        case .character(let text):
            return character(text, modifiers: modifiers)
        }
    }

    /// Tilde-terminated keys take the modifier as a second parameter, not a prefix.
    private static func modified(_ body: String, _ parameter: Int) -> String {
        guard parameter != 1 else { return csi + body }
        return csi + String(body.dropLast()) + ";\(parameter)" + String(body.suffix(1))
    }

    /// Cursor keys, Home and End follow the mode the program selected.
    private static func cursor(modes: KeyModes, modifiers: KeyModifiers, final: Character) -> String {
        if modifiers.rawValue == 0 {
            let introducer = modes.applicationCursorKeys ? esc + "O" : csi
            return introducer + String(final)
        }
        return csi + "1;\(modifiers.csiParameter)" + String(final)
    }

    /// F1–F4 use SS3 without modifiers and CSI 1;<mod> with them.
    private static func function(modifiers: KeyModifiers, final: Character) -> String {
        if modifiers.rawValue == 0 {
            return esc + "O" + String(final)
        }
        return csi + "1;\(modifiers.csiParameter)" + String(final)
    }

    private static func character(_ text: String, modifiers: KeyModifiers) -> String {
        guard let first = text.first else { return text }
        if modifiers.contains(.ctrl) {
            // Ctrl-A .. Ctrl-Z, plus the four punctuation cases a shell expects.
            let upper = String(first).uppercased()
            if let scalar = upper.unicodeScalars.first, (65...90).contains(scalar.value) {
                let control = UInt32(scalar.value - 64)
                return String(UnicodeScalar(control) ?? UnicodeScalar(0))
            }
            switch first {
            case "[": return esc
            case "\\": return "\u{1C}"
            case "]": return "\u{1D}"
            case "^": return "\u{1E}"
            case "_": return "\u{1F}"
            case "?": return "\u{7F}"
            case " ": return "\u{00}"
            default: return text
            }
        }
        return text
    }

    /// Bracketed paste is a promise the *program* asked for. Without it the
    /// payload is sent verbatim; with it every newline stays literal because the
    /// markers do the framing.
    public static func paste(_ text: String, bracketed: Bool) -> Data {
        let sanitized = text.replacingOccurrences(of: "\u{1B}", with: "")
        guard bracketed else { return Data(sanitized.utf8) }
        return Data(("\u{1B}[200~" + sanitized + "\u{1B}[201~").utf8)
    }

    /// SGR mouse encoding, which is the only one the app enables.
    public static func mouse(button: Int, column: Int, row: Int, release: Bool, sgr: Bool) -> Data {
        guard column >= 0, row >= 0 else { return Data() }
        let code = button + (release ? 3 : 0)
        if sgr {
            let terminator = release ? "m" : "M"
            return Data("\u{1B}[<\(code);\(column + 1);\(row + 1)\(terminator)".utf8)
        }
        let bytes: [UInt8] = [0x1B, 0x5B, 0x4D, UInt8(32 + code), UInt8(32 + column), UInt8(32 + row)]
        return Data(bytes)
    }
}
