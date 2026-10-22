package dev.anthracite.remote.host

/**
 * What the read-only probe observed. Every field is a fact from the host or a
 * clearly-derived interpretation; nothing here is a capability claim.
 */
internal data class HostFacts(
    val capturedAtMillis: Long,
    val complete: Boolean,
    val osId: String,
    val osVersionId: String,
    val osIdLike: List<String>,
    val osPretty: String,
    val kernel: String,
    val arch: String,
    val kernelRelease: String,
    val uid: Int,
    val user: String,
    val primaryGroup: String,
    val groups: List<String>,
    val home: String,
    val shell: String,
    val workingDirectory: String,
    val cpuCount: Int,
    val memoryTotalKb: Long,
    val diskAvailableKb: Long,
    val filesystem: String,
    val sudoInstalled: Boolean,
    val sudoNonInteractive: Boolean,
    val systemctlPresent: Boolean,
    val loginctlPresent: Boolean,
    val linger: Boolean?,
    val containerDocker: Boolean,
    val containerHint: String,
    val pid1: String,
    val tools: Map<String, String>,
    val kubeContexts: List<String>,
    val kubeCurrentContext: String?,
    val raw: Map<String, String>,
) {
    val displayName: String
        get() = osPretty.ifBlank { listOf(osId, osVersionId).filter { it.isNotBlank() }.joinToString(" ") }
            .ifBlank { "Unknown Linux" }

    val architecture: String get() = arch.ifBlank { "unknown" }

    val isRoot: Boolean get() = uid == 0

    val family: EnvironmentFamily get() = EnvironmentFamily.forOsId(osId, osIdLike)

    /** The package manager that is actually present, not one implied by the OS name. */
    val packageManager: PackageManagerKind? get() = PackageManagerKind.detect(tools.keys)

    val hasTmux: Boolean get() = tools.containsKey("tmux")

    val hasGit: Boolean get() = tools.containsKey("git")

    val hasSudo: Boolean get() = sudoInstalled

    /** A container cannot prove supervisor, linger or reboot behaviour. */
    val containerized: Boolean
        get() = containerDocker || containerHint.isNotBlank() || pid1.equals("tini", ignoreCase = true)

    val supervision: Supervision
        get() = when {
            !systemctlPresent -> if (pid1.isNotBlank()) Supervision.NON_SYSTEMD else Supervision.UNKNOWN
            pid1.equals("systemd", ignoreCase = true) && !containerized -> Supervision.SYSTEMD
            pid1.equals("systemd", ignoreCase = true) -> Supervision.SYSTEMD_IN_CONTAINER
            else -> Supervision.NON_SYSTEMD
        }

    /**
     * Durability is a set of separate, honestly-labelled capabilities:
     * a dropped channel, an interactive logout and a host reboot are different
     * events with different host policy behind them.
     */
    fun durability(): DurabilityAssessment {
        if (!hasTmux) {
            return DurabilityAssessment(
                disconnected = CapabilityState.UNSUPPORTED,
                logout = CapabilityState.UNSUPPORTED,
                reboot = CapabilityState.UNSUPPORTED,
                detail = "tmux is not installed. Terminal tabs work, but nothing can outlive the SSH channel.",
            )
        }
        // Logout survival is never claimed, whatever the host reports: linger
        // only permits a user manager to keep running, and a host may still end
        // detached processes (KillUserProcesses) on logout. Nothing observed from
        // inside the session can prove otherwise, so the state is UNKNOWN and the
        // detail below explains which specific fact is missing.
        val logout = CapabilityState.UNKNOWN
        val logoutDetail = when {
            containerized -> "This session runs in a container; host logout policy cannot be observed from it."
            supervision != Supervision.SYSTEMD ->
                "This host does not use systemd user sessions; logout survival depends on the local supervisor and is untested."
            linger == true ->
                "Linger is enabled for this user, but KillUserProcesses can still end detached programs. Verify with a real logout."
            linger == false ->
                "Linger is not enabled for this user, so a logout will normally end detached sessions."
            else -> "Linger state is unknown, so logout survival cannot be predicted. Verify with a real logout."
        }
        return DurabilityAssessment(
            disconnected = CapabilityState.AVAILABLE,
            logout = logout,
            reboot = CapabilityState.UNSUPPORTED,
            detail = "A detached tmux session survives a dropped SSH channel. $logoutDetail " +
                "No process survives a host reboot, and no CLI's internal conversation state is restorable by Anthracite.",
        )
    }

    fun capabilities(): HostCapabilities = HostCapabilities(
        terminal = if (kernel.equals("Linux", ignoreCase = true)) CapabilityState.AVAILABLE else CapabilityState.UNSUPPORTED,
        files = CapabilityState.AVAILABLE,
        hostGit = if (hasGit) CapabilityState.AVAILABLE else CapabilityState.UNSUPPORTED,
        provisioning = if (packageManager == null) CapabilityState.UNSUPPORTED else CapabilityState.AVAILABLE,
        docker = runtimeState("docker"),
        podman = runtimeState("podman"),
        kubernetes = when {
            !tools.containsKey("kubectl") -> CapabilityState.UNSUPPORTED
            kubeCurrentContext.isNullOrBlank() -> CapabilityState.DETECTED_UNUSABLE
            else -> CapabilityState.AVAILABLE
        },
        durability = durability(),
        detail = buildList {
            if (packageManager == null) add("No supported native package manager was detected.")
            if (!hasGit) add("git is not installed, so host Git features are unavailable.")
            if (!hasTmux) add("tmux is not installed, so detached sessions are unavailable.")
            if (tools.containsKey("docker")) {
                add("Docker access can be root-equivalent; check group membership before running containers.")
            }
        },
    )

    private fun runtimeState(binary: String): CapabilityState = when {
        !tools.containsKey(binary) -> CapabilityState.UNSUPPORTED
        // A binary on PATH is not proof the daemon, socket or credentials work.
        else -> CapabilityState.DETECTED_UNUSABLE
    }
}

