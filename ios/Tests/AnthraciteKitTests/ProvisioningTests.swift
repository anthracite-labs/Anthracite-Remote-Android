import XCTest
@testable import AnthraciteKit

final class ProvisioningTests: XCTestCase {
    private func facts(
        osId: String = "debian",
        arch: String = "x86_64",
        manager: PackageManagerKind? = .apt,
        root: Bool = true,
        sudo: Bool = true,
        containerized: Bool = false,
        installed: Set<String> = [],
        machineId: String = ""
    ) -> HostFacts {
        HostFacts(
            osId: osId,
            osVersion: "12",
            osPrettyName: "Debian GNU/Linux 12",
            arch: arch,
            managers: manager.map { [$0] } ?? [],
            isRoot: root,
            sudoNonInteractive: sudo,
            diskAvailableKb: 2_000_000,
            tools: installed,
            containerized: containerized,
            machineId: machineId,
            capturedAt: Date()
        )
    }

    func testStepsDigestIsTheReviewedSHA256() {
        let steps = [PlanStep(index: 1, packages: ["tmux"], toolIds: ["tmux"])]
        XCTAssertEqual(ProvisionPlanner.stepsBytes(steps), Data("1\tinstall\ttmux\n".utf8))
        XCTAssertEqual(
            ProvisionPlanner.digest(ProvisionPlanner.stepsBytes(steps)),
            "59de9a4d68d4791d2525e191905cff01c76470c188085371be51e93913de86fe"
        )
    }

    func testPlanFileMatchesTheHostRunnerGrammar() throws {
        let request = ProvisionRequest(toolIds: ["tmux"])
        let plan = try ProvisionPlanner.plan(request, facts: facts())
        let jobId = "0123456789abcdef"
        let text = String(decoding: try ProvisionPlanner.planFile(plan: plan, jobId: jobId), as: UTF8.self)
        XCTAssertTrue(text.contains("job_id=0123456789abcdef\n"))
        XCTAssertTrue(text.contains("manager=apt-get\n"))
        XCTAssertTrue(text.contains("admin=0\n"))
        XCTAssertTrue(text.contains("os_id=debian\n"))
        XCTAssertTrue(text.contains("plan_hash=\(plan.stepsDigest)\n"))
        XCTAssertTrue(text.hasSuffix("\n"))

        XCTAssertThrowsError(try ProvisionPlanner.planFile(plan: plan, jobId: "../../etc/passwd")) { error in
            XCTAssertEqual(error as? ProvisionError, .invalidJobId)
        }
    }

    func testJobAndCancelCommandsNeverCarryAPID() throws {
        let id = "0123456789abcdef"
        XCTAssertEqual(try XCTUnwrap(ProvisionJobs.statusCommand(id)), "sh -s \"$HOME/.anthracite/jobs/\(id)\"")
        XCTAssertEqual(try XCTUnwrap(ProvisionJobs.cancelCommand(id)), "sh -s \"$HOME/.anthracite/jobs/\(id)\"")
        XCTAssertFalse(ProvisionJobs.cancelCommand(id)!.contains("kill"))
        XCTAssertNil(ProvisionJobs.cancelCommand("../../etc"))
        XCTAssertNil(ProvisionJobs.statusCommand("0123456789ABCDEF"))
        XCTAssertNil(ProvisionJobs.statusCommand("1234"))
    }

    func testCancelOutcomesDistinguishRequestFromProof() {
        XCTAssertFalse(CancelOutcome.signalSent.isConfirmed)
        XCTAssertFalse(CancelOutcome.stillRunning.isConfirmed)
        XCTAssertFalse(CancelOutcome.refusedUnknownIdentity.isConfirmed)
        XCTAssertTrue(CancelOutcome.confirmedStopped.isConfirmed)
        XCTAssertTrue(CancelOutcome.alreadyFinished.isConfirmed)

        let refused = ProvisionJobs.parseCancel(
            "cancel_result=refused_unknown_identity\nreason=start_ticks_unavailable\npid=4242\nprocess_alive=yes\nverification=none\n"
        )
        XCTAssertEqual(refused, .refusedUnknownIdentity)
        XCTAssertEqual(ProvisionJobs.parseCancel("cancel_result=confirmed_stopped\n"), .confirmedStopped)
        XCTAssertEqual(ProvisionJobs.parseCancel("result=signal_sent\n"), .signalSent)
        XCTAssertEqual(ProvisionJobs.parseCancel(""), .unparseable)
        XCTAssertEqual(ProvisionJobs.parseCancel("result=nonsense\n"), .unparseable)
    }

