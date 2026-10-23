package dev.anthracite.remote.deploy

import dev.anthracite.remote.host.PosixShell
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
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_VOLUMES = 8
    const val MAX_PORTS = 8
    const val MAX_ENV = 16

    fun newName(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(4).also(random::nextBytes)
        return PREFIX + bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun isManagedName(value: String): Boolean =
        value.startsWith(PREFIX) && PosixShell.isContainerId(value) && value.length <= 64

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
            )

        override fun commands(): List<String> = listOf(
            binary + " pull " + PosixShell.quote(image),
            runCommand(),
        )

        fun runCommand(): String = buildString {
            append(binary).append(" run --detach --name ").append(PosixShell.quote(name))
            append(" --label ").append(PosixShell.quote("${DeployPlans.MANAGED_LABEL}=1"))
            for (port in ports) {
                append(" --publish ").append(PosixShell.quote(port.toString()))
            }
            for (volume in volumes) {
                append(" --volume ").append(PosixShell.quote(volume.toString()))
            }
            for (variable in environment) {
                // The value is quoted as one `KEY=value` token: it is never bare.
                append(" --env ").append(PosixShell.quote("${variable.key}=${variable.value}"))
            }
            append(' ').append(PosixShell.quote(image))
        }
    }

    /**
     * One reviewed workload manifest applied to one confirmed context and
     * namespace. Cluster-scoped kinds are refused, because they cannot be cleaned
     * up by name without risking something this app did not create.
     */
    data class Workload(
        override val name: String,
        val context: String,
        val namespace: String,
        val kind: WorkloadKind,
        val manifest: String,
    ) : DeployPlan() {
        init {
            require(DeployPlans.isManagedName(name)) { "invalid workload name" }
            require(DeployCommands.isKubeContext(context)) { "invalid Kubernetes context" }
            require(PosixShell.isKubernetesName(namespace)) { "invalid namespace" }
            require(manifest.toByteArray(Charsets.UTF_8).size <= DeployPlans.MAX_MANIFEST_BYTES) {
                "the manifest is larger than ${DeployPlans.MAX_MANIFEST_BYTES} bytes"
            }
            require(!manifest.contains('\u0000')) { "the manifest contains NUL" }
            require(!manifest.contains("\n---")) { "a manifest must contain exactly one document" }
            require(!manifest.trimStart().startsWith("---")) { "a manifest must contain one document" }
            val declaredKind = valueOf(manifest, "kind")
            require(declaredKind != null) { "the manifest has no kind" }
            require(declaredKind == kind.apiValue) {
                "the manifest kind is $declaredKind, not ${kind.apiValue}"
            }
            val metadataName = metadataNameOf(manifest)
            require(metadataName == name) {
                "the manifest's metadata.name is ${metadataName ?: "missing"}, not $name"
            }
        }

        override val warnings: List<String> = listOf(
            "Applies to context ${context}, namespace ${namespace}; both are re-checked on the host " +
                "immediately before the mutation. Only ${kind.apiValue}/$name is rolled back or deleted.",
            "No Secret, Namespace or cluster-scoped resource is accepted here.",
        )

        override fun commands(): List<String> = listOf(applyCommand(), rolloutCommand())

        private fun kubectl(action: String): String =
            "kubectl --context ${PosixShell.quote(context)} --namespace ${PosixShell.quote(namespace)} $action"

        fun contextCommand(): String = "kubectl config current-context"

        fun permissionCommand(): String =
            kubectl("auth can-i patch ${kind.resource}")

        /** The manifest arrives on stdin: nothing from it is parsed by a shell. */
        fun applyCommand(): String = kubectl("apply -f -")

        fun rolloutCommand(): String =
            kubectl("rollout status ${kind.resource}/${PosixShell.quote(name)} --timeout=120s")

        fun logsCommand(): String =
            kubectl("logs ${kind.resource}/${PosixShell.quote(name)} --tail=200 --all-containers 2>&1")

        fun rollbackCommand(): String = kubectl("rollout undo ${kind.resource}/${PosixShell.quote(name)}")

        fun deleteCommand(): String =
            kubectl("delete ${kind.resource}/${PosixShell.quote(name)} --ignore-not-found --wait=false")

        private companion object {
            fun valueOf(manifest: String, key: String): String? =
                Regex("(?m)^$key:[ \\t]*([A-Za-z]+)[ \\t]*$").find(manifest)?.groupValues?.get(1)

            fun metadataNameOf(manifest: String): String? =
                Regex("(?m)^metadata:[ \\t]*\\n(?:[ \\t]+[A-Za-z0-9._/-]+:.*\\n)*?[ \\t]+name:[ \\t]*([A-Za-z0-9._-]+)[ \\t]*$")
                    .find(manifest)?.groupValues?.get(1)
        }
    }
}

/** Workload kinds with a rollout status this screen can report honestly. */
internal enum class WorkloadKind(val apiValue: String, val resource: String) {
    DEPLOYMENT("Deployment", "deployment"),
    STATEFULSET("StatefulSet", "statefulset"),
}
