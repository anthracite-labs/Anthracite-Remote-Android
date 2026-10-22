package dev.anthracite.remote.host

import dev.anthracite.remote.SshClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A reviewed catalogue entry. Presets are data; they are never executable code. */
internal data class CatalogTool(
    val id: String,
    val label: String,
    val description: String,
    val category: Category,
    /** Executable used only to *detect* an existing installation. */
    val executable: String,
    /** Native package names per manager. A missing entry means "not offered here". */
    val packages: Map<PackageManagerKind, List<String>>,
    val requiresAdmin: Boolean = true,
) {
    enum class Category(val label: String) {
        TERMINAL("Terminal"),
        VERSION_CONTROL("Version control"),
        FILES("Files"),
        RUNTIME("Runtimes"),
        BUILD("Build tools"),
        UTILITY("Utilities"),
    }

    fun packagesFor(manager: PackageManagerKind): List<String> = packages[manager].orEmpty()
}

internal data class CatalogProfile(val id: String, val label: String, val description: String, val toolIds: List<String>)

/**
 * The shipped catalogue.
 *
 * Only native distribution packages are used: there is no `curl | sh`, no
 * downloaded binary and therefore no artifact trust question to answer here.
 * Package names are per manager because names are *not* portable across
 * distributions. Everything listed is present in the standard repositories of
 * the supported families.
 */
internal object ProvisionCatalog {
    const val VERSION = "2026-10-22.1"

