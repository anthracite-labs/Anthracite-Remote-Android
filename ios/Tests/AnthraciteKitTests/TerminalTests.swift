import XCTest
@testable import AnthraciteKit

final class TerminalTests: XCTestCase {
    private func screen(columns: Int = 20, rows: Int = 5, scrollback: Int = 100) -> TerminalScreen {
        TerminalScreen(columns: columns, rows: rows, scrollbackLimit: scrollback)
    }

    func testPlainTextAndLineFeedScrollIntoScrollback() {
        let terminal = screen()
        terminal.feed(Data("one\r\ntwo\r\nthree\r\nfour\r\nfive".utf8))
        XCTAssertEqual(terminal.snapshot().scrollback.count, 0)
        terminal.feed(Data("\r\nsix".utf8))
        XCTAssertEqual(terminal.text(row: 0), "two")
        XCTAssertEqual(terminal.text(row: 4), "six")
        XCTAssertEqual(terminal.snapshot().scrollback.count, 1)
        XCTAssertEqual(terminal.snapshot().scrollback.first.map { line -> String in
            String(line.compactMap { Unicode.Scalar($0.scalar) }.map(Character.init))
                .trimmingCharacters(in: .whitespaces)
        }, "one")
    }

    func testCursorMovementAndErase() {
        let terminal = screen()
        terminal.feed(Data("abcdef\u{1B}[1;3H\u{1B}[K".utf8))
        XCTAssertEqual(terminal.text(row: 0), "ab")
        XCTAssertEqual(terminal.snapshot().cursorColumn, 2)
        terminal.feed(Data("\u{1B}[2J".utf8))
        XCTAssertTrue(terminal.text(row: 0).isEmpty)
    }

    func testSGRSetsColoursWithoutLeaking() {
        let terminal = screen()
        terminal.feed(Data("\u{1B}[1;31;44mX\u{1B}[0mY".utf8))
        let line = terminal.snapshot().line[0]
        XCTAssertEqual(line[0].style.bold, true)
        XCTAssertEqual(line[0].style.foreground, .indexed(1))
        XCTAssertEqual(line[0].style.background, .indexed(4))
        XCTAssertEqual(line[1].style.bold, false)
        XCTAssertEqual(line[1].style.foreground, .default)
    }

    func testTrueColourAndIndexedColour() {
        let terminal = screen()
        terminal.feed(Data("\u{1B}[38;2;1;2;3mR\u{1B}[48;5;200mB".utf8))
        let line = terminal.snapshot().line[0]
        XCTAssertEqual(line[0].style.foreground, .rgb(1, 2, 3))
        XCTAssertEqual(line[1].style.background, .indexed(200))
    }

    func testAlternateScreenKeepsPrimaryContent() {
        let terminal = screen()
        terminal.feed(Data("primary".utf8))
        terminal.feed(Data("\u{1B}[?1049h".utf8))
        terminal.feed(Data("alt".utf8))
        XCTAssertEqual(terminal.text(row: 0), "alt")
        XCTAssertTrue(terminal.snapshot().altScreen)
        terminal.feed(Data("\u{1B}[?1049l".utf8))
        XCTAssertEqual(terminal.text(row: 0), "primary")
    }

    func testWideCharacterOccupiesTwoCellsAndNeverSplits() {
        let terminal = screen(columns: 4)
        terminal.feed(Data("あい".utf8))
        let line = terminal.snapshot().line[0]
        XCTAssertEqual(line[0].width, 2)
        XCTAssertEqual(line[1].width, 0)
        XCTAssertEqual(line[2].width, 2)
        // A resize that cuts the glyph in half must not leave a dangling half.
        terminal.resize(columns: 3, rows: 5)
        let resized = terminal.snapshot().line[0]
        XCTAssertNotEqual(resized[2].width, 0)
    }

    func testBracketedPasteModeIsTracked() {
        let terminal = screen()
        XCTAssertFalse(terminal.snapshot().bracketedPaste)
        terminal.feed(Data("\u{1B}[?2004h".utf8))
        XCTAssertTrue(terminal.snapshot().bracketedPaste)
        terminal.feed(Data("\u{1B}[?2004l".utf8))
        XCTAssertFalse(terminal.snapshot().bracketedPaste)
    }

    func testOSC52IsNeverApplied() {
        let terminal = screen()
        var blocked = false
        terminal.onBlockedClipboardWrite = { blocked = true }
        terminal.feed(Data("\u{1B}]52;c;SGVsbG8=\u{07}".utf8))
        XCTAssertTrue(blocked)
        XCTAssertTrue(terminal.text(row: 0).isEmpty)
    }

    func testOSC8OnlySurfacesHTTPS() {
        let terminal = screen()
        var links: [String] = []
        terminal.onHyperlink = { links.append($0) }
        terminal.feed(Data("\u{1B}]8;;https://example.com/x\u{07}".utf8))
        terminal.feed(Data("\u{1B}]8;;javascript:alert(1)\u{07}".utf8))
        terminal.feed(Data("\u{1B}]8;;file:///etc/passwd\u{07}".utf8))
        XCTAssertEqual(links, ["https://example.com/x"])
    }

    func testTitleStripsControlCharactersAndIsCapped() {
        let terminal = screen()
        let long = String(repeating: "a", count: 200)
        terminal.feed(Data("\u{1B}]0;ok\u{07}".utf8))
        XCTAssertEqual(terminal.title, "ok")
        terminal.feed(Data("\u{1B}]2;\(long)\u{07}".utf8))
        XCTAssertEqual(terminal.title.count, 120)
    }

