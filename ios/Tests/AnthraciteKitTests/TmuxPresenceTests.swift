import XCTest
@testable import AnthraciteKit

/// A session that cannot be verified is never reported as stopped.
///
/// `has-session` and `list-sessions` answer with exit 1 for two very different
/// situations: tmux has nothing to report, or the command never reached a healthy
/// tmux. Only the first is evidence. These tests pin the narrow match.
final class TmuxPresenceTests: XCTestCase {
    private let live = TmuxSession(name: "work", id: "$3", windows: 1, attached: false, created: 1_700_000_000)
    private let listLine = "work\t$3\t1\t0\t1700000000"

    // MARK: - Listing answers

    func testPresentSessionIsPresent() {
        let presence = Tmux.presence(
            exitCode: 0,
            stdout: listLine + "\n",
            stderr: "",
            name: live.name,
            id: live.id
        )
        XCTAssertEqual(presence, .present)
    }

    func testAbsentFromAHealthyListingIsAbsent() {
        let presence = Tmux.presence(
            exitCode: 0,
            stdout: "other\t$9\t1\t0\t1700000000\n",
            stderr: "",
            name: live.name,
            id: live.id
        )
        XCTAssertEqual(presence, .absent)
    }

    func testAReusedNameOrIdIsUnknownNotAbsent() {
        let reusedName = "work\t$9\t1\t0\t1700000000\n"
        XCTAssertEqual(
            Tmux.presence(exitCode: 0, stdout: reusedName, stderr: "", name: live.name, id: live.id),
            .unknown
        )
        let reusedId = "other\t$3\t1\t0\t1700000000\n"
        XCTAssertEqual(
            Tmux.presence(exitCode: 0, stdout: reusedId, stderr: "", name: live.name, id: live.id),
            .unknown
        )
    }

    func testAMalformedLineMakesTheWholeAnswerUnknown() {
        let presence = Tmux.presence(
            exitCode: 0,
            stdout: listLine + "\nthis is not a listing\n",
            stderr: "",
            name: live.name,
            id: live.id
        )
        XCTAssertEqual(presence, .unknown)
        XCTAssertEqual(Tmux.parseListing("broken\n").unparsed, 1)
    }

    func testStderrOnASuccessfulListingIsUnknown() {
        let presence = Tmux.presence(
            exitCode: 0,
            stdout: listLine + "\n",
            stderr: "warning: something odd\n",
            name: live.name,
            id: live.id
        )
        XCTAssertEqual(presence, .unknown)
    }

    // MARK: - Expected absence

    func testNoSessionsIsTheOnlyAcceptedAbsence() {
        XCTAssertEqual(
            Tmux.presence(exitCode: 1, stdout: "", stderr: "no sessions\n", name: live.name, id: live.id),
            .absent
        )
        XCTAssertEqual(
            Tmux.presence(
                exitCode: 1,
                stdout: "",
                stderr: "no server running on /tmp/tmux-1000/default\n",
                name: live.name,
                id: live.id
            ),
            .absent
        )
    }

    func testEveryOtherFailureIsUnknown() {
        // Each of these can be produced by a host that is still running the work.
        let failures: [(Int, String, String)] = [
            (1, "", "permission denied"),
            (1, "", "error connecting to /tmp/tmux-1000/default (No such file or directory)"),
            (1, "", "error connecting to /tmp/tmux-1000/default (Connection refused)"),
            (1, "", "lost server"),
            (1, "", "error: fork failed: Resource temporarily unavailable"),
            (1, "partial listing\n", "no sessions"),
            (1, "", "no server running on /tmp/tmux 1000/default"),
            (127, "", "tmux: command not found"),
            (255, "", "Connection reset by peer"),
        ]
        for (code, out, err) in failures {
            XCTAssertEqual(
                Tmux.presence(exitCode: code, stdout: out, stderr: err, name: live.name, id: live.id),
                .unknown,
                "exit \(code) with '\(err)' must not be read as stopped"
            )
            XCTAssertEqual(
                Tmux.hasSessionPresence(exitCode: code, stdout: out, stderr: err, id: live.id),
                .unknown,
                "has-session exit \(code) with '\(err)' must not be read as stopped"
            )
        }
    }

    // MARK: - has-session

    func testHasSessionAnswers() {
        XCTAssertEqual(
            Tmux.hasSessionPresence(exitCode: 0, stdout: "", stderr: "", id: "$3"),
            .present
        )
        XCTAssertEqual(
            Tmux.hasSessionPresence(exitCode: 1, stdout: "", stderr: "can't find session: $3", id: "$3"),
            .absent
        )
        // A message about a different session proves nothing about this one.
        XCTAssertEqual(
            Tmux.hasSessionPresence(exitCode: 1, stdout: "", stderr: "can't find session: $4", id: "$3"),
            .unknown
        )
        XCTAssertEqual(
            Tmux.hasSessionPresence(exitCode: 1, stdout: "", stderr: "can't find session: $30", id: "$3"),
            .unknown
        )
    }

    // MARK: - Existing behaviour

    func testIdentityStillRequiresBothNameAndId() {
        XCTAssertTrue(Tmux.identityMatches([live], name: "work", id: "$3"))
        XCTAssertFalse(Tmux.identityMatches([live], name: "work", id: "$9"))
        XCTAssertFalse(Tmux.identityMatches([live], name: "other", id: "$3"))
    }
}
