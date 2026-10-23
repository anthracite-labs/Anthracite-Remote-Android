import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

// MARK: - Catalogue

public enum PackageManagerKind: String, CaseIterable, Sendable {
    case apt, dnf, zypper, pacman, apk

    public var display: String {
        switch self {
        case .apt: return "apt"
        case .dnf: return "dnf"
        case .zypper: return "zypper"
        case .pacman: return "pacman"
        case .apk: return "apk"
        }
    }

    /// The binary the planner calls. `apt` is a front end; `apt-get` is the
    /// non-interactive interface that never prints a progress bar.
    public var binary: String {
        switch self {
        case .apt: return "apt-get"
        case .dnf: return "dnf"
        case .zypper: return "zypper"
        case .pacman: return "pacman"
        case .apk: return "apk"
        }
    }

    public var installArguments: [String] {
        switch self {
        case .apt: return ["install", "-y", "--no-install-recommends"]
        case .dnf: return ["install", "-y"]
        case .zypper: return ["--non-interactive", "install", "--no-recommends"]
        case .pacman: return ["-S", "--noconfirm", "--needed"]
        case .apk: return ["add", "--no-progress"]
        }
    }

    /// Verification asks the package database, never `command -v`: a binary on
    /// PATH does not prove its package is installed, and a same-named shim on
    /// PATH proves nothing at all.
    public var verifyBinary: String {
        switch self {
        case .apt: return "dpkg-query"
        case .dnf, .zypper: return "rpm"
        case .pacman: return "pacman"
        case .apk: return "apk"
        }
    }

    public func verifyArguments(_ packages: [String]) -> [String] {
        switch self {
        case .apt:
            return ["-W", "-f=${Status}\n", "--"] + packages
        case .dnf, .zypper:
            return ["-q"] + packages
        case .pacman:
            return ["-Q"] + packages
        case .apk:
            return ["info", "-e"] + packages
        }
    }
}

public struct ProvisionTool: Sendable, Hashable {
    public let id: String
    public let label: String
    public let executable: String
    public let group: String
    public let packages: [PackageManagerKind: [String]]

    public func packages(for manager: PackageManagerKind) -> [String] {
        packages[manager] ?? []
    }
}

public enum ProvisionCatalog {
    public static let version = "3"

    public static let tools: [ProvisionTool] = [
        ProvisionTool(id: "tmux", label: "tmux", executable: "tmux", group: "shell",
                      packages: [.apt: ["tmux"], .dnf: ["tmux"], .zypper: ["tmux"], .pacman: ["tmux"], .apk: ["tmux"]]),
        ProvisionTool(id: "git", label: "Git", executable: "git", group: "shell",
                      packages: [.apt: ["git"], .dnf: ["git"], .zypper: ["git"], .pacman: ["git"], .apk: ["git"]]),
        ProvisionTool(id: "ripgrep", label: "ripgrep", executable: "rg", group: "shell",
                      packages: [.apt: ["ripgrep"], .dnf: ["ripgrep"], .zypper: ["ripgrep"], .pacman: ["ripgrep"], .apk: ["ripgrep"]]),
        ProvisionTool(id: "fzf", label: "fzf", executable: "fzf", group: "shell",
                      packages: [.apt: ["fzf"], .dnf: ["fzf"], .zypper: ["fzf"], .pacman: ["fzf"], .apk: ["fzf"]]),
        ProvisionTool(id: "tree", label: "tree", executable: "tree", group: "shell",
                      packages: [.apt: ["tree"], .dnf: ["tree"], .zypper: ["tree"], .pacman: ["tree"], .apk: ["tree"]]),
        ProvisionTool(id: "less", label: "less", executable: "less", group: "shell",
                      packages: [.apt: ["less"], .dnf: ["less"], .zypper: ["less"], .pacman: ["less"], .apk: ["less"]]),
        ProvisionTool(id: "jq", label: "jq", executable: "jq", group: "tools",
                      packages: [.apt: ["jq"], .dnf: ["jq"], .zypper: ["jq"], .pacman: ["jq"], .apk: ["jq"]]),
        ProvisionTool(id: "htop", label: "htop", executable: "htop", group: "tools",
                      packages: [.apt: ["htop"], .dnf: ["htop"], .zypper: ["htop"], .pacman: ["htop"], .apk: ["htop"]]),
        ProvisionTool(id: "wget", label: "wget", executable: "wget", group: "networking",
                      packages: [.apt: ["wget"], .dnf: ["wget"], .zypper: ["wget"], .pacman: ["wget"], .apk: ["wget"]]),
        ProvisionTool(id: "curl", label: "curl", executable: "curl", group: "networking",
                      packages: [.apt: ["curl"], .dnf: ["curl"], .zypper: ["curl"], .pacman: ["curl"], .apk: ["curl"]]),
        ProvisionTool(id: "sqlite", label: "SQLite", executable: "sqlite3", group: "database",
                      packages: [.apt: ["sqlite3"], .dnf: ["sqlite"], .zypper: ["sqlite3"], .pacman: ["sqlite"], .apk: ["sqlite"]]),
    ]