internal data class DurabilityAssessment(
    val disconnected: CapabilityState,
    val logout: CapabilityState,
    val reboot: CapabilityState,
    val detail: String,
)

internal data class HostCapabilities(
    val terminal: CapabilityState,
    val files: CapabilityState,
    val hostGit: CapabilityState,
    val provisioning: CapabilityState,
    val docker: CapabilityState,
    val podman: CapabilityState,
    val kubernetes: CapabilityState,
    val durability: DurabilityAssessment,
    val detail: List<String>,
)

/** Orthogonal capability states; never collapsed into one "supported" flag. */
internal enum class CapabilityState { AVAILABLE, DETECTED_UNUSABLE, UNSUPPORTED, UNKNOWN }

internal enum class Supervision { SYSTEMD, SYSTEMD_IN_CONTAINER, NON_SYSTEMD, UNKNOWN }

internal enum class EnvironmentFamily(val label: String) {
    DEBIAN("Debian/Ubuntu"),
    FEDORA("Fedora/RHEL"),
    ARCH("Arch"),
    SUSE("openSUSE/SLE"),
    ALPINE("Alpine"),
    UNKNOWN("Other Linux");

    companion object {
        fun forOsId(osId: String, idLike: List<String>): EnvironmentFamily {
            val candidates = (listOf(osId) + idLike).map { it.lowercase() }
            for (candidate in candidates) {
                when (candidate) {
                    "debian", "ubuntu", "linuxmint", "pop", "raspbian", "kali" -> return DEBIAN
                    "fedora", "rhel", "centos", "rocky", "alma", "ol", "amzn" -> return FEDORA
                    "arch", "manjaro", "endeavouros", "cachyos" -> return ARCH
                    "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles", "suse" -> return SUSE
                    "alpine", "postmarketos" -> return ALPINE
                }
            }
            return UNKNOWN
        }
    }
}

internal enum class PackageManagerKind(val id: String, val display: String, val family: EnvironmentFamily) {
    APT("apt", "APT (apt-get)", EnvironmentFamily.DEBIAN),
    DNF("dnf", "DNF", EnvironmentFamily.FEDORA),
    ZYPPER("zypper", "Zypper", EnvironmentFamily.SUSE),
    PACMAN("pacman", "pacman", EnvironmentFamily.ARCH),
    APK("apk", "apk", EnvironmentFamily.ALPINE);

    companion object {
        /** Detected from actual binaries, in a fixed order of precedence. */
        fun detect(presentTools: Set<String>): PackageManagerKind? = when {
            presentTools.contains("apt-get") -> APT
            presentTools.contains("dnf") -> DNF
            presentTools.contains("zypper") -> ZYPPER
            presentTools.contains("pacman") -> PACMAN
            presentTools.contains("apk") -> APK
            else -> null
        }
    }
}

/** Parses the `key=value` output of `anthracite-probe.sh`. */
internal object HostFactsParser {
    fun parse(stdout: String, capturedAtMillis: Long): HostFacts {
        val values = LinkedHashMap<String, String>()
        for (line in stdout.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            if (!KEY_PATTERN.matches(key)) continue
            values[key] = line.substring(separator + 1).trim()
        }
        val tools = values.filterKeys { it.startsWith("tool.") }
            .mapKeys { it.key.removePrefix("tool.") }
        fun text(key: String): String = values[key].orEmpty()
        fun long(key: String, fallback: Long = 0L): Long = values[key]?.toLongOrNull() ?: fallback
        fun int(key: String, fallback: Int = 0): Int = values[key]?.toIntOrNull() ?: fallback
        fun bool(key: String): Boolean = values[key].equals("yes", ignoreCase = true)
        return HostFacts(
            capturedAtMillis = capturedAtMillis,
            complete = values["done"] == "1",
            osId = text("os_id"),
            osVersionId = text("os_version_id"),
            osIdLike = text("os_id_like").split(' ').filter { it.isNotBlank() },
            osPretty = text("os_pretty"),
            kernel = text("kernel"),
            arch = text("arch"),
            kernelRelease = text("kernel_release"),
            uid = int("uid", -1),
            user = text("user"),
            primaryGroup = text("group"),
            groups = text("groups").split(' ').filter { it.isNotBlank() },
            home = text("home"),
            shell = text("shell"),
            workingDirectory = text("pwd"),
            cpuCount = int("cpu_count", 0),
            memoryTotalKb = long("mem_total_kb"),
            diskAvailableKb = long("disk_avail_kb"),
            filesystem = text("filesystem"),
            sudoInstalled = bool("sudo_present"),
            sudoNonInteractive = bool("sudo_noninteractive"),
            systemctlPresent = bool("systemctl_present"),
            loginctlPresent = bool("loginctl_present"),
            linger = when (values["linger"]?.lowercase()) {
                "yes" -> true
                "no" -> false
                else -> null
            },
            containerDocker = bool("container_docker"),
            containerHint = text("container_hint"),
            pid1 = text("pid1"),
            tools = tools,
            kubeContexts = text("kube_contexts").split(',').filter { it.isNotBlank() },
            kubeCurrentContext = text("kube_current").ifBlank { null },
            raw = values,
        )
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
}
