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

/**
 * Creating and controlling deployments the user approved.
 *
 * Every mutation goes through a [DeployPlan], which has already validated every
 * value. Deleting a container is only offered after the host reports the
 * `anthracite.managed=1` label on that exact name, and a Kubernetes mutation
 * re-reads the active context and checks RBAC immediately before it runs.
 */
internal class DeployCreator(private val ssh: SshClient) {
    fun nativeStart(plan: DeployPlan.Native): ExecResult =
        ssh.exec(plan.commands().single(), timeoutMs = 30_000)

    fun nativeStatus(plan: DeployPlan.Native): String =
        ssh.exec(plan.statusCommand(), timeoutMs = 20_000).stdout.trim()

    fun nativeLogs(plan: DeployPlan.Native): String =
        ssh.exec(plan.logsCommand(), timeoutMs = 30_000).text

    fun nativeStop(plan: DeployPlan.Native): ExecResult =
        ssh.exec(plan.stopCommand(), timeoutMs = 30_000)

    fun containerPull(plan: DeployPlan.Container): ExecResult =
        ssh.exec(plan.commands().first(), timeoutMs = 600_000)

    fun containerRun(plan: DeployPlan.Container): ExecResult =
        ssh.exec(plan.runCommand(), timeoutMs = 180_000)

    /** Reads the ownership label; null means the container does not carry it. */
    fun containerLabel(runtime: DeployCommands.Runtime, name: String): String? {
        require(DeployPlans.isManagedName(name)) { "invalid container name" }
        val binary = if (runtime == DeployCommands.Runtime.PODMAN) "podman" else "docker"
        val result = ssh.exec(
            "$binary inspect --format '{{index .Config.Labels \"${DeployPlans.MANAGED_LABEL}\"}}' " +
                "${PosixShell.quote(name)} 2>/dev/null || true",
            timeoutMs = 20_000,
        )
        return result.stdout.trim().ifBlank { null }
    }

    /** Null when the host does not confirm the label, so nothing is removed. */
    fun removeOwnedContainer(runtime: DeployCommands.Runtime, name: String): ExecResult? {
        if (!DeployPlans.isOwnedResource(name, containerLabel(runtime, name))) return null
        val binary = if (runtime == DeployCommands.Runtime.PODMAN) "podman" else "docker"
        return ssh.exec("$binary rm --force ${PosixShell.quote(name)}", timeoutMs = 60_000)
    }

    /**
     * Re-reads the active context and RBAC authority, then applies the manifest
     * from stdin. A context that changed since the preview refuses the mutation.
     */
    fun workloadApply(plan: DeployPlan.Workload): ExecResult {
        val active = ssh.exec(plan.contextCommand(), timeoutMs = 20_000).stdout.trim()
        if (active != plan.context) {
            return ExecResult(64, "", "the active kubectl context is now '$active', not '${plan.context}'")
        }
        val allowed = ssh.exec(plan.permissionCommand(), timeoutMs = 20_000).stdout.trim().lowercase()
        if (allowed != "yes") {
            return ExecResult(64, "", "RBAC does not allow patching ${plan.kind.resource} in ${plan.namespace}")
        }
        return ssh.exec(plan.applyCommand(), stdin = plan.manifest.toByteArray(Charsets.UTF_8), timeoutMs = 120_000)
    }

    fun workloadRollout(plan: DeployPlan.Workload): ExecResult =
        ssh.exec(plan.rolloutCommand(), timeoutMs = 180_000)

    fun workloadLogs(plan: DeployPlan.Workload): String =
        ssh.exec(plan.logsCommand(), timeoutMs = 30_000).text

    fun workloadRollback(plan: DeployPlan.Workload): ExecResult =
        ssh.exec(plan.rollbackCommand(), timeoutMs = 120_000)

    fun workloadDelete(plan: DeployPlan.Workload): ExecResult =
        ssh.exec(plan.deleteCommand(), timeoutMs = 60_000)
}