    public static func tool(_ id: String) -> ProvisionTool? {
        tools.first { $0.id == id }
    }

    /// Tools whose vendors publish no pin-verifiable artifact for every
    /// distribution. They are instructions for the user, never executed by the
    /// app, and they are deliberately absent from `tools`.
    public struct ManualInstaller: Sendable, Hashable {
        public let id: String
        public let label: String
        public let reason: String
        public let instructions: String
    }

    public static let manualInstallers: [ManualInstaller] = [
        ManualInstaller(
            id: "opencode",
            label: "OpenCode",
            reason: "The managed install is the pinned OpenCode journey in this app; no package recipe is reviewed.",
            instructions: "Use Home → Install & Launch OpenCode, which verifies a pinned release digest."
        ),
        ManualInstaller(
            id: "claude-code",
            label: "Claude Code",
            reason: "The vendor publishes a network installer, not a signed package for every distribution.",
            instructions: "Follow the vendor's published instructions in a terminal tab, as your own user."
        ),
        ManualInstaller(
            id: "codex",
            label: "Codex CLI",
            reason: "The vendor publishes a network installer, not a signed package for every distribution.",
            instructions: "Follow the vendor's published instructions in a terminal tab, as your own user."
        ),
        ManualInstaller(
            id: "gemini",
            label: "Gemini CLI",
            reason: "The vendor publishes a network installer, not a signed package for every distribution.",
            instructions: "Follow the vendor's published instructions in a terminal tab, as your own user."
        ),
    ]
}

// MARK: - Host facts

public struct HostFacts: Sendable {
    public var osId: String
    public var osVersion: String
    public var osPrettyName: String
    public var arch: String
    public var managers: Set<PackageManagerKind>
    public var isRoot: Bool
    public var sudoNonInteractive: Bool
    public var diskAvailableKb: Int
    public var tools: Set<String>
    public var containerized: Bool
    public var machineId: String
    public var capturedAt: Date
    /// The SSH user's home directory, as the host reported it.
    public var home: String

    public init(
        osId: String,
        osVersion: String,
        osPrettyName: String,
        arch: String,
        managers: Set<PackageManagerKind>,
        isRoot: Bool,
        sudoNonInteractive: Bool,
        diskAvailableKb: Int,
        tools: Set<String>,
        containerized: Bool,
        machineId: String,
        capturedAt: Date,
        home: String = ""
    ) {
        self.osId = osId
        self.osVersion = osVersion
        self.osPrettyName = osPrettyName
        self.arch = arch
        self.managers = managers
        self.isRoot = isRoot
        self.sudoNonInteractive = sudoNonInteractive
        self.diskAvailableKb = diskAvailableKb
        self.tools = tools
        self.containerized = containerized
        self.machineId = machineId
        self.capturedAt = capturedAt
        self.home = home
    }

    /// The manager the plan uses: the first one the host reports, in a fixed
    /// order so the same host always produces the same plan.
    public var manager: PackageManagerKind? {
        for kind in [PackageManagerKind.apt, .dnf, .zypper, .pacman, .apk] where managers.contains(kind) {
            return kind
        }
        return nil
    }
}

