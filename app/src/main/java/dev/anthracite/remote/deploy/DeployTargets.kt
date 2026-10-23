package dev.anthracite.remote.deploy

import dev.anthracite.remote.ExecResult
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.json.MiniJson

/**
 * Native, Docker, Podman and Kubernetes controls built on the host's own CLIs.
 *
 * Rules enforced here:
 * * no implicit `--privileged` and no socket forwarding: creation only ever names
 *   an existing image and never escalates;
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

/**
 * What a creation produced: the last command result and the identity the host
 * confirmed — a Kubernetes UID or a container id — when there is one.
 */
internal data class DeployOutcome(val result: ExecResult, val identity: String? = null)

/** A container as the runtime reports it: immutable id and ownership label. */
internal data class ContainerIdentity(val id: String, val managedLabel: String?)

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
    fun containerRun(plan: DeployPlan.Container): DeployOutcome {
        val started = if (plan.environment.isEmpty()) {
            shell.exec(plan.runCommand(), null, 180_000)
        } else {
            var weWroteTheFile = false
            try {
                val written = shell.exec(plan.envFileCommand(), plan.envFileBytes(), 30_000)
                if (written.exitCode != 0) {
                    // The write command removes its own partial file, and refuses
                    // without touching a pre-existing one. Either way nothing here
                    // belongs to this run, so no removal is sent: this app never
                    // deletes a file it did not create.
                    written
                } else {
                    weWroteTheFile = true
                    shell.exec(plan.runCommand(), null, 180_000)
                }
            } finally {
                if (weWroteTheFile) shell.exec(plan.envFileRemoveCommand(), null, 20_000)
            }
        }
        if (started.exitCode != 0) return DeployOutcome(started)
        // Record the immutable id now; without it the container is never removed
        // by this app, which fails closed rather than deleting on a name alone.
        return DeployOutcome(started, containerIdentity(plan)?.id)
    }

    /** Reads the container's immutable id and ownership label, or null. */
    fun containerIdentity(plan: DeployPlan.Container): ContainerIdentity? {
        val result = shell.exec(plan.identityCommand(), null, 20_000)
        if (result.exitCode != 0) return null
        val parts = result.stdout.trim().split('|')
        if (parts.size != 2) return null
        val id = parts[0].trim()
        if (!PosixShell.isContainerId(id)) return null
        return ContainerIdentity(id, parts[1].trim().ifBlank { null })
    }

    /**
     * Reads what the runtime reports for this name, and nothing else.
     *
     * Used to reconcile a persisted record after the screen was recreated. Only a
     * structurally valid id is reported as an identity; a failed or garbled read
     * is [LiveIdentity.Unreadable], because this code cannot tell "no such
     * container" from "the runtime is not answering" and does not guess.
     */
    fun observeIdentity(plan: DeployPlan.Container): LiveIdentity {
        val read = shell.exec(plan.identityCommand(), null, 20_000)
        if (read.exitCode != 0) {
            return LiveIdentity.Unreadable("The container runtime did not report this name.")
        }
        val id = read.stdout.trim().substringBefore('|').trim()
        if (!PosixShell.isContainerId(id)) {
            return LiveIdentity.Unreadable("The runtime's answer was not a container identity.")
        }
        return LiveIdentity.Present(id)
    }

    /**
     * The same for a Kubernetes object.
     *
     * A 404 is reported as [LiveIdentity.Missing] only because `kubectl` says so
     * with its own `(NotFound)` marker; every other failure stays unreadable.
     */
    fun observeIdentity(plan: DeployPlan.Workload): LiveIdentity {
        val read = shell.exec(plan.identityCommand(), null, 20_000)
        if (read.exitCode != 0) {
            val detail = (read.stderr + read.stdout).lowercase()
            return if (detail.contains("(notfound)") || detail.contains("not found")) {
                LiveIdentity.Missing
            } else {
                LiveIdentity.Unreadable("The cluster did not report this object.")
            }
        }
        val identity = WorkloadGuard.parseIdentity(read.stdout)
            ?: return LiveIdentity.Unreadable("The cluster's answer was not an object identity.")
        return LiveIdentity.Present(identity.uid)
    }

    /**
     * Removes only the exact container this app created.
     *
     * Both facts must still hold: the id the runtime reports for the name is the
     * id recorded at creation, and the container carries our label. Removal then
     * names the full id, so a container that took the same name afterwards cannot
     * be hit. Null means the host did not confirm ownership and nothing was sent.
     */
    fun removeOwnedContainer(plan: DeployPlan.Container): ExecResult? {
        val recorded = plan.createdId ?: return null
        val observed = containerIdentity(plan) ?: return null
        if (observed.id != recorded) return null
        if (!DeployPlans.isOwnedResource(plan.name, observed.managedLabel)) return null
        return shell.exec(plan.removeCommand(recorded), null, 60_000)
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
        // `create`, not `patch`: this path may only make a new object.
        val canI = shell.exec(plan.permissionCommand("create"), null, 20_000)
        if (WorkloadGuard.authority(plan, active, canI.stdout) != WorkloadMutationVerdict.ALLOWED) {
            return DeployOutcome(
                ExecResult(64, "", "RBAC does not allow creating ${plan.kind.resource} in ${plan.namespace}"),
                null,
            )
        }
        val applied = shell.exec(plan.createCommand(), plan.manifest().toByteArray(Charsets.UTF_8), 120_000)
        if (applied.exitCode != 0) {
            val alreadyExists = applied.stderr.contains("AlreadyExists", ignoreCase = true)
            return DeployOutcome(
                ExecResult(
                    if (alreadyExists) 64 else applied.exitCode,
                    applied.stdout,
                    if (alreadyExists) {
                        "An object named ${plan.name} already exists in ${plan.namespace}. It was left " +
                            "untouched: this app never adopts an object it did not create."
                    } else {
                        applied.stderr
                    },
                ),
                null,
            )
        }
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

    /**
     * `kubectl rollout undo`, guarded and then verified.
     *
     * The API server offers no precondition for a rollout: unlike a delete, the
     * undo cannot carry the reviewed UID, so between the guard's UID read and the
     * undo the object could be replaced — the window is small and it is real. This
     * does not claim otherwise. What it does is **detect** it: the identity is read
     * again afterwards, and a rollback that landed on a different object is
     * reported as replaced rather than as success. A user who sees that knows the
     * cluster holds something this app did not review.
     */
    fun workloadRollback(plan: DeployPlan.Workload): ExecResult {
        val result = guardedMutation(plan, "patch", { _ -> plan.rollbackCommand() }, 120_000)
        if (result.exitCode != 0) return result
        val expected = plan.appliedUid ?: return result
        val after = readIdentity(plan)
            ?: return ExecResult(
                DeployPlans.EXIT_IDENTITY_UNAVAILABLE,
                result.stdout,
                "The rollback ran, but the object's identity could not be read afterwards, " +
                    "so it is unknown whether the object is still the one that was reviewed.",
            )
        if (after.uid == expected) return result
        return ExecResult(
            DeployPlans.EXIT_IDENTITY_CHANGED,
            result.stdout,
            "The object was replaced while the rollback ran: the cluster now holds a different " +
                "object under this name. Nothing further will be done with it.",
        )
    }

    fun workloadDelete(plan: DeployPlan.Workload): ExecResult =
        // The mutation is built from the reviewed UID, and the cluster checks that
        // UID inside the delete itself: there is no unconditional delete here.
        guardedMutation(plan, "delete", { uid -> plan.conditionalDeleteCommand(uid) }, 60_000)

    private fun guardedMutation(
        plan: DeployPlan.Workload,
        verb: String,
        command: (String) -> String,
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
        // The last identity check and the mutation travel as one host command, so
        // no round trip can slip between them.
        val uid = plan.appliedUid ?: return ExecResult(64, "", preflight.detail)
        return shell.exec(plan.guardedMutationCommand(command(uid), uid), null, timeoutMs)
    }

    private fun readIdentity(plan: DeployPlan.Workload): WorkloadIdentity? {
        val read = shell.exec(plan.identityCommand(), null, 20_000)
        if (read.exitCode != 0) return null
        return WorkloadGuard.parseIdentity(read.stdout)
    }

    private fun refused(verdict: WorkloadMutationVerdict): WorkloadPreflight =
        WorkloadPreflight(verdict, WorkloadGuard.explanation(verdict), null)
}
