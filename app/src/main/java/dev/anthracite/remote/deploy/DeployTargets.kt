package dev.anthracite.remote.deploy

import dev.anthracite.remote.ExecResult
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.json.MiniJson

/**
 * Native, Docker, Podman and Kubernetes controls built on the host's own CLIs.
 *
 * Rules enforced here:
 * * no implicit `--privileged`, no socket forwarding, no container creation;
 * * every mutation names one target that the user selected, and destructive
 *   actions are only offered for an identifier the host just reported;
 * * Kubernetes always names the confirmed context and namespace, and checks RBAC
 *   (`kubectl auth can-i`) before offering a delete;
 * * output that is a stream (logs) is bounded, and structured output is parsed
 *   from an explicit JSON format rather than localized text.
 */
internal data class ContainerSummary(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: String,
) {
    val shortId: String get() = id.take(12)
    val running: Boolean get() = state.equals("running", ignoreCase = true)
}

internal data class KubeContextInfo(val name: String, val current: Boolean)

/** What a creation produced: the last command result and the identity, if confirmed. */
internal data class DeployOutcome(val result: ExecResult, val appliedUid: String? = null)

/** The outcome of a guard check, with the text a screen may show verbatim. */
internal data class WorkloadPreflight(
    val verdict: WorkloadMutationVerdict,
    val detail: String,
    val identity: WorkloadIdentity?,
) {
    val allowed: Boolean get() = verdict == WorkloadMutationVerdict.ALLOWED
}

internal data class KubePod(
    val name: String,
    val namespace: String,
    val phase: String,
    val readyContainers: Int,
    val totalContainers: Int,
    val restarts: Int,
    val node: String?,
) {
    val ready: Boolean get() = totalContainers > 0 && readyContainers == totalContainers
}

internal object DeployCommands {
    fun detectTools(): String =
        "for t in docker podman kubectl systemctl; do " +
            "p=$(command -v \"\$t\" 2>/dev/null) && " +
            "printf 'tool.%s=%s\\n' \"\$t\" \"\$p\"; done"

    fun dockerPs(): String = "docker ps -a --no-trunc --format '{{json .}}'"

    fun podmanPs(): String = "podman ps -a --no-trunc --format json"

    fun dockerLogs(id: String, lines: Int = 200): String {
        requireContainerId(id)
        return "docker logs --tail ${lines.coerceIn(1, 1000)} ${PosixShell.quote(id)} 2>&1"
    }

    fun podmanLogs(id: String, lines: Int = 200): String {
        requireContainerId(id)
        return "podman logs --tail ${lines.coerceIn(1, 1000)} ${PosixShell.quote(id)} 2>&1"
    }

    fun dockerAction(id: String, action: Action, runtime: Runtime): String {
        requireContainerId(id)
        val binary = if (runtime == Runtime.PODMAN) "podman" else "docker"
        return "$binary ${action.command} ${PosixShell.quote(id)}"
    }

    fun dockerCanAccess(runtime: Runtime): String {
        val binary = if (runtime == Runtime.PODMAN) "podman" else "docker"
        return "$binary info --format '{{.ServerVersion}}' 2>/dev/null || true"
    }

    fun kubeContexts(): String = "kubectl config get-contexts -o name 2>/dev/null"

    fun kubeCurrentContext(): String = "kubectl config current-context 2>/dev/null"

    fun kubeAuthCanDeletePods(context: String, namespace: String): String =
        "kubectl --context ${quoteContext(context)} --namespace ${quoteNamespace(namespace)} " +
            "auth can-i delete pods 2>/dev/null"

    fun kubePods(context: String, namespace: String): String =
        "kubectl --context ${quoteContext(context)} --namespace ${quoteNamespace(namespace)} get pods -o json"

    fun kubeLogs(context: String, namespace: String, pod: String, lines: Int = 200): String {
        requireKubeName(pod)
        return "kubectl --context ${quoteContext(context)} --namespace ${quoteNamespace(namespace)} " +
            "logs --tail=${lines.coerceIn(1, 1000)} ${PosixShell.quote(pod)} 2>&1"
    }