// MARK: - Evidence

/// Capabilities that are proven separately. A read-only probe proves discovery;
/// it never proves installation, and a container proves even less about a host.
public enum ProvisionCapability: String, Sendable, CaseIterable {
    case sshPty, osDiscovery, planGeneration, packageInstall, packageVerify
    case cancellation, cleanup, sessionDurability, rebootDurability, containerRuntime, kubernetes
}

public struct DistributionEvidence: Sendable {
    public let osId: String
    public let osVersion: String
    public let arch: String
    public let image: String
    public let imageDigest: String
    public let testedAt: String
    public let runUrl: String
    public let capabilities: Set<ProvisionCapability>
}

/// The tier of *this* host. A distribution record never promotes a host.
public enum SupportTier: Sendable {
    case verifiedHost
    case testedInImage(String)
    case unverified(String)

    public var label: String {
        switch self {
        case .verifiedHost: return "Verified on this host"
        case .testedInImage: return "Installation tested in a container image, not on this host"
        case .unverified: return "Not verified for this distribution"
        }
    }
}

public struct HostEvidence: Sendable {
    public let machineId: String
    public let osId: String
    public let osVersion: String
    public let arch: String
    public let testedAt: String
    public let runUrl: String
    public let capabilities: Set<ProvisionCapability>

    public var installTested: Bool {
        capabilities.contains(.packageInstall) && capabilities.contains(.packageVerify)
    }
}

public enum ProvisioningEvidence {
    /// Empty until a host run has actually completed on that machine. The iOS
    /// client ships no host evidence, because none exists.
    public static let host: [HostEvidence] = []