    val tools: List<CatalogTool> = listOf(
        tool(
            id = "git", label = "Git", description = "Version control client", category = CatalogTool.Category.VERSION_CONTROL,
            executable = "git",
            packages = mapOf(
                PackageManagerKind.APT to listOf("git"),
                PackageManagerKind.DNF to listOf("git"),
                PackageManagerKind.ZYPPER to listOf("git"),
                PackageManagerKind.PACMAN to listOf("git"),
                PackageManagerKind.APK to listOf("git"),
            ),
        ),
        tool(
            id = "tmux", label = "tmux", description = "Detachable terminal sessions", category = CatalogTool.Category.TERMINAL,
            executable = "tmux",
            packages = mapOf(
                PackageManagerKind.APT to listOf("tmux"),
                PackageManagerKind.DNF to listOf("tmux"),
                PackageManagerKind.ZYPPER to listOf("tmux"),
                PackageManagerKind.PACMAN to listOf("tmux"),
                PackageManagerKind.APK to listOf("tmux"),
            ),
        ),
        tool(
            id = "curl", label = "curl", description = "HTTP client", category = CatalogTool.Category.UTILITY,
            executable = "curl",
            packages = mapOf(
                PackageManagerKind.APT to listOf("curl"),
                PackageManagerKind.DNF to listOf("curl"),
                PackageManagerKind.ZYPPER to listOf("curl"),
                PackageManagerKind.PACMAN to listOf("curl"),
                PackageManagerKind.APK to listOf("curl"),
            ),
        ),
        tool(
            id = "unzip", label = "unzip", description = "ZIP extraction", category = CatalogTool.Category.FILES,
            executable = "unzip",
            packages = mapOf(
                PackageManagerKind.APT to listOf("unzip"),
                PackageManagerKind.DNF to listOf("unzip"),
                PackageManagerKind.ZYPPER to listOf("unzip"),
                PackageManagerKind.PACMAN to listOf("unzip"),
                PackageManagerKind.APK to listOf("unzip"),
            ),
        ),
        tool(
            id = "jq", label = "jq", description = "JSON processor", category = CatalogTool.Category.UTILITY,
            executable = "jq",
            packages = mapOf(
                PackageManagerKind.APT to listOf("jq"),
                PackageManagerKind.DNF to listOf("jq"),
                PackageManagerKind.ZYPPER to listOf("jq"),
                PackageManagerKind.PACMAN to listOf("jq"),
                PackageManagerKind.APK to listOf("jq"),
            ),
        ),
        tool(
            id = "htop", label = "htop", description = "Interactive process viewer", category = CatalogTool.Category.TERMINAL,
            executable = "htop",
            packages = mapOf(
                PackageManagerKind.APT to listOf("htop"),
                PackageManagerKind.DNF to listOf("htop"),
                PackageManagerKind.ZYPPER to listOf("htop"),
                PackageManagerKind.PACMAN to listOf("htop"),
                PackageManagerKind.APK to listOf("htop"),
            ),
        ),
        tool(
            id = "vim", label = "vim", description = "Terminal editor", category = CatalogTool.Category.TERMINAL,
            executable = "vim",
            packages = mapOf(
                PackageManagerKind.APT to listOf("vim"),
                PackageManagerKind.DNF to listOf("vim-enhanced"),
                PackageManagerKind.ZYPPER to listOf("vim"),
                PackageManagerKind.PACMAN to listOf("vim"),
                PackageManagerKind.APK to listOf("vim"),
            ),
        ),
        tool(
            id = "ripgrep", label = "ripgrep", description = "Fast recursive search", category = CatalogTool.Category.UTILITY,
            executable = "rg",
            packages = mapOf(
                PackageManagerKind.APT to listOf("ripgrep"),
                PackageManagerKind.DNF to listOf("ripgrep"),
                PackageManagerKind.ZYPPER to listOf("ripgrep"),
                PackageManagerKind.PACMAN to listOf("ripgrep"),
                PackageManagerKind.APK to listOf("ripgrep"),
            ),
        ),
        tool(
            id = "rsync", label = "rsync", description = "Incremental file transfer", category = CatalogTool.Category.FILES,
            executable = "rsync",
            packages = mapOf(
                PackageManagerKind.APT to listOf("rsync"),
                PackageManagerKind.DNF to listOf("rsync"),
                PackageManagerKind.ZYPPER to listOf("rsync"),
                PackageManagerKind.PACMAN to listOf("rsync"),
                PackageManagerKind.APK to listOf("rsync"),
            ),
        ),
        tool(
            id = "python", label = "Python 3", description = "Python runtime", category = CatalogTool.Category.RUNTIME,
            executable = "python3",
            packages = mapOf(
                PackageManagerKind.APT to listOf("python3"),
                PackageManagerKind.DNF to listOf("python3"),
                PackageManagerKind.ZYPPER to listOf("python3"),
                // Arch ships Python 3 as `python`; there is no `python3` package.
                PackageManagerKind.PACMAN to listOf("python"),
                PackageManagerKind.APK to listOf("python3"),
            ),
        ),
        tool(
            id = "node", label = "Node.js", description = "Node.js runtime", category = CatalogTool.Category.RUNTIME,
            executable = "node",
            packages = mapOf(
                PackageManagerKind.APT to listOf("nodejs"),
                PackageManagerKind.DNF to listOf("nodejs"),
                PackageManagerKind.ZYPPER to listOf("nodejs"),
                PackageManagerKind.PACMAN to listOf("nodejs"),
                PackageManagerKind.APK to listOf("nodejs"),
            ),
        ),
        tool(
            id = "make", label = "make", description = "Build automation", category = CatalogTool.Category.BUILD,
            executable = "make",
            packages = mapOf(
                PackageManagerKind.APT to listOf("make"),
                PackageManagerKind.DNF to listOf("make"),
                PackageManagerKind.ZYPPER to listOf("make"),
                PackageManagerKind.PACMAN to listOf("make"),
                PackageManagerKind.APK to listOf("make"),
            ),
        ),
        tool(
            id = "gcc", label = "GCC", description = "C compiler", category = CatalogTool.Category.BUILD,
            executable = "gcc",
            packages = mapOf(
                PackageManagerKind.APT to listOf("gcc"),
                PackageManagerKind.DNF to listOf("gcc"),
                PackageManagerKind.ZYPPER to listOf("gcc"),
                PackageManagerKind.PACMAN to listOf("gcc"),
                PackageManagerKind.APK to listOf("gcc"),
            ),
        ),
    )

