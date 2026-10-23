import Foundation
import AnthraciteKit

/// Drives guided provisioning over SSH. The phone uploads the reviewed host-kit
/// scripts and a plan, never a generated shell program.
@MainActor
final class ProvisioningViewModel: ObservableObject {
    @Published var facts: HostFacts?
    @Published var plan: ProvisionPlan?
    @Published var selected: Set<String> = []
    @Published var acknowledge = false
    @Published var verdict: PlanVerdict?
    @Published var status: JobStatus?
    @Published var logTail = ""
    @Published var cancelOutcome: CancelOutcome?
    @Published var busy = false
    @Published var message: String?

    /// Injected by the view on appearance. Nothing here constructs a second
    /// connection or a second profile store.
    private weak var model: AppModel?
    private var jobId: String?

    func attach(model: AppModel) {
        if self.model !== model { self.model = model }
    }

    private func script(_ name: String) throws -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "sh", subdirectory: "host"),
              let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty else {
            throw NSError(
                domain: "anthracite",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "The reviewed host script \(name).sh is missing from this build."]
            )
        }
        return text
    }

    func refreshFacts() async {
        guard let model else { return }
        busy = true
        defer { busy = false }
        do {
            let result = try await model.runScript(try script("anthracite-probe"), arguments: [])
            guard result.succeeded else {
                message = "The host probe failed: \(result.stderr.prefix(300))"
                return
            }
            facts = Self.parseFacts(result.stdout)
            message = nil
        } catch {
            message = model.describe(error)
        }
    }

    static func parseFacts(_ output: String) -> HostFacts {
        var values: [String: String] = [:]
        for line in output.split(separator: "\n") {
            guard let separator = line.firstIndex(of: "=") else { continue }
            let key = String(line[line.startIndex..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespaces)
            values[key] = value
        }
        var managers = Set<PackageManagerKind>()
        if values["tool.apt-get"] != nil { managers.insert(.apt) }
        if values["tool.dnf"] != nil { managers.insert(.dnf) }
        if values["tool.zypper"] != nil { managers.insert(.zypper) }
        if values["tool.pacman"] != nil { managers.insert(.pacman) }
        if values["tool.apk"] != nil { managers.insert(.apk) }
        var tools = Set<String>()
        for (key, value) in values where key.hasPrefix("tool.") && !value.isEmpty {
            tools.insert(String(key.dropFirst("tool.".count)))
        }
        return HostFacts(
            osId: values["os_id"] ?? "",
            osVersion: values["os_version_id"] ?? "",
            osPrettyName: values["os_pretty"] ?? "",
            arch: values["arch"] ?? "",
            managers: managers,
            isRoot: values["uid"] == "0",
            sudoNonInteractive: values["sudo_noninteractive"] == "yes",
            diskAvailableKb: Int(values["disk_avail_kb"] ?? "") ?? 0,
            tools: tools,
            containerized: values["container_docker"] == "yes" || !(values["container_hint"] ?? "").isEmpty,
            machineId: values["machine_id"] ?? "",
            capturedAt: Date(),
            home: values["home"] ?? ""
        )
    }

    func buildPlan() async {
        guard let facts, model != nil else { return }
        do {
            let plan = try ProvisionPlanner.plan(
                ProvisionRequest(toolIds: Array(selected), acknowledgeUnverified: acknowledge),
                facts: facts
            )
            self.plan = plan
            verdict = ProvisionPlanner.decide(plan, facts: facts)
        } catch {
            message = "The plan could not be built: \(error)"
            plan = nil
            verdict = nil
        }
    }

    /// Uploads the reviewed files and launches the runner detached, so a dropped
    /// phone connection does not stop the job.
    func start() async {
        guard let model, let plan, let verdict, verdict.canLaunch else { return }
        guard acknowledge || isVerifiedHost(plan.tier) else {
            message = "Confirm that you accept running an unverified plan."
            return
        }
        busy = true
        defer { busy = false }
        do {
            let runner = try script("anthracite-provision-run")
            let jobId = Self.newJobId()
            self.jobId = jobId
            let home = try await remoteHome()
            let absolute = "\(home)/\(ProvisionJobs.jobRoot)/\(jobId)"
            let directory = "'" + absolute + "'"
            let mkdir = try await model.exec("umask 077; mkdir -p \(directory)")
            guard mkdir.succeeded else {
                message = "The job directory could not be created: \(mkdir.stderr.prefix(200))"
                return
            }
            let sftp = try await model.openSFTP()
            let planFile = try ProvisionPlanner.planFile(plan: plan, jobId: jobId)
            let steps = ProvisionPlanner.stepsBytes(plan.steps)
            try await sftp.writeFile("\(absolute)/plan", data: planFile, mode: 0o600)
            try await sftp.writeFile("\(absolute)/steps", data: steps, mode: 0o600)
            try await sftp.writeFile("\(absolute)/anthracite-provision-run.sh",
                                     data: Data(runner.utf8), mode: 0o700)
            await sftp.close()

            let launch = "cd \(directory) && ( setsid sh ./anthracite-provision-run.sh \(directory) </dev/null >/dev/null 2>&1 & ) && echo launched"
            let started = try await model.exec(launch)
            guard started.succeeded else {
                // Fall back once, and only once: `setsid` is not universal.
                let nohup = "cd \(directory) && ( nohup sh ./anthracite-provision-run.sh \(directory) </dev/null >/dev/null 2>&1 & ) && echo launched"
                let retry = try await model.exec(nohup)
                guard retry.succeeded else {
                    message = "The host did not accept the job: \(retry.stderr.prefix(200))"
                    return
                }
            }
            message = nil
            await refreshStatus()
        } catch {
            message = model.describe(error)
        }
    }

    func refreshStatus() async {
        guard let model, let jobId else { return }
        do {
            let home = try await remoteHome()
            let result = try await model.runScript(
                try script("anthracite-provision-status"),
                arguments: ["\(home)/\(ProvisionJobs.jobRoot)/\(jobId)"]
            )
            status = ProvisionJobs.parseStatus(result.stdout)
            logTail = status?.logTail ?? ""
        } catch {
            message = model.describe(error)
        }
    }

    /// The host proves the process identity immediately before signalling; the
    /// phone supplies only the job directory.
    func cancel() async {
        guard let model, let jobId else { return }
        do {
            let home = try await remoteHome()
            let result = try await model.runScript(
                try script("anthracite-provision-cancel"),
                arguments: ["\(home)/\(ProvisionJobs.jobRoot)/\(jobId)"]
            )
            let outcome = ProvisionJobs.parseCancel(result.stdout)
            cancelOutcome = outcome
            message = outcome.explanation
            await refreshStatus()
        } catch {
            message = model.describe(error)
        }
    }

    /// The host's own idea of `$HOME`; never guessed from the profile.
    private func remoteHome() async throws -> String {
        guard let model else { throw SSHError.connectionFailed("Not connected.") }
        if let home = facts?.home, home.hasPrefix("/") { return home }
        let result = try await model.exec("printf '%s' \"$HOME\"")
        let home = result.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        guard home.hasPrefix("/"), !home.contains("'") else {
            throw NSError(
                domain: "anthracite",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "The host did not report a usable home directory."]
            )
        }
        return home
    }

    private func isVerifiedHost(_ tier: SupportTier) -> Bool {
        if case .verifiedHost = tier { return true }
        return false
    }

    static func newJobId() -> String {
        var bytes = [UInt8](repeating: 0, count: 8)
        #if canImport(Security)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        #else
        for index in 0..<bytes.count { bytes[index] = UInt8.random(in: 0...255) }
        #endif
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
