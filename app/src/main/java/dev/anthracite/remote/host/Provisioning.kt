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
 * Independent capabilities. Proving one of these never promotes another: a
 * successful read-only probe says nothing about package installation, and a
 * successful install says nothing about a supervisor, a logout or a reboot.
 */
internal enum class ProvisionCapability(val label: String) {
    SSH_PTY("SSH and interactive terminal"),
    OS_DISCOVERY("Read-only discovery"),
    PLAN_GENERATION("Package plan generation"),
    PACKAGE_INSTALL("Package installation"),
    PACKAGE_VERIFY("Post-install verification"),
    CANCELLATION("Cancellation and stop handling"),
    CLEANUP("Cleanup after a job"),
    SESSION_DURABILITY("Detached session durability"),
    REBOOT_DURABILITY("Survives a host reboot"),
    CONTAINER_RUNTIME("Container runtime control"),
    KUBERNETES("Kubernetes access"),
}

/**
 * Evidence for one *exact* image, VM or host.
 *
 * A capability is only listed here after it was exercised end to end, and the
 * record names the image digest (or host identity), the date and the GitHub
 * Actions run. Recording a distribution name alone is not evidence: the same
 * name can resolve to a different image tomorrow.
 */
internal data class DistributionEvidence(
    val osId: String,
    val osVersion: String,
    val arch: String,
    val image: String,
    val imageDigest: String,
    val testedAt: String,
    val runUrl: String,
    val capabilities: Set<ProvisionCapability>,
) {
    val installTested: Boolean
        get() = ProvisionCapability.PACKAGE_INSTALL in capabilities &&
            ProvisionCapability.PACKAGE_VERIFY in capabilities
}

/**
 * What can honestly be claimed for *this* host, and why.
 *
 * [tier] is a statement about the connected machine, not about an image with a
 * similar name. A container that matches a tested image can only ever reach
 * [SupportTier.TESTED_IN_IMAGE]; that still requires the user's acknowledgement
 * before a package mutation, because the host's own supervisor, logout and
 * reboot behaviour were never exercised.
 */
internal data class CapabilityAssessment(
    val tier: SupportTier,
    val capabilities: Set<ProvisionCapability>,
    val evidence: DistributionEvidence?,
    val detail: String,
)

/** Verified support tiers. Only [VERIFIED_HOST] skips the acknowledgement. */
internal enum class SupportTier(val label: String) {
    /** This exact host completed install, verify, cancel and cleanup. */
    VERIFIED_HOST("Verified on this host"),

    /**
     * A container image of the same distribution, release and architecture
     * completed install and verify. Supervisor/logout/reboot remain untested.
     */
    TESTED_IN_IMAGE("Installation tested in a container image, not on this host"),

    UNVERIFIED("Not verified for this distribution"),
}

internal object ProvisioningSupport {
    /**
     * Populated only from a real matrix run. Empty is the truthful default:
     * read-only discovery does not prove that a package can be installed.
     */
    val evidence: List<DistributionEvidence> = emptyList()

    /** Evidence keyed to a specific machine (VM or host), never to a distro name. */
    val hostEvidence: Map<String, Set<ProvisionCapability>> = emptyMap()