    val profiles: List<CatalogProfile> = listOf(
        CatalogProfile(
            id = "base",
            label = "Recommended base",
            description = "Git, tmux and the small utilities every host workflow expects.",
            toolIds = listOf("git", "tmux", "curl", "unzip", "jq"),
        ),
        CatalogProfile(
            id = "developer",
            label = "Developer additions",
            description = "Adds Python, Node.js, make and GCC for building projects on the host.",
            toolIds = listOf("python", "node", "make", "gcc"),
        ),
        CatalogProfile(
            id = "terminal",
            label = "Terminal extras",
            description = "htop, vim, ripgrep and rsync.",
            toolIds = listOf("htop", "vim", "ripgrep", "rsync"),
        ),
    )

    fun tool(id: String): CatalogTool? = tools.firstOrNull { it.id == id }

    fun profile(id: String): CatalogProfile? = profiles.firstOrNull { it.id == id }

    private fun tool(
        id: String,
        label: String,
        description: String,
        category: CatalogTool.Category,
        executable: String,
        packages: Map<PackageManagerKind, List<String>>,
        requiresAdmin: Boolean = true,
    ): CatalogTool {
        require(PosixShell.isIdentifier(id)) { "catalog id is invalid: $id" }
        require(PosixShell.isExecutableToken(executable)) { "catalog executable is invalid: $executable" }
        packages.values.flatten().forEach { require(PosixShell.isPackageName(it)) { "package name is invalid: $it" } }
        return CatalogTool(id, label, description, category, executable, packages, requiresAdmin)
    }
}

/**
 * Verified support tiers. A tier is only raised with evidence: a container run
 * proves package names and manager behaviour, not supervisor or logout
 * behaviour, which needs a real host or VM.
 */
internal enum class SupportTier(val label: String) {
    VERIFIED_HOST("Verified on a real host"),
    VERIFIED_CONTAINER("Verified in a container (supervisor/logout untested)"),
    UNVERIFIED("Not verified for this distribution"),
}

internal object ProvisioningSupport {
    /**
     * Evidence for each entry lives in `docs/support-matrix.md` with the CI run or
     * host that produced it. Adding an entry without a run is not allowed.
     */
    private val verifiedContainers: Set<String> = setOf(
        "debian:12",
        "ubuntu:24.04",
        "alpine:3.21",
        "fedora:42",
        "archlinux:latest",
        "opensuse/tumbleweed:latest",
    )

    fun tierFor(facts: HostFacts): SupportTier {
        if (facts.packageManager == null) return SupportTier.UNVERIFIED
        val key = "${facts.osId}:${facts.osVersionId}"
        if (key in verifiedContainers && !facts.containerized) {
            // A container image name is not proof for a real host of the same name.
            return SupportTier.UNVERIFIED
        }
        if (key in verifiedContainers) return SupportTier.VERIFIED_CONTAINER
        return SupportTier.UNVERIFIED
    }
}

// ---------------------------------------------------------------------------
// Planning
// ---------------------------------------------------------------------------

internal data class BootstrapRequest(
    val toolIds: List<String>,
    val profileId: String? = null,
    val acknowledgeUnverified: Boolean = false,
) {
    val describedLabel: String get() = profileId?.let { ProvisionCatalog.profile(it)?.label } ?: "Custom selection"
}

internal sealed class PlanAction {
    data class Install(
        val tool: CatalogTool,
        val manager: PackageManagerKind,
        val packages: List<String>,
    ) : PlanAction()

    data class AlreadyPresent(val tool: CatalogTool) : PlanAction()

    data class Unavailable(val toolId: String, val reason: String) : PlanAction()
}

internal data class PlanStep(val index: Int, val packages: List<String>, val toolIds: List<String>)

