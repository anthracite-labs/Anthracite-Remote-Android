package dev.anthracite.remote.deploy

import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.json.MiniJson
import java.security.SecureRandom

/**
 * What a deployment creation request means, before anything runs.
 *
 * A plan is data: it validates every value that will reach a host command and it
 * renders the exact commands the user is asked to approve. Nothing here executes
 * anything, and nothing here can produce an unquoted value.
 *
 * Every resource this app creates is named with the `anthracite-` prefix and, for
 * containers, labelled `anthracite.managed=1`. Cleanup is only ever offered for a
 * resource that still carries that name and that label, so an unrelated container
 * that happens to share a name cannot be stopped or removed by mistake.
 */
internal object DeployPlans {
    const val MANAGED_LABEL = "anthracite.managed"
    const val PREFIX = "anthracite-"
    const val MAX_VOLUMES = 8
    const val MAX_PORTS = 8
    const val MAX_ENV = 16
    const val MAX_REPLICAS = 10

    /** Verbs this app will ever use against a workload it created. */
    val WORKLOAD_VERBS = setOf("get", "patch", "delete")

    /** Kubernetes object UIDs are UUIDs; anything else is not an identity. */
    fun isKubernetesUid(value: String): Boolean =
        value.length == 36 && KUBERNETES_UID.matches(value)

    private val KUBERNETES_UID =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private val MANAGED_NAME = Regex("^anthracite-[a-z0-9][a-z0-9-]*$")

    fun newName(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(4).also(random::nextBytes)
        return PREFIX + bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * A managed name is the prefix **plus a real name**. `"anthracite-"` alone is
     * not ours: it is what a half-typed or truncated value looks like, and
     * treating it as ours would let cleanup target something this app never made.
     */
    fun isManagedName(value: String): Boolean =
        value.length in (PREFIX.length + 1)..64 &&
            MANAGED_NAME.matches(value) &&
            PosixShell.isContainerId(value)

    fun isOwnedResource(name: String, reportedLabel: String?): Boolean =
        isManagedName(name) && reportedLabel == "1"

    /** `8080:80`, `127.0.0.1:8080:80/udp` is deliberately not supported. */
    fun parsePorts(text: String): List<PortMapping> {
        val entries = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size <= MAX_PORTS) { "at most $MAX_PORTS port mappings are supported" }
        return entries.map { entry ->
            val parts = entry.split(':')
            require(parts.size == 2) { "a port mapping must be host:container, for example 8080:80" }
            val host = parts[0].toIntOrNull()
                ?: throw IllegalArgumentException("invalid host port: ${parts[0]}")
            val container = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("invalid container port: ${parts[1]}")
            PortMapping(host, container)
        }
    }

    /** `~/project:/app` or `/srv/data:/var/lib/data:ro`, one per line. */
    fun parseVolumes(text: String): List<VolumeMount> {
        val entries = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size <= MAX_VOLUMES) { "at most $MAX_VOLUMES volumes are supported" }
        return entries.map { entry ->
            val parts = entry.split(':')
            require(parts.size == 2 || parts.size == 3) {
                "a volume must be host-path:container-path[:ro]"
            }
            val readOnly = parts.size == 3 && parts[2] == "ro"
            if (parts.size == 3) require(readOnly) { "the only supported option is :ro" }
            VolumeMount(parts[0], parts[1], readOnly)
        }
    }

    /** `KEY=value`, one per line. Values may contain `=`. */
    fun parseEnvironment(text: String): List<EnvVar> {
        val entries = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size <= MAX_ENV) { "at most $MAX_ENV environment variables are supported" }
        return entries.map { entry ->
            val separator = entry.indexOf('=')
            require(separator > 0) { "an environment variable must be KEY=value" }
            EnvVar(entry.substring(0, separator), entry.substring(separator + 1))
        }
    }

    fun parseArguments(text: String): List<String> =
        text.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
}

internal data class PortMapping(val host: Int, val container: Int) {
    init {
        require(host in 1..65535) { "host port is out of range" }
        require(container in 1..65535) { "container port is out of range" }
    }

    override fun toString(): String = "$host:$container"
}