    fun assessmentFor(
        facts: HostFacts,
        records: List<DistributionEvidence> = evidence,
        hosts: Map<String, Set<ProvisionCapability>> = hostEvidence,
    ): CapabilityAssessment {
        if (facts.packageManager == null) {
            return CapabilityAssessment(
                SupportTier.UNVERIFIED,
                emptySet(),
                null,
                "No supported native package manager was detected on this host.",
            )
        }
        // Only a host identity that was itself tested may skip the acknowledgement.
        val hostCapabilities = hosts[facts.machineIdentity].orEmpty()
        if (ProvisionCapability.PACKAGE_INSTALL in hostCapabilities &&
            ProvisionCapability.PACKAGE_VERIFY in hostCapabilities
        ) {
            return CapabilityAssessment(
                SupportTier.VERIFIED_HOST,
                hostCapabilities,
                null,
                "Guided provisioning was verified on this host.",
            )
        }
        val match = records.firstOrNull { record ->
            record.osId == facts.osId &&
                record.osVersion == facts.osVersionId &&
                record.arch == facts.architecture &&
                record.installTested
        } ?: return CapabilityAssessment(
            SupportTier.UNVERIFIED,
            emptySet(),
            null,
            "No package-installation evidence exists for ${facts.displayName} " +
                "${facts.architecture}: discovery alone does not certify provisioning.",
        )
        val provenance = "${match.image} (digest ${match.imageDigest.take(19)}…), " +
            "tested ${match.testedAt}, ${match.runUrl}"
        return if (facts.containerized) {
            CapabilityAssessment(
                SupportTier.TESTED_IN_IMAGE,
                match.capabilities,
                match,
                "Package installation was tested in $provenance. The container image, not this " +
                    "host's supervisor, logout or reboot behaviour, is what that run covered.",
            )
        } else {
            CapabilityAssessment(
                SupportTier.UNVERIFIED,
                match.capabilities,
                match,
                "Package names were tested in $provenance, not on this host. A real host of the " +
                    "same distribution still has its own package availability, supervisor, logout " +
                    "and reboot behaviour.",
            )
        }
    }

    fun tierFor(facts: HostFacts): SupportTier = assessmentFor(facts).tier
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
    val supportDetail: String,
    val testedCapabilities: Set<ProvisionCapability>,
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