    func testStatusParsingRefusesToCallAnUnprovenProcessRunning() {
        let running = ProvisionJobs.parseStatus(
            "status=RUNNING\nstep=2\nprocess_alive=yes\nstart_ticks_match=yes\nidentity=verified\npid=99\n"
        )
        XCTAssertEqual(running.effectiveState, .running)

        let unproven = ProvisionJobs.parseStatus(
            "status=RUNNING\nstep=2\nprocess_alive=yes\nstart_ticks_match=yes\nidentity=unverified\npid=99\n"
        )
        XCTAssertEqual(unproven.identity, .unverified)
        XCTAssertEqual(unproven.effectiveState, .unknown)

        let missing = ProvisionJobs.parseStatus("status=RUNNING\nprocess_alive=no\n")
        XCTAssertEqual(missing.identity, .absent)
        XCTAssertEqual(missing.startTicksMatch, nil)

        XCTAssertEqual(ProvisionJobs.parseStatus("status=CANCELLED\n").state, .cancelled)
        XCTAssertEqual(ProvisionJobs.parseStatus("status=WHAT\n").state, .unknown)
    }

    func testDiscoveryAloneNeverPermitsProvisioning() throws {
        let discoveryOnly = DistributionEvidence(
            osId: "debian", osVersion: "12", arch: "x86_64", image: "debian:12",
            imageDigest: "sha256:" + String(repeating: "a", count: 64),
            testedAt: "2026-10-23",
            runUrl: "https://example.invalid/run/1",
            capabilities: [.osDiscovery, .planGeneration]
        )
        let host = facts()
        let tier = ProvisioningEvidence.tier(for: host, images: [discoveryOnly], hostEvidence: [])
        guard case .unverified = tier else { return XCTFail("discovery must not certify installs") }

        let unprivileged = facts(root: false, sudo: false)
        let plan = try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: unprivileged)
        XCTAssertEqual(ProvisionPlanner.decide(plan, facts: unprivileged).decision, .needsInteractivePrivilege)