internal data class ProvisionPlan(
    val request: BootstrapRequest,
    val factsCapturedAtMillis: Long,
    val osId: String,
    val manager: PackageManagerKind?,
    val supportTier: SupportTier,
    val actions: List<PlanAction>,
    val steps: List<PlanStep>,
    val requiresAdmin: Boolean,
    val estimatedDownloadBytes: Long?,
    val warnings: List<String>,
    val stepsDigest: String,
) {
    val installActions: List<PlanAction.Install> get() = actions.filterIsInstance<PlanAction.Install>()

    val totalPackages: Int get() = installActions.sumOf { it.packages.size }

    val isNoOp: Boolean get() = installActions.isEmpty()
}

internal enum class PlanDecision {
    ALLOWED,
    ALLOWED_UNVERIFIED,
    NEEDS_INTERACTIVE_PRIVILEGE,
    BLOCKED_NO_MANAGER,
    BLOCKED_UNSUPPORTED_DISTRIBUTION,
    BLOCKED_NOTHING_TO_DO,
    BLOCKED_INSUFFICIENT_DISK,
}

internal data class PlanVerdict(val decision: PlanDecision, val explanation: String, val canLaunch: Boolean)

internal object ProvisionPlanner {
    const val MAX_TOOLS = 32
    const val MAX_PACKAGES = 64
    const val PACKAGES_PER_STEP = 16
    const val MIN_DISK_KB = 200 * 1024L

    fun plan(facts: HostFacts, request: BootstrapRequest): ProvisionPlan {
        val manager = facts.packageManager
        val requested = request.toolIds.distinct().take(MAX_TOOLS)
        val actions = mutableListOf<PlanAction>()
        val warnings = mutableListOf<String>()
        for (toolId in requested) {
            val tool = ProvisionCatalog.tool(toolId)
            if (tool == null) {
                actions += PlanAction.Unavailable(toolId, "This tool is not in the shipped catalogue.")
                continue
            }
            if (manager == null) {
                actions += PlanAction.Unavailable(toolId, "No supported native package manager was detected.")
                continue
            }
            val packages = tool.packagesFor(manager)
            if (packages.isEmpty()) {
                actions += PlanAction.Unavailable(
                    toolId,
                    "${tool.label} has no reviewed ${manager.display} package.",
                )
                continue
            }
            val installed = facts.tools.containsKey(tool.executable)
            if (installed) {
                actions += PlanAction.AlreadyPresent(tool)
            } else {
                actions += PlanAction.Install(tool, manager, packages)
            }
        }
        if (requested.size < request.toolIds.size) {
            warnings += "Only the first $MAX_TOOLS selections were planned."
        }

        val uniquePackages = LinkedHashMap<String, MutableList<String>>()
        for (action in actions.filterIsInstance<PlanAction.Install>()) {
            for (pkg in action.packages) {
                uniquePackages.getOrPut(pkg) { mutableListOf() } += action.tool.id
            }
        }
        if (uniquePackages.size > MAX_PACKAGES) {
            warnings += "The selection exceeds $MAX_PACKAGES packages and was truncated."
        }
        val packageList = uniquePackages.keys.take(MAX_PACKAGES).toList()
        val steps = packageList.chunked(PACKAGES_PER_STEP).mapIndexed { index, chunk ->
            val toolIds = chunk.flatMap { uniquePackages[it].orEmpty() }.distinct()
            PlanStep(index + 1, chunk, toolIds)
        }

        val tier = ProvisioningSupport.tierFor(facts)
        if (tier == SupportTier.UNVERIFIED) {
            warnings += "Guided provisioning is not verified for ${facts.displayName} ${facts.architecture}. " +
                "Use the terminal to install software, or continue only if you accept the risk."
        }
        if (facts.containerized) {
            warnings += "The host appears to be a container. Package changes affect that container only."
        }
        if (facts.isRoot) {
            warnings += "The SSH user is root. Every command runs as root; a non-root user is safer."
        }
        if (steps.isNotEmpty() && !facts.sudoNonInteractive && !facts.isRoot) {
            warnings += "Passwordless sudo was not detected. The job cannot prompt for a password."
        }

        val digest = digestOf(stepsFileBytes(steps))
        return ProvisionPlan(
            request = request.copy(toolIds = requested),
            factsCapturedAtMillis = facts.capturedAtMillis,
            osId = facts.osId,
            manager = manager,
            supportTier = tier,
            actions = actions,
            steps = steps,
            requiresAdmin = steps.isNotEmpty() && !facts.isRoot,
            estimatedDownloadBytes = null,
            warnings = warnings,
            stepsDigest = digest,
        )
    }