internal data class VolumeMount(val hostPath: String, val containerPath: String, val readOnly: Boolean = false) {
    init {
        require(isAbsolute(hostPath)) { "the host path must be absolute or start with ~/" }
        require(isAbsolute(containerPath)) { "the container path must be absolute" }
        require(PosixShell.isQuotablePath(hostPath)) { "the host path contains control characters" }
        require(PosixShell.isQuotablePath(containerPath)) { "the container path contains control characters" }
    }

    override fun toString(): String = buildString {
        append(hostPath).append(':').append(containerPath)
        if (readOnly) append(":ro")
    }

    private companion object {
        fun isAbsolute(value: String): Boolean =
            (value.startsWith("/") || value.startsWith("~/")) && !value.contains("..") && value.length <= 1024
    }
}

internal data class EnvVar(val key: String, val value: String) {
    init {
        require(key.matches(Regex("[A-Z_][A-Z0-9_]{0,63}"))) { "invalid environment variable name" }
        require(!value.contains('\n') && !value.contains('\r') && !value.contains('\u0000')) {
            "an environment value must be a single line"
        }
        require(value.length <= 512) { "an environment value is too long" }
    }
}

/** A deployment the user reviewed, rendered as the exact commands it will run. */
internal sealed class DeployPlan {
    abstract val name: String
    abstract val warnings: List<String>

    /** Commands in the order the client runs them. Read-only for the reviewer. */
    abstract fun commands(): List<String>

    /**
     * A native program managed as a user-level systemd unit. It is never a root
     * service: `systemd-run --user` runs with the SSH user's own authority.
     */
    data class Native(
        override val name: String,
        val workingDirectory: String,
        val executable: String,
        val arguments: List<String>,
    ) : DeployPlan() {
        init {
            require(DeployPlans.isManagedName(name)) { "invalid deployment name" }
            require(workingDirectory.startsWith("/") || workingDirectory.startsWith("~/")) {
                "the working directory must be absolute"
            }
            require(PosixShell.isQuotablePath(workingDirectory)) { "invalid working directory" }
            require(!workingDirectory.contains("..")) { "the working directory may not contain .." }
            require(PosixShell.isExecutableToken(executable)) { "invalid executable" }
            require(arguments.size <= 32) { "too many arguments" }
            require(arguments.all { it.length <= 200 && PosixShell.isQuotablePath(it) }) {
                "an argument is too long or contains control characters"
            }
        }

        override val warnings: List<String> = listOf(
            "Runs as your SSH user in a systemd user unit (`systemd-run --user`). " +
                "It is not a root service and it does not survive a host reboot unless you enable that yourself.",
        )

        override fun commands(): List<String> = listOf(
            buildString {
                append("systemd-run --user --collect --unit=").append(PosixShell.quote(name))
                append(" --working-directory=").append(PosixShell.quote(workingDirectory))
                append(" -- ").append(PosixShell.quote(executable))
                for (argument in arguments) {
                    append(' ').append(PosixShell.quote(argument))
                }
            },
        )

        fun statusCommand(): String = "systemctl --user is-active ${PosixShell.quote(name)}"
        fun logsCommand(): String = "journalctl --user -u ${PosixShell.quote(name)} -n 200 --no-pager 2>&1"
        fun stopCommand(): String = "systemctl --user stop ${PosixShell.quote(name)}"
    }