    fun plan(
        facts: HostFacts,
        request: BootstrapRequest,
        evidence: List<DistributionEvidence> = ProvisioningSupport.evidence,
        hostEvidence: Map<String, Set<ProvisionCapability>> = ProvisioningSupport.hostEvidence,
    ): ProvisionPlan {
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

        val assessment = ProvisioningSupport.assessmentFor(facts, evidence, hostEvidence)
        val tier = assessment.tier
        if (tier != SupportTier.VERIFIED_HOST) {
            warnings += assessment.detail
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
            supportDetail = assessment.detail,
            testedCapabilities = assessment.capabilities,
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
        // Only a run that this exact host completed may skip the acknowledgement.
        // Image evidence never does: a container test proves package names, not
        // the supervisor, logout or reboot behaviour of the connected machine.
        return if (plan.supportTier != SupportTier.VERIFIED_HOST && !plan.request.acknowledgeUnverified) {
            val why = plan.supportDetail.ifBlank {
                "This distribution is not in the verified matrix; read-only discovery does not certify " +
                    "package installation."
            }
            PlanVerdict(
                PlanDecision.ALLOWED_UNVERIFIED,
                "$why Review the exact commands, then confirm that you accept running them at your own risk.",
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
    /** `null` when the host did not report; never guessed from a PID alone. */
    val identityVerified: Boolean?,
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
    const val CANCEL_FILE = "anthracite-provision-cancel.sh"
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

    /**
     * Runs the reviewed compare-and-signal script on the host.
     *
     * The phone never selects a PID and never sends a signal itself: the host kit
     * re-verifies the recorded process identity immediately before signalling and
     * refuses when it cannot prove ownership.
     */
    fun cancelScriptCommand(jobId: String): String = "sh -s \"\$HOME/${jobDirectory(jobId)}\""

    private fun fields(stdout: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (line in stdout.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        return values
    }

    fun parseStatus(stdout: String): JobStatus {
        val values = fields(stdout)
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
        val identityVerified = when (values["identity"]) {
            "verified" -> true
            "unverified" -> false
            else -> null
        }
        val logTail = decodeLogTail(values["log_tail_b64"].orEmpty())
        val pid = values["pid"]?.takeIf { it.matches(Regex("[0-9]{1,10}")) }
        return JobStatus(
            state = reconcile(state, alive, ticksMatch, identityVerified),
            step = values["step"].orEmpty(),
            exitCode = values["exit_code"]?.toIntOrNull() ?: -1,
            processAlive = alive,
            startTicksMatch = ticksMatch,
            identityVerified = identityVerified,
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
    fun reconcile(
        recorded: JobState,
        alive: Boolean,
        ticksMatch: Boolean?,
        identityVerified: Boolean? = null,
    ): JobState = when {
        recorded == JobState.RUNNING && !alive -> JobState.INTERRUPTED
        recorded == JobState.RUNNING && ticksMatch == false -> JobState.INTERRUPTED
        // A live PID whose command line is not this job is not our job. The state
        // is unknown, not running and not successful.
        recorded == JobState.RUNNING && identityVerified == false -> JobState.UNKNOWN
        else -> recorded
    }

    /**
     * Parses the host-side compare-and-signal result. A signal that was merely
     * requested is never reported as a completed stop.
     */
    fun parseCancel(stdout: String): CancelResult {
        val values = fields(stdout)
        val outcome = when (values["cancel_result"]) {
            "refused_invalid_job" -> CancelOutcome.REFUSED_INVALID_JOB
            "refused_unknown_identity" -> CancelOutcome.REFUSED_UNKNOWN_IDENTITY
            "refused_not_owner" -> CancelOutcome.REFUSED_NOT_OWNER
            "already_finished" -> CancelOutcome.ALREADY_FINISHED
            "signal_sent" -> CancelOutcome.SIGNAL_SENT
            "still_running" -> CancelOutcome.STILL_RUNNING
            "confirmed_stopped" -> CancelOutcome.CONFIRMED_STOPPED
            "signal_failed" -> CancelOutcome.SIGNAL_FAILED
            else -> CancelOutcome.UNKNOWN
        }
        return CancelResult(
            outcome = outcome,
            reason = values["reason"],
            state = values["state"],
            pid = values["pid"]?.takeIf { it.matches(Regex("[0-9]{1,10}")) },
            processAlive = when (values["process_alive"]) {
                "yes" -> true
                "no" -> false
                else -> null
            },
            verification = values["verification"]?.takeIf { it != "none" },
        )
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
 * What the host reported after a compare-and-signal request.
 *
 * [SIGNAL_SENT] and [STILL_RUNNING] mean "the verified runner was asked to stop
 * and is still alive". Only [CONFIRMED_STOPPED] and [ALREADY_FINISHED] mean the
 * process is gone; a successful `kill` syscall is never treated as termination.
 */
internal enum class CancelOutcome {
    REFUSED_INVALID_JOB,
    REFUSED_UNKNOWN_IDENTITY,
    REFUSED_NOT_OWNER,
    ALREADY_FINISHED,
    SIGNAL_SENT,
    STILL_RUNNING,
    CONFIRMED_STOPPED,
    SIGNAL_FAILED,
    UNKNOWN;

    val signalWasSent: Boolean
        get() = this == SIGNAL_SENT || this == STILL_RUNNING

    val stopped: Boolean
        get() = this == CONFIRMED_STOPPED || this == ALREADY_FINISHED
}

internal data class CancelResult(
    val outcome: CancelOutcome,
    val reason: String?,
    val state: String?,
    val pid: String?,
    val processAlive: Boolean?,
    /** Non-null only when the host proved pid, start time, owner and cmdline. */
    val verification: String?,
) {
    val identityProven: Boolean get() = verification != null

    /** A signal was delivered to a verified runner; it may still be alive. */
    val signalWasSent: Boolean get() = outcome.signalWasSent

    /** The host observed the process exit; not merely "a kill call returned 0". */
    val stopped: Boolean get() = outcome.stopped
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
     * Asks the host to stop a job. The identity check, the decision and the
     * signal all happen on the host in one reviewed script, so a reused PID or a
     * missing start time can never be signalled by a stale phone observation.
     */
    fun cancel(jobId: String, cancelScript: String): CancelResult {
        val result = ssh.exec(
            ProvisionJobs.cancelScriptCommand(jobId),
            stdin = cancelScript.toByteArray(Charsets.UTF_8),
        )
        return ProvisionJobs.parseCancel(result.stdout)
    }
}