        var privileged = host
        privileged.isRoot = true
        let privilegedPlan = try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: privileged)
        XCTAssertEqual(ProvisionPlanner.decide(privilegedPlan, facts: privileged).decision, .allowedUnverified)
    }

    func testImageEvidenceStillRequiresTheAcknowledgement() throws {
        let image = DistributionEvidence(
            osId: "debian", osVersion: "12", arch: "x86_64", image: "debian:12",
            imageDigest: "sha256:" + String(repeating: "b", count: 64),
            testedAt: "2026-10-23",
            runUrl: "https://example.invalid/run/1",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        )
        let host = facts(containerized: true)
        let tier = ProvisioningEvidence.tier(for: host, images: [image], hostEvidence: [])
        guard case .testedInImage(let scope) = tier else { return XCTFail("a container image match is image evidence") }
        XCTAssertTrue(scope.contains("supervisor"))

        let plan = ProvisionPlan(
            request: ProvisionRequest(toolIds: ["tmux"]),
            osId: "debian",
            manager: .apt,
            tier: tier,
            steps: [PlanStep(index: 1, packages: ["tmux"], toolIds: ["tmux"])],
            requiresAdmin: false,
            warnings: [],
            stepsDigest: String(repeating: "c", count: 64),
            supportDetail: scope
        )
        XCTAssertEqual(ProvisionPlanner.decide(plan, facts: host).decision, .allowedUnverified)
        let acknowledged = ProvisionPlan(
            request: ProvisionRequest(toolIds: ["tmux"], acknowledgeUnverified: true),
            osId: "debian",
            manager: .apt,
            tier: tier,
            steps: plan.steps,
            requiresAdmin: false,
            warnings: [],
            stepsDigest: plan.stepsDigest,
            supportDetail: scope
        )
        XCTAssertEqual(ProvisionPlanner.decide(acknowledged, facts: host).decision, .allowed)
    }

    func testOnlyThisMachinesOwnEvidenceSkipsTheAcknowledgement() throws {
        let host = facts(machineId: "machine-a")
        let evidence = HostEvidence(
            machineId: "machine-a", osId: "debian", osVersion: "12", arch: "x86_64",
            testedAt: "2026-10-23", runUrl: "https://example.invalid/run/2",
            capabilities: [.packageInstall, .packageVerify, .cancellation, .cleanup]
        )
        guard case .verifiedHost = ProvisioningEvidence.tier(for: host, images: [], hostEvidence: [evidence]) else {
            return XCTFail("own-host evidence should verify")
        }
        guard case .unverified = ProvisioningEvidence.tier(
            for: host, images: [], hostEvidence: [HostEvidence(
                machineId: "machine-b", osId: "debian", osVersion: "12", arch: "x86_64",
                testedAt: "2026-10-23", runUrl: "https://example.invalid/run/3",
                capabilities: [.packageInstall, .packageVerify]
            )]
        ) else {
            return XCTFail("another machine's evidence must not transfer")
        }
        let partial = HostEvidence(
            machineId: "machine-a", osId: "debian", osVersion: "12", arch: "x86_64",
            testedAt: "2026-10-23", runUrl: "https://example.invalid/run/4",
            capabilities: [.packageInstall]
        )
        guard case .unverified = ProvisioningEvidence.tier(for: host, images: [], hostEvidence: [partial]) else {
            return XCTFail("install without verification is not sufficient evidence")
        }
    }

    func testShippedEvidenceIsHonestAboutWhatWasTested() {
        XCTAssertTrue(ProvisioningEvidence.host.isEmpty, "no host run has happened")
        XCTAssertEqual(ProvisioningEvidence.images.count, 6)
        for record in ProvisioningEvidence.images {
            XCTAssertTrue(record.capabilities.contains(.packageInstall))
            XCTAssertTrue(record.capabilities.contains(.packageVerify))
            XCTAssertFalse(record.capabilities.contains(.sshPty))
            XCTAssertFalse(record.capabilities.contains(.sessionDurability))
            XCTAssertFalse(record.capabilities.contains(.rebootDurability))
            XCTAssertTrue(record.runUrl.contains("/actions/runs/"))
            XCTAssertTrue(record.imageDigest.hasPrefix("sha256:"))
            XCTAssertEqual(record.imageDigest.count, 71)
            XCTAssertTrue(record.testedAt.range(of: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", options: .regularExpression) != nil)
        }
    }

    func testManualInstallersAreInstructionsOnly() {
        let ids = ProvisionCatalog.manualInstallers.map(\.id)
        XCTAssertTrue(ids.contains("opencode"))
        for id in ids {
            XCTAssertNil(ProvisionCatalog.tool(id), "manual installers must never be executable catalogue entries")
        }
        for tool in ProvisionCatalog.tools {
            XCTAssertNil(ProvisionCatalog.manualInstallers.first { $0.id == tool.id })
            XCTAssertTrue(tool.id.range(of: "^[a-z][a-z0-9-]*$", options: .regularExpression) != nil)
        }
    }

    func testAlreadyInstalledToolsAndEmptySelectionsAreHandled() throws {
        let host = facts(root: true, installed: ["tmux"])
        let plan = try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: host)
        XCTAssertTrue(plan.steps.isEmpty)
        XCTAssertEqual(ProvisionPlanner.decide(plan, facts: host).decision, .blockedNothingToDo)
        XCTAssertThrowsError(try ProvisionPlanner.plan(ProvisionRequest(toolIds: []), facts: host))
        XCTAssertThrowsError(try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["not-a-tool"]), facts: host))
        XCTAssertThrowsError(try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: facts(manager: nil)))
    }

    func testPreviewCommandsUseTheNativeManagerArguments() throws {
        let plan = try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: facts(root: false, sudo: true))
        XCTAssertEqual(ProvisionPlanner.previewCommands(plan), ["sudo apt-get install -y --no-install-recommends tmux"])

        let dnf = try ProvisionPlanner.plan(ProvisionRequest(toolIds: ["tmux"]), facts: facts(osId: "fedora", manager: .dnf))
        XCTAssertEqual(ProvisionPlanner.previewCommands(dnf), ["dnf install -y tmux"])
        XCTAssertEqual(PackageManagerKind.apt.verifyBinary, "dpkg-query")
        XCTAssertEqual(PackageManagerKind.apk.verifyArguments(["jq"]), ["info", "-e", "jq"])
    }
}