    /** A container created from an existing image, never from an anonymous pull. */
    data class Container(
        override val name: String,
        val runtime: DeployCommands.Runtime,
        val image: String,
        val ports: List<PortMapping> = emptyList(),
        val volumes: List<VolumeMount> = emptyList(),
        val environment: List<EnvVar> = emptyList(),
    ) : DeployPlan() {
        init {
            require(DeployPlans.isManagedName(name)) { "invalid container name" }
            require(PosixShell.isImageReference(image)) { "invalid image reference" }
            require(ports.size <= DeployPlans.MAX_PORTS) { "too many ports" }
            require(volumes.size <= DeployPlans.MAX_VOLUMES) { "too many volumes" }
            require(environment.size <= DeployPlans.MAX_ENV) { "too many environment variables" }
        }

        private val binary: String
            get() = if (runtime == DeployCommands.Runtime.PODMAN) "podman" else "docker"

        override val warnings: List<String>
            get() = listOf(
                runtime.warning,
                "The container is labelled ${DeployPlans.MANAGED_LABEL}=1. Only a container that " +
                    "still carries this name and label is stopped or removed later.",
                "Environment values are written to a 0600 file on the host and passed with " +
                    "--env-file, so they never appear in this screen or in a process list. Anyone who " +
                    "can use the container runtime's socket can still read them from `${binary} inspect`.",
            )

        override fun commands(): List<String> = buildList {
            add(binary + " pull " + PosixShell.quote(image))
            if (environment.isNotEmpty()) add(envFileCommand())
            add(runCommand())
            if (environment.isNotEmpty()) add(envFileRemoveCommand())
        }

        /**
         * The environment file the runtime reads instead of `--env KEY=value`.
         *
         * A value passed as an argument is visible in `ps` on the host, in the
         * shell history of anything that echoes the command and in this app's own
         * review screen. Values are therefore written once, with mode 0600, and
         * removed straight after the container starts.
         */
        fun envFilePath(): String = "\"\$HOME/.anthracite/deploy/$name.env\""

        fun envFileCommand(): String =
            "umask 077; mkdir -p \"\$HOME/.anthracite/deploy\" && cat > ${envFilePath()}"

        fun envFileRemoveCommand(): String = "rm -f ${envFilePath()}"

        /** The exact bytes the container will receive, as `docker --env-file` reads them. */
        fun envFileBytes(): ByteArray =
            environment.joinToString("") { variable -> "${variable.key}=${variable.value}\n" }
                .toByteArray(Charsets.UTF_8)

        /** What the reviewer may see: names in full, values never. */
        fun environmentPreview(): List<String> = environment.map { variable -> "${variable.key}=***" }

        fun runCommand(): String = buildString {
            append(binary).append(" run --detach --name ").append(PosixShell.quote(name))
            append(" --label ").append(PosixShell.quote("${DeployPlans.MANAGED_LABEL}=1"))
            for (port in ports) {
                append(" --publish ").append(PosixShell.quote(port.toString()))
            }
            for (volume in volumes) {
                append(" --volume ").append(PosixShell.quote(volume.toString()))
            }
            if (environment.isNotEmpty()) {
                // Only the path is an argument: no value ever reaches argv.
                append(" --env-file ").append(envFilePath())
            }
            append(' ').append(PosixShell.quote(image))
        }
    }

