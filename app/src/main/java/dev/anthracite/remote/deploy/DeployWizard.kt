package dev.anthracite.remote.deploy

/**
 * The creation wizard's decisions, with no Android UI attached.
 *
 * The managed name is fixed when the wizard is constructed, before the first
 * field is shown. That is what makes the review honest: the name in the manifest
 * is the name the user was told about, and it is a name this app may later
 * inspect and remove. A name generated *after* the form would be a different
 * object from the one that was reviewed.
 *
 * A workload is described by typed fields — image, replicas, container port —
 * rather than by pasted YAML, so there is exactly one document, exactly one
 * resource, and the ownership label cannot be forgotten or forged.
 */
internal class DeployWizard(val name: String = DeployPlans.newName()) {
    init {
        require(DeployPlans.isManagedName(name)) { "invalid workload name" }
    }

    fun container(
        runtime: DeployCommands.Runtime,
        image: String,
        ports: String,
        volumes: String,
        environment: String,
    ): DeployPlan.Container = DeployPlan.Container(
        name = name,
        runtime = runtime,
        image = image.trim(),
        ports = DeployPlans.parsePorts(ports),
        volumes = DeployPlans.parseVolumes(volumes),
        environment = DeployPlans.parseEnvironment(environment),
    )

    fun workload(
        kind: WorkloadKind,
        context: String,
        namespace: String,
        image: String,
        replicas: String,
        containerPort: String,
    ): DeployPlan.Workload = DeployPlan.Workload(
        name = name,
        context = context,
        namespace = namespace,
        kind = kind,
        image = image.trim(),
        replicas = parseReplicas(replicas),
        containerPort = parseContainerPort(containerPort),
    )

    companion object {
        /** An empty field keeps the default; anything else must be a sane number. */
        fun parseReplicas(text: String): Int {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) return 1
            val value = cleaned.toIntOrNull()
                ?: throw IllegalArgumentException("replicas must be a whole number")
            require(value in 1..DeployPlans.MAX_REPLICAS) {
                "replicas must be between 1 and ${DeployPlans.MAX_REPLICAS}"
            }
            return value
        }

        fun parseContainerPort(text: String): Int {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) return 8080
            val value = cleaned.toIntOrNull()
                ?: throw IllegalArgumentException("the container port must be a whole number")
            require(value in 1..65535) { "the container port must be between 1 and 65535" }
            return value
        }
    }
}