    fun decide(plan: ProvisionPlan, facts: HostFacts): PlanVerdict {
        if (plan.manager == null) {
            return PlanVerdict(
                PlanDecision.BLOCKED_NO_MANAGER,
                "No supported native package manager was detected on this host.",
                false,
            )
        }
        if (plan.isNoOp) {
            return PlanVerdict(
                PlanDecision.BLOCKED_NOTHING_TO_DO,
                if (plan.actions.any { it is PlanAction.Unavailable }) {
                    "Nothing in this selection can be installed on this host."
                } else {
                    "Every selected tool is already installed."
                },
                false,
            )
        }
        if (facts.diskAvailableKb in 1 until MIN_DISK_KB) {
            return PlanVerdict(
                PlanDecision.BLOCKED_INSUFFICIENT_DISK,
                "Only ${facts.diskAvailableKb / 1024} MB is free on the host; at least 200 MB is required.",
                false,
            )
        }
        if (plan.requiresAdmin && !facts.sudoNonInteractive && !facts.isRoot) {
            return PlanVerdict(
                PlanDecision.NEEDS_INTERACTIVE_PRIVILEGE,
                "Installing system packages needs administrator rights, and passwordless sudo is not " +
                    "available. Run the shown command in a terminal tab so you can authorize it yourself. " +
                    "Anthracite never stores or forwards a sudo password.",
                false,
            )
        }
        return if (plan.supportTier == SupportTier.UNVERIFIED && !plan.request.acknowledgeUnverified) {
            PlanVerdict(
                PlanDecision.ALLOWED_UNVERIFIED,
                "This distribution is not in the verified matrix. Review the exact commands, then confirm " +
                    "that you accept running them at your own risk.",
                true,
            )
        } else {
            PlanVerdict(PlanDecision.ALLOWED, "The plan is ready. Review the commands and authorize it.", true)
        }
    }

    /** Human-readable commands for review and for the manual fallback. Read-only. */
    fun previewCommands(plan: ProvisionPlan): List<String> {
        val manager = plan.manager ?: return emptyList()
        val prefix = if (plan.requiresAdmin) "sudo " else ""
        return plan.steps.map { step ->
            val packages = step.packages.joinToString(" ")
            when (manager) {
                PackageManagerKind.APT -> "$prefix${manager.id}-get install -y --no-install-recommends $packages"
                PackageManagerKind.DNF -> "$prefix${manager.id} install -y $packages"
                PackageManagerKind.ZYPPER ->
                    "$prefix${manager.id} --non-interactive install --no-recommends $packages"
                PackageManagerKind.PACMAN -> "$prefix${manager.id} -S --noconfirm --needed $packages"
                PackageManagerKind.APK -> "$prefix${manager.id} add --no-progress $packages"
            }
        }
    }