    func testUnknownAndOverlongSequencesAreConsumed() {
        let terminal = screen()
        terminal.feed(Data("\u{1B}[999999999;99999999;1mX".utf8))
        terminal.feed(Data("\u{1B}]0;".utf8 + Data(repeating: 0x41, count: 5000) + Data("\u{07}".utf8)))
        terminal.feed(Data("Y".utf8))
        XCTAssertTrue(terminal.text(row: 0).hasSuffix("Y"))
    }

    func testDCSAndAPCPayloadsAreDiscarded() {
        let terminal = screen()
        terminal.feed(Data("\u{1B}Pq#0;2;0;0;0\u{1B}\\Z".utf8))
        XCTAssertEqual(terminal.text(row: 0), "Z")
    }

    func testDeviceStatusReportsCursor() {
        let terminal = screen()
        var replies: [String] = []
        terminal.onReply = { replies.append(String(decoding: $0, as: UTF8.self)) }
        terminal.feed(Data("\u{1B}[3;4H".utf8))
        terminal.feed(Data("\u{1B}[6n".utf8))
        XCTAssertEqual(replies.last, "\u{1B}[3;4R")
    }

    func testScrollRegionConfinesLineFeed() {
        let terminal = screen(columns: 10, rows: 5)
        terminal.feed(Data("a\r\nb\r\nc\r\nd\r\ne".utf8))
        terminal.feed(Data("\u{1B}[2;3r\u{1B}[3;1H\u{1B}D".utf8))
        // Reverse index inside the region scrolls only rows 2-3.
        let snapshot = terminal.snapshot()
        XCTAssertEqual(snapshot.line.count, 5)
    }

    func testResizeKeepsTextAndCursorInsideGrid() {
        let terminal = screen(columns: 20, rows: 5)
        terminal.feed(Data("hello world".utf8))
        terminal.resize(columns: 6, rows: 3)
        XCTAssertEqual(terminal.snapshot().line.count, 3)
        XCTAssertEqual(terminal.snapshot().line[0].count, 6)
        XCTAssertEqual(terminal.text(row: 0), "hello")
        XCTAssertLessThan(terminal.snapshot().cursorColumn, 6)
    }
}

final class KeyEncodingTests: XCTestCase {
    private let plain = KeyModes()

    func testControlCharacters() {
        XCTAssertEqual(TerminalKeys.sequence(for: .character("c"), modifiers: [.ctrl], modes: plain), Data([0x03]))
        XCTAssertEqual(TerminalKeys.sequence(for: .character("["), modifiers: [.ctrl], modes: plain), Data([0x1B]))
        XCTAssertEqual(TerminalKeys.sequence(for: .character("?"), modifiers: [.ctrl], modes: plain), Data([0x7F]))
    }

    func testAltUsesEscapePrefixOnlyWhereNoParameterFormExists() {
        XCTAssertEqual(TerminalKeys.sequence(for: .enter, modifiers: [.alt], modes: plain), Data([0x1B, 0x0D]))
        XCTAssertEqual(
            TerminalKeys.sequence(for: .arrowRight, modifiers: [.alt], modes: plain),
            Data("\u{1B}[1;3C".utf8)
        )
        XCTAssertEqual(
            TerminalKeys.sequence(for: .delete, modifiers: [.ctrl], modes: plain),
            Data("\u{1B}[3;5~".utf8)
        )
    }

    func testApplicationCursorModeChangesCursorIntroducer() {
        let application = KeyModes(applicationCursorKeys: true)
        XCTAssertEqual(TerminalKeys.sequence(for: .arrowUp, modifiers: [], modes: plain), Data("\u{1B}[A".utf8))
        XCTAssertEqual(TerminalKeys.sequence(for: .arrowUp, modifiers: [], modes: application), Data("\u{1B}OA".utf8))
    }

    func testFunctionKeysAndShiftTab() {
        XCTAssertEqual(TerminalKeys.sequence(for: .f2, modifiers: [], modes: plain), Data("\u{1B}OQ".utf8))
        XCTAssertEqual(TerminalKeys.sequence(for: .f7, modifiers: [], modes: plain), Data("\u{1B}[18~".utf8))
        XCTAssertEqual(TerminalKeys.sequence(for: .tab, modifiers: [.shift], modes: plain), Data("\u{1B}[Z".utf8))
    }

    func testPasteIsFramedOnlyWhenTheProgramAsked() {
        XCTAssertEqual(TerminalKeys.paste("a\nb", bracketed: false), Data("a\nb".utf8))
        XCTAssertEqual(TerminalKeys.paste("a\nb", bracketed: true), Data("\u{1B}[200~a\nb\u{1B}[201~".utf8))
        // A pasted escape sequence must not become a live control sequence.
        XCTAssertEqual(TerminalKeys.paste("x\u{1B}[31m", bracketed: true), Data("\u{1B}[200~x[31m\u{1B}[201~".utf8))
    }

    func testMouseReportsWithinBoundsOnly() {
        XCTAssertEqual(TerminalKeys.mouse(button: 0, column: 0, row: 0, release: false, sgr: true),
                       Data("\u{1B}[<0;1;1M".utf8))
        XCTAssertTrue(TerminalKeys.mouse(button: 0, column: -1, row: 0, release: false, sgr: true).isEmpty)
    }
}