    fun kubeDescribePod(context: String, namespace: String, pod: String): String {
        requireKubeName(pod)
        return "kubectl --context ${quoteContext(context)} --namespace ${quoteNamespace(namespace)} " +
            "describe pod ${PosixShell.quote(pod)} 2>&1"
    }

    /** Deletes exactly one pod, which the controller may recreate — stated in the UI. */
    fun kubeDeletePod(context: String, namespace: String, pod: String): String {
        requireKubeName(pod)
        return "kubectl --context ${quoteContext(context)} --namespace ${quoteNamespace(namespace)} " +
            "delete pod ${PosixShell.quote(pod)} --wait=false"
    }

    private fun quoteContext(context: String): String {
        require(isKubeContext(context)) { "invalid Kubernetes context" }
        return PosixShell.quote(context)
    }

    private fun quoteNamespace(namespace: String): String {
        require(PosixShell.isKubernetesName(namespace)) { "invalid namespace" }
        return PosixShell.quote(namespace)
    }

    private fun requireKubeName(name: String) = require(PosixShell.isKubernetesName(name)) { "invalid resource name" }

    private fun requireContainerId(id: String) {
        require(PosixShell.isContainerId(id)) { "invalid container id" }
    }

    fun isKubeContext(context: String): Boolean =
        context.isNotEmpty() && context.length <= 200 && PosixShell.isContainerId(context)

    enum class Runtime(val label: String, val warning: String) {
        DOCKER(
            "Docker",
            "Access to the Docker socket is equivalent to root on this host. Every command runs with that authority.",
        ),
        PODMAN(
            "Podman",
            "Rootless Podman runs as your user; a rootful daemon has the same authority as Docker.",
        ),
    }

    enum class Action(val command: String, val label: String, val destructive: Boolean) {
        START("start", "Start", false),
        STOP("stop", "Stop", false),
        RESTART("restart", "Restart", false),
    }
}

/** Docker emits one JSON object per line with `--format '{{json .}}'`. */
internal fun parseDockerPs(stdout: String): Pair<List<ContainerSummary>, Int> {
    val containers = mutableListOf<ContainerSummary>()
    var unparsed = 0
    for (line in stdout.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
        val json = runCatching { MiniJson.parse(trimmed).asObject() }.getOrNull()
        if (json == null) {
            unparsed++
            continue
        }
        val id = json.string("ID") ?: json.string("Id") ?: run {
            unparsed++
            ""
        }
        if (id.isEmpty()) continue
        containers += ContainerSummary(
            id = id,
            name = json.string("Names") ?: json.string("Name") ?: id.take(12),
            image = json.string("Image") ?: "unknown",
            state = json.string("State") ?: "",
            status = json.string("Status") ?: "",
            ports = json.string("Ports") ?: "",
        )
    }
    return containers to unparsed
}

/** `podman ps --format json` returns one array document. */
internal fun parsePodmanPs(stdout: String): Pair<List<ContainerSummary>, Int> {
    val root = runCatching { MiniJson.parse(stdout) }.getOrNull() ?: return emptyList<ContainerSummary>() to 1
    val array = root.asArray() ?: return emptyList<ContainerSummary>() to 1
    val containers = mutableListOf<ContainerSummary>()
    var unparsed = 0
    for (item in array.items) {
        val json = item.asObject()
        if (json == null) {
            unparsed++
            continue
        }
        val id = json.string("Id") ?: json.string("ID")
        if (id.isNullOrBlank()) {
            unparsed++
            continue
        }
        val names = json.array("Names")?.items?.mapNotNull { it.asString() }?.joinToString(",")
        containers += ContainerSummary(
            id = id,
            name = names?.ifBlank { null } ?: id.take(12),
            image = json.string("Image") ?: "unknown",
            state = json.string("State") ?: "",
            status = json.string("Status") ?: "",
            ports = json.array("Ports")?.items?.size?.toString().orEmpty(),
        )
    }
    return containers to unparsed
}