    fun stepsFileBytes(steps: List<PlanStep>): ByteArray {
        val builder = StringBuilder()
        for (step in steps) {
            builder.append(step.index).append('\t').append("install").append('\t')
                .append(step.packages.joinToString(" ")).append('\n')
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    fun digestOf(steps: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(steps)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** The file the host runner reads; the phone never uploads arbitrary shell text. */
    fun planFileBytes(plan: ProvisionPlan, jobId: String): ByteArray {
        require(jobId.matches(Regex("[a-f0-9]{16}"))) { "job id is invalid" }
        val manager = plan.manager?.id ?: ""
        return buildString {
            append("job_id=").append(jobId).append('\n')
            append("manager=").append(manager).append('\n')
            append("admin=").append(if (plan.requiresAdmin) "1" else "0").append('\n')
            append("os_id=").append(plan.osId).append('\n')
            append("catalog_version=").append(ProvisionCatalog.VERSION).append('\n')
            append("plan_hash=").append(plan.stepsDigest).append('\n')
        }.toByteArray(Charsets.UTF_8)
    }
}

// ---------------------------------------------------------------------------
// Job lifecycle
// ---------------------------------------------------------------------------

internal enum class JobState { PLANNED, APPROVED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, LOCKED, UNKNOWN }

internal data class JobStatus(
    val state: JobState,
    val step: String,
    val exitCode: Int,
    val processAlive: Boolean,
    val startTicksMatch: Boolean?,
    val pid: String?,
    val logTail: String,
    val logBytes: Long,
    val resultPresent: Boolean,
    val reason: String?,
    val rawLogTruncated: Boolean,
)

internal object ProvisionJobs {
    const val RUNNER_FILE = "anthracite-provision-run.sh"
    const val STATUS_FILE = "anthracite-provision-status.sh"
    const val JOB_ROOT = ".anthracite/jobs"
    const val LOG_TAIL_LIMIT = 3000

    fun newJobId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun jobDirectory(jobId: String): String {
        require(jobId.matches(Regex("[a-f0-9]{16}"))) { "job id is invalid" }
        return "$JOB_ROOT/$jobId"
    }

    fun mkdirCommand(jobId: String): String =
        "umask 077; mkdir -p \"\$HOME/${jobDirectory(jobId)}\""

    /**
     * Starts the uploaded runner detached from the SSH channel.
     *
     * `setsid` is preferred so the job is not in the SSH session's process group;
     * `nohup` is the fallback. Either way the runner ignores SIGHUP and keeps its
     * own state, so a dropped phone connection does not stop provisioning.
     */
    fun launchCommand(jobId: String, useSetsid: Boolean): String {
        val directory = jobDirectory(jobId)
        val invoke = if (useSetsid) {
            "setsid sh ./$RUNNER_FILE \"\$HOME/$directory\""
        } else {
            "nohup sh ./$RUNNER_FILE \"\$HOME/$directory\""
        }
        return "cd \"\$HOME/$directory\" && ( $invoke </dev/null >/dev/null 2>&1 & ) && echo launched"
    }

    fun statusCommand(jobId: String): String = "sh -s \"\$HOME/${jobDirectory(jobId)}\""

    fun cancelCommand(pid: String): String {
        require(pid.matches(Regex("[0-9]{1,10}"))) { "pid is invalid" }
        return "kill -TERM $pid"
    }

    fun parseStatus(stdout: String): JobStatus {
        val values = LinkedHashMap<String, String>()
        for (line in stdout.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        val state = when (values["status"]?.uppercase()) {
            "PLANNED" -> JobState.PLANNED
            "APPROVED" -> JobState.APPROVED
            "QUEUED" -> JobState.QUEUED
            "RUNNING" -> JobState.RUNNING
            "SUCCEEDED" -> JobState.SUCCEEDED
            "FAILED" -> JobState.FAILED
            "CANCELLED" -> JobState.CANCELLED
            "INTERRUPTED" -> JobState.INTERRUPTED
            "LOCKED" -> JobState.LOCKED
            else -> JobState.UNKNOWN
        }
        val alive = values["process_alive"] == "yes"
        val ticksMatch = when (values["start_ticks_match"]) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        val logTail = decodeLogTail(values["log_tail_b64"].orEmpty())
        val pid = values["pid"]?.takeIf { it.matches(Regex("[0-9]{1,10}")) }
        return JobStatus(
            state = reconcile(state, alive, ticksMatch),
            step = values["step"].orEmpty(),
            exitCode = values["exit_code"]?.toIntOrNull() ?: -1,
            processAlive = alive,
            startTicksMatch = ticksMatch,
            pid = pid,
            logTail = logTail.first,
            logBytes = values["log_bytes"]?.toLongOrNull() ?: 0L,
            resultPresent = values["result_present"] == "yes",
            reason = values["reason"],
            rawLogTruncated = logTail.second,
        )
    }

    /**
     * The host is authoritative. A recorded RUNNING state with no matching
     * process is an interruption, never a success and never a silent restart.
     */
    fun reconcile(recorded: JobState, alive: Boolean, ticksMatch: Boolean?): JobState = when {
        recorded == JobState.RUNNING && !alive -> JobState.INTERRUPTED
        recorded == JobState.RUNNING && ticksMatch == false -> JobState.INTERRUPTED
        else -> recorded
    }

    private fun decodeLogTail(encoded: String): Pair<String, Boolean> {
        if (encoded.isBlank()) return "" to false
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return "" to false
        val truncated = bytes.size > LOG_TAIL_LIMIT
        val text = String(bytes.copyOfRange(bytes.size - minOf(bytes.size, LOG_TAIL_LIMIT), bytes.size), Charsets.UTF_8)
        return text to truncated
    }
}

/**
 * Redacts values that must never reach a log view, a screenshot or a bug report.
 * The app never sends credentials to the host, so this is defence in depth for
 * host output that happens to echo a token or a path with a secret name.
 */
internal object LogRedactor {
    /** `name = value` shapes, where the name is worth keeping for the reader. */
    private val KEYED_SECRET = Regex("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*\\S+")

    /**
     * Tokens that carry no `name =` prefix. These must be replaced by the marker
     * alone: rebuilding them as `label=value` would print the credential itself,
     * because the whole match *is* the secret.
     */
    private val BARE_SECRETS = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
    )

    fun redact(text: String, extraSecrets: List<String> = emptyList()): String {
        var result = text
        for (secret in extraSecrets) {
            if (secret.length >= 4) result = result.replace(secret, REDACTED)
        }
        result = KEYED_SECRET.replace(result) { match ->
            val label = match.value.substringBefore('=').substringBefore(':').trim()
            "$label=$REDACTED"
        }
        for (pattern in BARE_SECRETS) {
            result = pattern.replace(result, REDACTED)
        }
        return result
    }

    const val REDACTED = "[redacted]"
}

/** Host-side provisioning operations over the existing SSH connection. */
internal class ProvisionClient(private val ssh: SshClient) {
    fun status(jobId: String, statusScript: String): JobStatus {
        val result = ssh.exec(ProvisionJobs.statusCommand(jobId), stdin = statusScript.toByteArray(Charsets.UTF_8))
        return ProvisionJobs.parseStatus(result.stdout)
    }

    fun uploadJob(jobId: String, plan: ProvisionPlan, runnerScript: String) {
        val directory = ProvisionJobs.jobDirectory(jobId)
        ssh.exec(ProvisionJobs.mkdirCommand(jobId))
        ssh.upload(runnerScript.toByteArray(Charsets.UTF_8), "$directory/${ProvisionJobs.RUNNER_FILE}")
        ssh.upload(ProvisionPlanner.stepsFileBytes(plan.steps), "$directory/steps")
        ssh.upload(ProvisionPlanner.planFileBytes(plan, jobId), "$directory/plan")
    }

    fun launch(jobId: String, useSetsid: Boolean) {
        val result = ssh.exec(ProvisionJobs.launchCommand(jobId, useSetsid))
        check(result.exitCode == 0) { "the host did not accept the job: ${result.text.take(200)}" }
    }

    /**
     * Requests cancellation only for a process whose recorded start time still
     * matches. A reused PID is never signalled.
     */
    fun cancel(jobId: String, statusScript: String): Boolean {
        val status = status(jobId, statusScript)
        val pid = status.pid ?: return false
        if (!status.processAlive || status.startTicksMatch == false) return false
        val result = ssh.exec(ProvisionJobs.cancelCommand(pid))
        return result.exitCode == 0
    }
}