    public static let images: [DistributionEvidence] = [
        DistributionEvidence(
            osId: "debian", osVersion: "12", arch: "x86_64", image: "debian:12",
            imageDigest: "sha256:854dd8ab51d62866dbd8f5431d15ce77b12983a2a4a1e1fe43a2395dc7ef9f61",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
        DistributionEvidence(
            osId: "ubuntu", osVersion: "24.04", arch: "x86_64", image: "ubuntu:24.04",
            imageDigest: "sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
        DistributionEvidence(
            osId: "fedora", osVersion: "42", arch: "x86_64", image: "fedora:42",
            imageDigest: "sha256:99e203b80b1c3d8f7e161ec10a68fd02b081ef83a3963553e513c82846b97814",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
        DistributionEvidence(
            osId: "arch", osVersion: "", arch: "x86_64", image: "archlinux:latest",
            imageDigest: "sha256:52dfbd5afb23a220d5ee92dafcb4c788d96c026ed04179890c23adef4c897fa0",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
        DistributionEvidence(
            osId: "opensuse-tumbleweed", osVersion: "", arch: "x86_64", image: "opensuse/tumbleweed:latest",
            imageDigest: "sha256:0c6f00841281acf55a8ef29e777fc2db40d0923289ba933d566542cff2cddc00",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
        DistributionEvidence(
            osId: "alpine", osVersion: "3.21", arch: "x86_64", image: "alpine:3.21",
            imageDigest: "sha256:bbfcd601d7cd20d35bb0b8b866ad1a8a0d107ca3e1c20870c75c5ce2c1186d30",
            testedAt: "2026-10-23",
            runUrl: "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212",
            capabilities: [.osDiscovery, .planGeneration, .packageInstall, .packageVerify, .cancellation, .cleanup]
        ),
    ]

    public static func tier(
        for facts: HostFacts,
        images: [DistributionEvidence] = ProvisioningEvidence.images,
        hostEvidence: [HostEvidence] = ProvisioningEvidence.host
    ) -> SupportTier {
        if let record = hostEvidence.first(where: {
            $0.machineId == facts.machineId && $0.osId == facts.osId && $0.arch == facts.arch && $0.installTested
        }) {
            return .verifiedHost
        }
        if let image = images.first(where: {
            $0.osId == facts.osId && $0.arch == facts.arch
                && $0.capabilities.contains(.packageInstall) && $0.capabilities.contains(.packageVerify)
        }) {
            let scope = facts.containerized
                ? "The same image (\(image.image)) completed install and verify on \(image.testedAt); supervisor, logout and reboot behaviour were not tested."
                : "This distribution was tested in a container image (\(image.image), \(image.testedAt)), not on your machine. Package availability, the supervisor, logout and reboot behaviour are unverified."
            return .testedInImage(scope)
        }
        return .unverified(
            "This distribution and architecture are not in the tested matrix. Read-only discovery does not certify package installation."
        )
    }
}

// MARK: - Plan

public struct PlanStep: Sendable, Equatable {
    public let index: Int
    public let packages: [String]
    public let toolIds: [String]
}

public struct ProvisionRequest: Sendable {
    public var toolIds: [String]
    public var acknowledgeUnverified: Bool

    public init(toolIds: [String], acknowledgeUnverified: Bool = false) {
        self.toolIds = toolIds
        self.acknowledgeUnverified = acknowledgeUnverified
    }
}

public enum PlanDecision: Sendable, Equatable {
    case allowed
    case allowedUnverified
    case needsInteractivePrivilege
    case blockedNoManager
    case blockedNothingToDo
    case blockedInsufficientDisk
}

public struct PlanVerdict: Sendable {
    public let decision: PlanDecision
    public let explanation: String
    public let canLaunch: Bool
}

public enum ProvisionError: Error, Equatable {
    case invalidJobId
    case invalidPlanHash
    case emptySelection
    case noManager
    case unknownTool(String)
}

public struct ProvisionPlan: Sendable {
    public let request: ProvisionRequest
    public let osId: String
    public let manager: PackageManagerKind
    public let tier: SupportTier
    public let steps: [PlanStep]
    public let requiresAdmin: Bool
    public let warnings: [String]
    public let stepsDigest: String
    public let supportDetail: String
}

public enum ProvisionPlanner {
    public static let maxTools = 32
    public static let maxPackages = 64
    public static let packagesPerStep = 8
    public static let minDiskKb = 200 * 1024

    public static func plan(_ request: ProvisionRequest, facts: HostFacts) throws -> ProvisionPlan {
        guard let manager = facts.manager else { throw ProvisionError.noManager }
        guard !request.toolIds.isEmpty else { throw ProvisionError.emptySelection }
        var warnings: [String] = []
        var selected = request.toolIds
        if selected.count > maxTools {
            selected = Array(selected.prefix(maxTools))
            warnings.append("Only the first \(maxTools) selections were planned.")
        }
        var packages: [String] = []
        for id in selected {
            guard let tool = ProvisionCatalog.tool(id) else {
                throw ProvisionError.unknownTool(id)
            }
            let names = tool.packages(for: manager)
            if names.isEmpty {
                warnings.append("\(tool.label) has no reviewed \(manager.display) package.")
                continue
            }
            if facts.tools.contains(tool.executable) { continue }
            for name in names where !packages.contains(name) {
                guard name.range(of: "^[a-z0-9][a-z0-9+._-]{0,63}$", options: .regularExpression) != nil else {
                    throw ProvisionError.unknownTool(id)
                }
                packages.append(name)
            }
        }
        if packages.count > maxPackages {
            packages = Array(packages.prefix(maxPackages))
            warnings.append("The selection exceeds \(maxPackages) packages and was truncated.")
        }
        let steps = packages.chunked(into: packagesPerStep).enumerated().map { index, chunk in
            PlanStep(index: index + 1, packages: chunk, toolIds: [])
        }
        let tier = ProvisioningEvidence.tier(for: facts)
        let detail: String
        switch tier {
        case .verifiedHost:
            detail = "This machine completed install and verify on the recorded date."
        case .testedInImage(let scope):
            detail = scope
            warnings.append(scope)
        case .unverified(let reason):
            detail = reason
            warnings.append(reason)
        }
        if facts.containerized {
            warnings.append("The host appears to be a container. Package changes affect that container only.")
        }
        if facts.isRoot {
            warnings.append("The SSH user is root. Every command runs as root; a non-root user is safer.")
        }
        if !steps.isEmpty && !facts.sudoNonInteractive && !facts.isRoot {
            warnings.append("Passwordless sudo was not detected. The job cannot prompt for a password.")
        }
        let requiresAdmin = !steps.isEmpty && !facts.isRoot
        return ProvisionPlan(
            request: request,
            osId: facts.osId,
            manager: manager,
            tier: tier,
            steps: steps,
            requiresAdmin: requiresAdmin,
            warnings: warnings,
            stepsDigest: digest(stepsBytes(steps)),
            supportDetail: detail
        )
    }

    public static func decide(_ plan: ProvisionPlan, facts: HostFacts) -> PlanVerdict {
        if plan.steps.isEmpty {
            return PlanVerdict(
                decision: .blockedNothingToDo,
                explanation: "Every selected tool is already installed, or nothing in the selection can be installed on this host.",
                canLaunch: false
            )
        }
        if facts.diskAvailableKb > 0 && facts.diskAvailableKb < minDiskKb {
            return PlanVerdict(
                decision: .blockedInsufficientDisk,
                explanation: "Only \(facts.diskAvailableKb / 1024) MB is free on the host; at least 200 MB is required.",
                canLaunch: false
            )
        }
        if plan.requiresAdmin && !facts.sudoNonInteractive && !facts.isRoot {
            return PlanVerdict(
                decision: .needsInteractivePrivilege,
                explanation: "Installing system packages needs administrator rights, and passwordless sudo is not available. Run the shown commands in a terminal tab, where you can authorize them yourself. Anthracite never stores or forwards a sudo password.",
                canLaunch: false
            )
        }
        // Only a run this exact host completed may skip the acknowledgement.
        if !isVerifiedHost(plan.tier) && !plan.request.acknowledgeUnverified {
            let why = plan.supportDetail.isEmpty
                ? "Read-only discovery does not certify package installation."
                : plan.supportDetail
            return PlanVerdict(
                decision: .allowedUnverified,
                explanation: "\(why) Review the exact commands, then confirm that you accept running them at your own risk.",
                canLaunch: true
            )
        }
        return PlanVerdict(
            decision: .allowed,
            explanation: "The plan is ready. Review the commands and authorize it.",
            canLaunch: true
        )
    }

    private static func isVerifiedHost(_ tier: SupportTier) -> Bool {
        if case .verifiedHost = tier { return true }
        return false
    }

    public static func previewCommands(_ plan: ProvisionPlan) -> [String] {
        let prefix = plan.requiresAdmin ? "sudo " : ""
        return plan.steps.map { step in
            let packages = step.packages.joined(separator: " ")
            return "\(prefix)\(plan.manager.binary) \(plan.manager.installArguments.joined(separator: " ")) \(packages)"
        }
    }

    public static func stepsBytes(_ steps: [PlanStep]) -> Data {
        var text = ""
        for step in steps {
            text += "\(step.index)\tinstall\t\(step.packages.joined(separator: " "))\n"
        }
        return Data(text.utf8)
    }

    public static func digest(_ data: Data) -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #else
        return SHA256Fallback.hex(data)
        #endif
    }

    public static func planFile(plan: ProvisionPlan, jobId: String) throws -> Data {
        guard jobId.range(of: "^[a-f0-9]{16}$", options: .regularExpression) != nil else {
            throw ProvisionError.invalidJobId
        }
        guard plan.stepsDigest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
            throw ProvisionError.invalidPlanHash
        }
        let text = """
        job_id=\(jobId)
        manager=\(plan.manager.binary)
        admin=\(plan.requiresAdmin ? "1" : "0")
        os_id=\(plan.osId)
        catalog_version=\(ProvisionCatalog.version)
        plan_hash=\(plan.stepsDigest)

        """
        return Data(text.utf8)
    }
}

// MARK: - Jobs

public enum JobState: String, Sendable {
    case planned = "PLANNED"
    case approved = "APPROVED"
    case queued = "QUEUED"
    case running = "RUNNING"
    case succeeded = "SUCCEEDED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
    case interrupted = "INTERRUPTED"
    case locked = "LOCKED"
    case unknown = "UNKNOWN"

    public var isTerminal: Bool {
        switch self {
        case .succeeded, .failed, .cancelled, .interrupted: return true
        default: return false
        }
    }
}

public struct JobStatus: Sendable {
    public let state: JobState
    public let step: String
    public let processAlive: Bool
    public let startTicksMatch: Bool?
    public let identity: IdentityState
    public let pid: String?
    public let logTail: String
    public let reason: String?

    /// A `RUNNING` record whose live process cannot be proven is not "running":
    /// it is unknown, and the UI says so.
    public var effectiveState: JobState {
        if state == .running && identity != .verified && processAlive { return .unknown }
        return state
    }
}

public enum IdentityState: String, Sendable {
    case verified, unverified, absent

    public static func parse(_ value: String?) -> IdentityState {
        switch value {
        case "verified": return .verified
        case "unverified": return .unverified
        default: return .absent
        }
    }
}

public enum CancelOutcome: String, Sendable {
    case refusedInvalidJob = "refused_invalid_job"
    case refusedUnknownIdentity = "refused_unknown_identity"
    case refusedNotOwner = "refused_not_owner"
    case alreadyFinished = "already_finished"
    case signalSent = "signal_sent"
    case stillRunning = "still_running"
    case confirmedStopped = "confirmed_stopped"
    case signalFailed = "signal_failed"
    case unparseable = "unparseable"

    public var explanation: String {
        switch self {
        case .refusedInvalidJob: return "The job path was invalid, so nothing was signalled."
        case .refusedUnknownIdentity: return "The host could not prove that the process is this job's runner. Nothing was signalled."
        case .refusedNotOwner: return "The job files are owned by another user. Nothing was signalled."
        case .alreadyFinished: return "The job had already finished. Nothing was signalled."
        case .signalSent: return "A stop request was delivered. The runner stops at the next safe boundary."
        case .stillRunning: return "The runner is still running; a package transaction may be finishing. It was not force-killed."
        case .confirmedStopped: return "The runner exited and the job is recorded as cancelled."
        case .signalFailed: return "The signal could not be delivered and nothing was confirmed stopped."
        case .unparseable: return "The host did not return a result this app understands. Nothing can be assumed."
        }
    }

    /// Only an observed exit may be presented as stopped.
    public var isConfirmed: Bool { self == .confirmedStopped || self == .alreadyFinished }
}

public enum ProvisionJobs {
    public static let jobRoot = ".anthracite/jobs"
    public static let runnerFile = "anthracite-provision-run.sh"
    public static let statusFile = "anthracite-provision-status.sh"
    public static let cancelFile = "anthracite-provision-cancel.sh"
    private static let jobIdPattern = "^[a-f0-9]{16}$"

    public static func isValidJobId(_ id: String) -> Bool {
        id.range(of: jobIdPattern, options: .regularExpression) != nil
    }

    public static func directory(_ id: String) -> String {
        "\(jobRoot)/\(id)"
    }

    /// The status command. The reviewed script is streamed on stdin so nothing
    /// is read from a path the app did not create.
    public static func statusCommand(_ id: String) -> String? {
        guard isValidJobId(id) else { return nil }
        return "sh -s \"$HOME/\(jobRoot)/\(id)\""
    }

    /// Cancellation is a host-side compare-and-signal. The phone supplies the job
    /// directory and never a PID, so it cannot signal an arbitrary process.
    public static func cancelCommand(_ id: String) -> String? {
        guard isValidJobId(id) else { return nil }
        return "sh -s \"$HOME/\(jobRoot)/\(id)\""
    }

    public static func parseStatus(_ stdout: String) -> JobStatus {
        var values: [String: String] = [:]
        for line in stdout.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let separator = line.firstIndex(of: "=") else { continue }
            let key = String(line[line.startIndex..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespaces)
            if !key.isEmpty { values[key] = value }
        }
        let state = JobState(rawValue: values["status"]?.uppercased() ?? "") ?? .unknown
        let ticks: Bool?
        switch values["start_ticks_match"] {
        case "yes": ticks = true
        case "no": ticks = false
        default: ticks = nil
        }
        return JobStatus(
            state: state,
            step: values["step"] ?? "",
            processAlive: values["process_alive"] == "yes",
            startTicksMatch: ticks,
            identity: IdentityState.parse(values["identity"]),
            pid: values["pid"],
            logTail: values["log_tail"] ?? "",
            reason: values["reason"]
        )
    }

    public static func parseCancel(_ stdout: String) -> CancelOutcome {
        for line in stdout.split(separator: "\n") {
            for prefix in ["cancel_result=", "result="] where line.hasPrefix(prefix) {
                return CancelOutcome(rawValue: String(line.dropFirst(prefix.count))) ?? .unparseable
            }
        }
        return .unparseable
    }
}

// MARK: - Small helpers

extension Array where Element == String {
    func chunked(into size: Int) -> [[String]] {
        guard size > 0 else { return isEmpty ? [] : [self] }
        var result: [[String]] = []
        var index = 0
        while index < count {
            result.append(Array(self[index..<Swift.min(index + size, count)]))
            index += size
        }
        return result
    }
}


/// A small SHA-256 used only when CryptoKit is unavailable (Linux test runs).
/// The app always uses CryptoKit on Apple platforms.
enum SHA256Fallback {
    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hex(_ data: Data) -> String {
        digestBytes(data).map { String(format: "%02x", $0) }.joined()
    }

    static func digestBytes(_ data: Data) -> [UInt8] {
        var h: [UInt32] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                           0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        var message = [UInt8](data)
        let bitLength = UInt64(message.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 { message.append(0) }
        for shift in stride(from: 56, through: 0, by: -8) {
            message.append(UInt8((bitLength >> UInt64(shift)) & 0xff))
        }
        var w = [UInt32](repeating: 0, count: 64)
        for chunk in stride(from: 0, to: message.count, by: 64) {
            for index in 0..<16 {
                let base = chunk + index * 4
                w[index] = (UInt32(message[base]) << 24) | (UInt32(message[base + 1]) << 16)
                    | (UInt32(message[base + 2]) << 8) | UInt32(message[base + 3])
            }
            for index in 16..<64 {
                let s0 = rotr(w[index - 15], 7) ^ rotr(w[index - 15], 18) ^ (w[index - 15] >> 3)
                let s1 = rotr(w[index - 2], 17) ^ rotr(w[index - 2], 19) ^ (w[index - 2] >> 10)
                w[index] = w[index - 16] &+ s0 &+ w[index - 7] &+ s1
            }
            var a = h[0], b = h[1], c = h[2], d = h[3]
            var e = h[4], f = h[5], g = h[6], hh = h[7]
            for index in 0..<64 {
                let s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
                let ch = (e & f) ^ (~e & g)
                let t1 = hh &+ s1 &+ ch &+ k[index] &+ w[index]
                let s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
                let maj = (a & b) ^ (a & c) ^ (b & c)
                let t2 = s0 &+ maj
                hh = g; g = f; f = e; e = d &+ t1
                d = c; c = b; b = a; a = t1 &+ t2
            }
            h[0] = h[0] &+ a; h[1] = h[1] &+ b; h[2] = h[2] &+ c; h[3] = h[3] &+ d
            h[4] = h[4] &+ e; h[5] = h[5] &+ f; h[6] = h[6] &+ g; h[7] = h[7] &+ hh
        }
        var output: [UInt8] = []
        output.reserveCapacity(32)
        for word in h {
            output.append(UInt8((word >> 24) & 0xff))
            output.append(UInt8((word >> 16) & 0xff))
            output.append(UInt8((word >> 8) & 0xff))
            output.append(UInt8(word & 0xff))
        }
        return output
    }

    private static func rotr(_ value: UInt32, _ count: UInt32) -> UInt32 {
        (value >> count) | (value << (32 - count))
    }
}