/** Parses `kubectl get pods -o json`. */
internal fun parseKubePods(stdout: String): Pair<List<KubePod>, String?> {
    val root = runCatching { MiniJson.parse(stdout) }.getOrNull() ?: return emptyList<KubePod>() to "unreadable JSON"
    // `obj("items")` returns the nested object; the list itself is an array, so
    // indexing it as one reported every healthy cluster as "no items".
    val items = root.asObject()?.array("items")?.items
        ?: return emptyList<KubePod>() to "no items in response"
    val pods = items.mapNotNull { item ->
        val pod = item.asObject() ?: return@mapNotNull null
        val metadata = pod.obj("metadata") ?: return@mapNotNull null
        val name = metadata.string("name") ?: return@mapNotNull null
        val namespace = metadata.string("namespace") ?: "default"
        val status = pod.obj("status")
        val containers = pod.obj("spec")?.array("containers")?.items?.size ?: 0
        val statuses = status?.array("containerStatuses")?.items.orEmpty()
        val ready = statuses.count { entry -> entry.asObject()?.bool("ready") == true }
        val restarts = statuses.sumOf { entry ->
            entry.asObject()?.int("restartCount") ?: 0
        }
        KubePod(
            name = name,
            namespace = namespace,
            phase = status?.string("phase") ?: "Unknown",
            readyContainers = ready,
            totalContainers = containers,
            restarts = restarts,
            node = pod.obj("spec")?.string("nodeName"),
        )
    }
    return pods to null
}

internal class DeployClient(private val ssh: SshClient) {
    fun dockerAvailable(runtime: DeployCommands.Runtime): Boolean {
        val result = ssh.exec(DeployCommands.dockerCanAccess(runtime), timeoutMs = 20_000)
        return result.stdout.isNotBlank()
    }

    fun containers(runtime: DeployCommands.Runtime): Pair<List<ContainerSummary>, Int> {
        val command = if (runtime == DeployCommands.Runtime.PODMAN) DeployCommands.podmanPs() else DeployCommands.dockerPs()
        val result = ssh.exec(command, timeoutMs = 30_000)
        check(result.exitCode == 0) { "The runtime did not list containers: ${result.text.take(200)}" }
        return if (runtime == DeployCommands.Runtime.PODMAN) {
            parsePodmanPs(result.stdout)
        } else {
            parseDockerPs(result.stdout)
        }
    }

    fun logs(runtime: DeployCommands.Runtime, id: String, lines: Int = 200): String {
        val command = if (runtime == DeployCommands.Runtime.PODMAN) {
            DeployCommands.podmanLogs(id, lines)
        } else {
            DeployCommands.dockerLogs(id, lines)
        }
        return ssh.exec(command, timeoutMs = 30_000).text
    }

    fun action(runtime: DeployCommands.Runtime, id: String, action: DeployCommands.Action): ExecResult =
        ssh.exec(DeployCommands.dockerAction(id, action, runtime), timeoutMs = 60_000)

    fun kubeContexts(): Pair<List<String>, String?> {
        val contexts = ssh.exec(DeployCommands.kubeContexts(), timeoutMs = 20_000).stdout
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val current = ssh.exec(DeployCommands.kubeCurrentContext(), timeoutMs = 20_000).stdout.trim()
        return contexts to current.ifBlank { null }
    }