    /**
     * One reviewed workload, applied to one confirmed context and namespace.
     *
     * The manifest is **generated from typed fields**, never typed by the user and
     * never parsed back out of free text: there is exactly one document, exactly
     * one resource, the name is the generated one, and the ownership label is
     * always present because it is written here. Cluster-scoped kinds are refused
     * outright, because they cannot be cleaned up by name without risking
     * something this app did not create.
     *
     * [appliedUid] is the object identity the cluster reported after the apply.
     * Every later mutation re-reads it: a Deployment that was deleted and
     * re-created by someone else has the same name and would otherwise look like
     * ours.
     */
    data class Workload(
        override val name: String,
        val context: String,
        val namespace: String,
        val kind: WorkloadKind,
        val image: String,
        val replicas: Int = 1,
        val containerPort: Int = 8080,
        val appliedUid: String? = null,
    ) : DeployPlan() {
        init {
            require(DeployPlans.isManagedName(name)) { "invalid workload name" }
            require(DeployCommands.isKubeContext(context)) { "invalid Kubernetes context" }
            require(PosixShell.isKubernetesName(namespace)) { "invalid namespace" }
            require(PosixShell.isImageReference(image)) { "invalid image reference" }
            require(replicas in 1..DeployPlans.MAX_REPLICAS) {
                "replicas must be between 1 and ${DeployPlans.MAX_REPLICAS}"
            }
            require(containerPort in 1..65535) { "container port is out of range" }
            if (appliedUid != null) {
                require(DeployPlans.isKubernetesUid(appliedUid)) { "invalid Kubernetes UID" }
            }
        }

        /** The same plan, now carrying the identity the cluster assigned. */
        fun withAppliedUid(uid: String): Workload = copy(appliedUid = uid)

        override val warnings: List<String> = listOf(
            "Applies to context $context, namespace $namespace. Both are re-read on the host " +
                "immediately before every mutation, and only ${kind.apiValue}/$name is rolled back or deleted.",
            "The object carries ${DeployPlans.MANAGED_LABEL}=1 and its cluster UID is recorded when it " +
                "is created. Rollback and delete refuse to run if the object was replaced.",
            "No Secret, Namespace or cluster-scoped resource is created here, and no other resource " +
                "is ever touched.",
        )

        override fun commands(): List<String> = listOf(applyCommand(), rolloutCommand())

        /** Exactly one document, generated from the validated fields above. */
        fun manifest(): String = buildString {
            append("apiVersion: apps/v1\n")
            append("kind: ").append(kind.apiValue).append('\n')
            append("metadata:\n")
            append("  name: ").append(name).append('\n')
            append("  namespace: ").append(namespace).append('\n')
            append("  labels:\n")
            append("    ").append(DeployPlans.MANAGED_LABEL).append(": \"1\"\n")
            append("spec:\n")
            if (kind == WorkloadKind.STATEFULSET) {
                // apps/v1 StatefulSet requires a governing service name.
                append("  serviceName: ").append(name).append('\n')
            }
            append("  replicas: ").append(replicas).append('\n')
            append("  selector:\n")
            append("    matchLabels:\n")
            append("      app: ").append(name).append('\n')
            append("  template:\n")
            append("    metadata:\n")
            append("      labels:\n")
            append("        app: ").append(name).append('\n')
            append("    spec:\n")
            append("      containers:\n")
            append("        - name: app\n")
            append("          image: ").append(image).append('\n')
            append("          ports:\n")
            append("            - containerPort: ").append(containerPort).append('\n')
        }

        private fun kubectl(action: String): String =
            "kubectl --context ${PosixShell.quote(context)} --namespace ${PosixShell.quote(namespace)} $action"

        fun contextCommand(): String = "kubectl config current-context"

        /** The verb the caller is about to use, checked against this exact resource. */
        fun permissionCommand(verb: String): String {
            require(verb in DeployPlans.WORKLOAD_VERBS) { "unsupported kubectl verb" }
            return kubectl("auth can-i $verb ${kind.resource}")
        }

        /** The manifest arrives on stdin: nothing from it is parsed by a shell. */
        fun applyCommand(): String = kubectl("apply -f -")

        /**
         * The object as the cluster holds it, including its immutable UID and its
         * ownership label. Read-only, and parsed structurally: the label key
         * contains dots, which a jsonpath would have to escape.
         */
        fun identityCommand(): String = kubectl("get ${kind.resource}/${PosixShell.quote(name)} -o json")

        fun rolloutCommand(): String =
            kubectl("rollout status ${kind.resource}/${PosixShell.quote(name)} --timeout=120s")

        fun logsCommand(): String =
            kubectl("logs ${kind.resource}/${PosixShell.quote(name)} --tail=200 --all-containers 2>&1")

        fun rollbackCommand(): String = kubectl("rollout undo ${kind.resource}/${PosixShell.quote(name)}")

        fun deleteCommand(): String =
            kubectl("delete ${kind.resource}/${PosixShell.quote(name)} --ignore-not-found --wait=false")
    }
}

/** An object identity as the cluster reports it. */
internal data class WorkloadIdentity(val uid: String, val managedLabel: String?)

/** Why a rollback or delete was allowed or refused. Every value fails closed. */
internal enum class WorkloadMutationVerdict {
    ALLOWED,
    REFUSED_NO_RECORDED_IDENTITY,
    REFUSED_CONTEXT_CHANGED,
    REFUSED_NOT_FOUND,
    REFUSED_NOT_OURS,
    REFUSED_UID_CHANGED,
    REFUSED_RBAC,
}

