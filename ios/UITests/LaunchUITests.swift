import XCTest

/// Simulator evidence that the app builds, launches and reaches its first real
/// screen. It deliberately uses an in-memory store so it cannot touch a real
/// Keychain or a real server.
final class LaunchUITests: XCTestCase {
    func testFirstLaunchOffersTheServerForm() {
        let app = XCUIApplication()
        app.launchArguments = ["-useMemoryStore"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Anthracite"].waitForExistence(timeout: 20))
        let addButton = app.buttons["Add a server"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 10))
        addButton.tap()

        XCTAssertTrue(app.textFields["Host name or IP address"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.textFields["SSH user"].exists)
        XCTAssertTrue(app.buttons["Save"].exists)
        // The form must say where the secret goes before anyone types one.
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'Keychain'")).firstMatch.exists)

        app.navigationBars.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["Add a server"].waitForExistence(timeout: 5))
    }

    func testValidationExplainsAnIncompleteServer() {
        let app = XCUIApplication()
        app.launchArguments = ["-useMemoryStore"]
        app.launch()

        app.buttons["Add a server"].tap()
        app.buttons["Save"].tap()
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'host name'")).firstMatch
                .waitForExistence(timeout: 5)
        )
    }
}