    fun canDeletePods(context: String, namespace: String): Boolean? {
        val result = ssh.exec(DeployCommands.kubeAuthCanDeletePods(context, namespace), timeoutMs = 20_000)
        return when (result.stdout.trim().lowercase()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    }

    fun pods(context: String, namespace: String): Pair<List<KubePod>, String?> {
        val result = ssh.exec(DeployCommands.kubePods(context, namespace), timeoutMs = 30_000)
        check(result.exitCode == 0) { "kubectl could not list pods: ${result.text.take(200)}" }
        return parseKubePods(result.stdout)
    }

    fun podLogs(context: String, namespace: String, pod: String): String =
        ssh.exec(DeployCommands.kubeLogs(context, namespace, pod), timeoutMs = 30_000).text

    fun describePod(context: String, namespace: String, pod: String): String =
        ssh.exec(DeployCommands.kubeDescribePod(context, namespace, pod), timeoutMs = 30_000).text

    fun deletePod(context: String, namespace: String, pod: String): ExecResult =
        ssh.exec(DeployCommands.kubeDeletePod(context, namespace, pod), timeoutMs = 60_000)
}

/** The only host capability a deployment needs, so the guards are testable. */
internal fun interface RemoteShell {
    fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult
}

internal class SshShell(private val ssh: SshClient) : RemoteShell {
    override fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult =
        ssh.exec(command, stdin, timeoutMs)
}

/**
 * Creating and controlling deployments the user approved.
 *
 * Every mutation goes through a [DeployPlan], which has already validated every
 * value. Deleting a container is only offered after the host reports the
 * `anthracite.managed=1` label on that exact name. A Kubernetes mutation re-reads
 * the active context, checks RBAC for the exact verb and re-reads the live
 * object's UID immediately before it runs.
 */
internal class DeployCreator(private val shell: RemoteShell) {
    fun nativeStart(plan: DeployPlan.Native): ExecResult =
        shell.exec(plan.commands().single(), null, 30_000)

    fun nativeStatus(plan: DeployPlan.Native): String =
        shell.exec(plan.statusCommand(), null, 20_000).stdout.trim()

    fun nativeLogs(plan: DeployPlan.Native): String =
        shell.exec(plan.logsCommand(), null, 30_000).text

    fun nativeStop(plan: DeployPlan.Native): ExecResult =
        shell.exec(plan.stopCommand(), null, 30_000)

    fun containerPull(plan: DeployPlan.Container): ExecResult =
        shell.exec(plan.commands().first(), null, 600_000)

    /**
     * Writes the environment file (mode 0600) through stdin, starts the container
     * from it and removes the file afterwards.
     *
     * No environment value is ever an argument: values cannot reach `ps`, this
     * app's review screen, or the host's shell history. The cost is that anyone
     * with the runtime socket can still read them with `inspect`, which the plan's
     * warnings state.
     */
    fun containerRun(plan: DeployPlan.Container): ExecResult {
        if (plan.environment.isEmpty()) return shell.exec(plan.runCommand(), null, 180_000)
        val written = shell.exec(plan.envFileCommand(), plan.envFileBytes(), 30_000)
        if (written.exitCode != 0) return written
        return try {
            shell.exec(plan.runCommand(), null, 180_000)
        } finally {
            shell.exec(plan.envFileRemoveCommand(), null, 20_000)
        }
    }

    /** Reads the ownership label; null means the container does not carry it. */
    fun containerLabel(runtime: DeployCommands.Runtime, name: String): String? {
        require(DeployPlans.isManagedName(name)) { "invalid container name" }
        val binary = if (runtime == DeployCommands.Runtime.PODMAN) "podman" else "docker"
        val result = shell.exec(
            "$binary inspect --format '{{index .Config.Labels \"${DeployPlans.MANAGED_LABEL}\"}}' " +
                "${PosixShell.quote(name)} 2>/dev/null || true",
            null,
            20_000,
        )
        return result.stdout.trim().ifBlank { null }
    }

    /** Null when the host does not confirm the label, so nothing is removed. */
    fun removeOwnedContainer(runtime: DeployCommands.Runtime, name: String): ExecResult? {
        if (!DeployPlans.isOwnedResource(name, containerLabel(runtime, name))) return null
        val binary = if (runtime == DeployCommands.Runtime.PODMAN) "podman" else "docker"
        return shell.exec("$binary rm --force ${PosixShell.quote(name)}", null, 60_000)
    }