/** What a guarded mutation will do. Nothing here talks to a host. */
internal object WorkloadGuard {
    /**
     * The identity half of the check: is the object in front of us the exact one
     * this app created? A missing read, a missing label or a different UID all
     * refuse; there is no "probably ours".
     */
    fun identity(
        plan: DeployPlan.Workload,
        observed: WorkloadIdentity?,
    ): WorkloadMutationVerdict {
        val expectedUid = plan.appliedUid ?: return WorkloadMutationVerdict.REFUSED_NO_RECORDED_IDENTITY
        if (observed == null) return WorkloadMutationVerdict.REFUSED_NOT_FOUND
        if (!DeployPlans.isOwnedResource(plan.name, observed.managedLabel)) {
            return WorkloadMutationVerdict.REFUSED_NOT_OURS
        }
        if (observed.uid != expectedUid) return WorkloadMutationVerdict.REFUSED_UID_CHANGED
        return WorkloadMutationVerdict.ALLOWED
    }

    /**
     * The authority half: the active context must still be the one that was
     * reviewed, and RBAC must allow the exact verb about to be used. `can-i`
     * prints "yes"/"no"; anything else is treated as a refusal.
     */
    fun authority(
        plan: DeployPlan.Workload,
        activeContext: String,
        canIOutput: String,
    ): WorkloadMutationVerdict {
        if (activeContext.trim() != plan.context) return WorkloadMutationVerdict.REFUSED_CONTEXT_CHANGED
        if (canIOutput.trim().lowercase() != "yes") return WorkloadMutationVerdict.REFUSED_RBAC
        return WorkloadMutationVerdict.ALLOWED
    }

    fun explanation(verdict: WorkloadMutationVerdict): String = when (verdict) {
        WorkloadMutationVerdict.ALLOWED -> "The object is the one this app created."
        WorkloadMutationVerdict.REFUSED_NO_RECORDED_IDENTITY ->
            "This deployment has no recorded cluster identity, so nothing is changed."
        WorkloadMutationVerdict.REFUSED_CONTEXT_CHANGED ->
            "The active kubectl context changed since this deployment was reviewed."
        WorkloadMutationVerdict.REFUSED_NOT_FOUND ->
            "The object is no longer on the cluster, so nothing is changed."
        WorkloadMutationVerdict.REFUSED_NOT_OURS ->
            "The object on the cluster is not labelled as one this app created, so it is left alone."
        WorkloadMutationVerdict.REFUSED_UID_CHANGED ->
            "The object was replaced after it was created, so it is not the one that was reviewed."
        WorkloadMutationVerdict.REFUSED_RBAC ->
            "The cluster does not allow this action for your credentials."
    }

    /**
     * Reads the object reported by [DeployPlan.Workload.identityCommand].
     *
     * Anything that is not a Kubernetes UID in a readable document is null: an
     * unreadable answer is never evidence about which object is in front of us.
     */
    fun parseIdentity(stdout: String): WorkloadIdentity? {
        val document = runCatching { MiniJson.parse(stdout) }.getOrNull()?.asObject() ?: return null
        val metadata = document.obj("metadata") ?: return null
        val uid = metadata.string("uid") ?: return null
        if (!DeployPlans.isKubernetesUid(uid)) return null
        val label = metadata.obj("labels")?.string(DeployPlans.MANAGED_LABEL)
        return WorkloadIdentity(uid, label)
    }
}

/**
 * The exact text shown before a plan runs.
 *
 * Pure so a test can assert that no environment value reaches it: the reviewer
 * sees the commands, the generated manifest and the warnings, and never a value.
 */
internal object DeployPlanReview {
    fun detail(plan: DeployPlan, review: String, manifestHeader: String, environmentHeader: String): String =
        buildString {
            append(review)
            append("\n\n")
            append(plan.commands().joinToString("\n"))
            if (plan is DeployPlan.Workload) {
                append("\n\n")
                append(manifestHeader)
                append("\n")
                append(plan.manifest())
            }
            if (plan is DeployPlan.Container && plan.environment.isNotEmpty()) {
                append("\n\n")
                append(environmentHeader)
                append("\n")
                append(plan.environmentPreview().joinToString("\n"))
            }
            append("\n\n")
            append(plan.warnings.joinToString("\n"))
        }
}

/** Workload kinds with a rollout status this screen can report honestly. */
internal enum class WorkloadKind(val apiValue: String, val resource: String) {
    DEPLOYMENT("Deployment", "deployment"),
    STATEFULSET("StatefulSet", "statefulset"),
}