    /**
     * Re-reads the active context and RBAC authority, then applies the generated
     * manifest from stdin and records the UID the cluster assigned.
     *
     * Nothing about the apply is inferred: the caller only gets a UID back when
     * the object that now exists is labelled as one this app created. Without a
     * UID no later rollback or delete is offered at all.
     */
    fun workloadApply(plan: DeployPlan.Workload): DeployOutcome {
        val contextResult = shell.exec(plan.contextCommand(), null, 20_000)
        val active = contextResult.stdout.trim()
        if (contextResult.exitCode != 0 || active != plan.context) {
            return DeployOutcome(
                ExecResult(
                    64,
                    "",
                    "the active kubectl context is '${active.ifBlank { "unknown" }}', not '${plan.context}'",
                ),
                null,
            )
        }
        val canI = shell.exec(plan.permissionCommand("patch"), null, 20_000)
        if (WorkloadGuard.authority(plan, active, canI.stdout) != WorkloadMutationVerdict.ALLOWED) {
            return DeployOutcome(
                ExecResult(64, "", "RBAC does not allow creating or patching ${plan.kind.resource} in ${plan.namespace}"),
                null,
            )
        }
        val applied = shell.exec(plan.applyCommand(), plan.manifest().toByteArray(Charsets.UTF_8), 120_000)
        if (applied.exitCode != 0) return DeployOutcome(applied, null)
        val identity = readIdentity(plan) ?: return DeployOutcome(applied, null)
        if (!DeployPlans.isOwnedResource(plan.name, identity.managedLabel)) {
            return DeployOutcome(applied, null)
        }
        return DeployOutcome(applied, identity.uid)
    }

    fun workloadRollout(plan: DeployPlan.Workload): ExecResult =
        shell.exec(plan.rolloutCommand(), null, 180_000)

    fun workloadLogs(plan: DeployPlan.Workload): String =
        shell.exec(plan.logsCommand(), null, 30_000).text

    /**
     * The check that every destructive Kubernetes action runs through: current
     * context, RBAC for the exact verb, then the live object's label and UID.
     * Any read that fails, or any answer that is not an explicit "yes", refuses.
     */
    fun workloadPreflight(plan: DeployPlan.Workload, verb: String): WorkloadPreflight {
        val contextResult = shell.exec(plan.contextCommand(), null, 20_000)
        val active = contextResult.stdout.trim()
        if (contextResult.exitCode != 0 || active != plan.context) {
            return refused(WorkloadMutationVerdict.REFUSED_CONTEXT_CHANGED)
        }
        val canI = shell.exec(plan.permissionCommand(verb), null, 20_000)
        if (WorkloadGuard.authority(plan, active, canI.stdout) != WorkloadMutationVerdict.ALLOWED) {
            return refused(WorkloadMutationVerdict.REFUSED_RBAC)
        }
        val identity = readIdentity(plan) ?: return refused(WorkloadMutationVerdict.REFUSED_NOT_FOUND)
        val verdict = WorkloadGuard.identity(plan, identity)
        return WorkloadPreflight(verdict, WorkloadGuard.explanation(verdict), identity)
    }

    fun workloadRollback(plan: DeployPlan.Workload): ExecResult =
        guardedMutation(plan, "patch", plan.rollbackCommand(), 120_000)

    fun workloadDelete(plan: DeployPlan.Workload): ExecResult =
        guardedMutation(plan, "delete", plan.deleteCommand(), 60_000)

    private fun guardedMutation(
        plan: DeployPlan.Workload,
        verb: String,
        command: String,
        timeoutMs: Long,
    ): ExecResult {
        // Nothing to verify against means nothing to do: do not even connect.
        if (plan.appliedUid == null) {
            return ExecResult(
                64,
                "",
                WorkloadGuard.explanation(WorkloadMutationVerdict.REFUSED_NO_RECORDED_IDENTITY),
            )
        }
        val preflight = workloadPreflight(plan, verb)
        if (!preflight.allowed) return ExecResult(64, "", preflight.detail)
        return shell.exec(command, null, timeoutMs)
    }

    private fun readIdentity(plan: DeployPlan.Workload): WorkloadIdentity? {
        val read = shell.exec(plan.identityCommand(), null, 20_000)
        if (read.exitCode != 0) return null
        return WorkloadGuard.parseIdentity(read.stdout)
    }

    private fun refused(verdict: WorkloadMutationVerdict): WorkloadPreflight =
        WorkloadPreflight(verdict, WorkloadGuard.explanation(verdict), null)
}
