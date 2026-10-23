package dev.anthracite.remote.deploy

import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.host.Preferences
import dev.anthracite.remote.json.JsonValue
import dev.anthracite.remote.json.MiniJson

/**
 * A deployment this app created, recorded so the screen that offers operations on
 * it survives process death.
 *
 * The record exists because ownership must not be inferred from a name. A
 * container name or a Kubernetes object name says nothing about *which* object is
 * in front of us: the runtime or the cluster may have deleted and re-created it,
 * and a name match would then let the app remove something it never made. Every
 * record therefore carries the immutable identity the runtime or the cluster
 * reported at creation time — a container id or a Kubernetes UID — together with
 * the host it was created through, its host-key fingerprint, and the runtime or
 * cluster context it was created in.
 *
 * No secret is stored: environment values never reach this record, and neither do
 * credentials. [image] is kept because a container plan cannot be rebuilt without
 * a valid image reference; it is not sensitive.
 */
internal enum class ManagedTargetKind { CONTAINER, WORKLOAD }

internal data class ManagedDeployment(
    val hostId: String,
    val hostFingerprint: String,
    val kind: ManagedTargetKind,
    val name: String,
    /** The container id or the Kubernetes UID assigned at creation. Never a name. */
    val identity: String,
    val runtime: DeployCommands.Runtime? = null,
    val workloadKind: WorkloadKind? = null,
    val context: String? = null,
    val namespace: String? = null,
    val image: String? = null,
    val replicas: Int = 1,
    val containerPort: Int = 8080,
    val createdAtMillis: Long = 0,
) {
    /**
     * True when every field is something the plan types would accept.
     *
     * The same rules guard both the write and the read: a record that would not
     * compile into a command is refused rather than half-trusted, so a corrupted
     * or tampered preference can never produce a command against a resource this
     * app would not otherwise build.
     */
    fun isValid(): Boolean {
        if (!isPlainToken(hostId) || hostId.length > 128) return false
        if (!isPlainToken(hostFingerprint) || hostFingerprint.length > 256) return false
        if (!DeployPlans.isManagedName(name)) return false
        if (createdAtMillis < 0) return false
        return when (kind) {
            ManagedTargetKind.CONTAINER ->
                runtime != null &&
                    workloadKind == null &&
                    context == null &&
                    namespace == null &&
                    image != null &&
                    PosixShell.isImageReference(image) &&
                    PosixShell.isContainerId(identity)

            ManagedTargetKind.WORKLOAD ->
                runtime == null &&
                    workloadKind != null &&
                    DeployPlans.isKubernetesUid(identity) &&
                    context != null &&
                    DeployCommands.isKubeContext(context) &&
                    namespace != null &&
                    PosixShell.isKubernetesName(namespace) &&
                    image != null &&
                    PosixShell.isImageReference(image) &&
                    replicas in 1..DeployPlans.MAX_REPLICAS &&
                    containerPort in 1..65535
        }
    }

    private fun isPlainToken(value: String): Boolean =
        value.isNotBlank() && value.none { it.code < 0x20 || it.code == 0x7f }
}

/**
 * Where the record was made, and what the screen knows about where it is now.
 *
 * A null field means "this screen does not currently know" and is not compared.
 * The workload kind is deliberately absent: it is part of the recorded plan, so
 * the command always names the recorded kind, and the object is pinned by its
 * UID instead of by a dropdown that can be changed.
 */
internal data class DeploymentTarget(
    val hostId: String,
    val hostFingerprint: String,
    val runtime: DeployCommands.Runtime? = null,
    val context: String? = null,
    val namespace: String? = null,
)

/** What a fresh read of the live object said. */
internal sealed class LiveIdentity {
    /** The runtime or cluster reported this identity for the recorded name. */
    data class Present(val value: String) : LiveIdentity()

    /** The quoted read was answered, and the object is not there. */
    object Missing : LiveIdentity()

    /** The read failed for any other reason: no evidence either way. */
    data class Unreadable(val detail: String) : LiveIdentity()
}

/** The decision the screen is allowed to act on. */
internal enum class ManagedState {
    /** The identity in front of us is exactly the one that was recorded. */
    MATCH,

    /** The record belongs to another host, runtime, context, namespace or kind. */
    ELSEWHERE,

    /** A different object now has the recorded name. */
    REPLACED,

    /** The object is gone. */
    GONE,

    /** The object could not be read, so ownership is unknown. */
    UNKNOWN,
}

internal object ManagedReconciliation {
    /**
     * Compares the record with where the app is now and what it can see.
     *
     * A target mismatch is decided first and without reading anything: a record
     * made in another context, namespace, runtime or host is not evidence about
     * this one, so nothing here may be touched on its strength. Only an identity
     * that compares equal to the recorded one is a match; every other outcome
     * withholds the operations rather than guessing.
     */
    fun evaluate(record: ManagedDeployment, target: DeploymentTarget, live: LiveIdentity): ManagedState {
        if (!sameTarget(record, target)) return ManagedState.ELSEWHERE
        return when (live) {
            is LiveIdentity.Present ->
                if (live.value == record.identity) ManagedState.MATCH else ManagedState.REPLACED
            LiveIdentity.Missing -> ManagedState.GONE
            is LiveIdentity.Unreadable -> ManagedState.UNKNOWN
        }
    }

    fun sameTarget(record: ManagedDeployment, target: DeploymentTarget): Boolean {
        if (record.hostId != target.hostId) return false
        // A host key that changed is a different host, whatever the id says.
        if (record.hostFingerprint != target.hostFingerprint) return false
        return when (record.kind) {
            ManagedTargetKind.CONTAINER -> target.runtime == null || target.runtime == record.runtime
            ManagedTargetKind.WORKLOAD ->
                (target.context == null || target.context == record.context) &&
                    (target.namespace == null || target.namespace == record.namespace)
        }
    }
}

/** Builds a record from a plan that has just been confirmed, or null when it has no identity. */
internal object ManagedRecords {
    /**
     * A record exists only for a creation whose immutable identity is known.
     *
     * A native unit has no such identity — `systemd-run` names it and nothing else
     * survives — so no record is written and no later operation is offered from
     * disk. The same rule as everywhere else: a name is not ownership.
     */
    fun of(
        plan: DeployPlan,
        hostId: String,
        hostFingerprint: String,
        nowMillis: Long,
    ): ManagedDeployment? = when (plan) {
        is DeployPlan.Container -> plan.createdId?.let { id ->
            ManagedDeployment(
                hostId = hostId,
                hostFingerprint = hostFingerprint,
                kind = ManagedTargetKind.CONTAINER,
                name = plan.name,
                identity = id,
                runtime = plan.runtime,
                image = plan.image,
                createdAtMillis = nowMillis,
            )
        }

        is DeployPlan.Workload -> plan.appliedUid?.let { uid ->
            ManagedDeployment(
                hostId = hostId,
                hostFingerprint = hostFingerprint,
                kind = ManagedTargetKind.WORKLOAD,
                name = plan.name,
                identity = uid,
                workloadKind = plan.kind,
                context = plan.context,
                namespace = plan.namespace,
                image = plan.image,
                replicas = plan.replicas,
                containerPort = plan.containerPort,
                createdAtMillis = nowMillis,
            )
        }

        is DeployPlan.Native -> null
    }
}

/** Rebuilds the plan a record describes, so the same guarded operations apply. */
internal object ManagedPlans {
    fun of(record: ManagedDeployment): DeployPlan? {
        if (!record.isValid()) return null
        return when (record.kind) {
            ManagedTargetKind.CONTAINER -> DeployPlan.Container(
                name = record.name,
                runtime = record.runtime ?: return null,
                image = record.image ?: return null,
                createdId = record.identity,
            )

            ManagedTargetKind.WORKLOAD -> DeployPlan.Workload(
                name = record.name,
                context = record.context ?: return null,
                namespace = record.namespace ?: return null,
                kind = record.workloadKind ?: return null,
                image = record.image ?: return null,
                replicas = record.replicas,
                containerPort = record.containerPort,
                appliedUid = record.identity,
            )
        }
    }
}

/**
 * Persists the creations this screen offers operations on, keyed by host.
 *
 * One host key holds a bounded collection of records — up to
 * [MAX_RECORDS_PER_HOST], each keyed by the immutable resource identity the
 * runtime or cluster reported at creation. Recording a second deployment on the
 * same host therefore adds a record instead of replacing the previous one;
 * saving a record whose identity is already stored updates it in place. When
 * the bound is exceeded the oldest other records are evicted, never the record
 * just saved. Every record is reconciled individually by its own identity.
 *
 * A failed commit restores what was there before, so the on-disk collection
 * never points at a deployment the app did not just write.
 */
internal class ManagedDeploymentStore(
    private val preferences: Preferences,
    private val key: String = KEY,
) {
    fun loadAll(): List<ManagedDeployment> = ManagedDeploymentCodec.decodeAll(preferences.string(key))

    /** The newest record, for callers that offer operations on one deployment. */
    fun load(): ManagedDeployment? = loadAll().maxByOrNull { it.createdAtMillis }

    fun save(record: ManagedDeployment): Boolean {
        if (!record.isValid()) return false
        val kept = loadAll()
            .filter { it.identity != record.identity }
            .sortedBy { it.createdAtMillis }
            .takeLast(MAX_RECORDS_PER_HOST - 1) + record
        return write(kept)
    }

    fun remove(identity: String): Boolean {
        val remaining = loadAll().filter { it.identity != identity }
        return write(remaining)
    }

    fun clear(): Boolean {
        preferences.remove(key)
        return preferences.commit()
    }

    private fun write(records: List<ManagedDeployment>): Boolean {
        val encoded = ManagedDeploymentCodec.encodeAll(records) ?: return false
        val previous = preferences.string(key)
        preferences.putString(key, encoded)
        if (preferences.commit()) return true
        if (previous == null) preferences.remove(key) else preferences.putString(key, previous)
        preferences.commit()
        return false
    }

    companion object {
        const val KEY = "deploy.managed.v1"

        /** How many deployment records one host key holds; the oldest are evicted first. */
        const val MAX_RECORDS_PER_HOST = 8

        /**
         * One slot per host, so recording a deployment on one host cannot erase
         * what is recorded for another. The host id is app-generated and checked
         * here, so it can never smuggle a separator into the key.
         */
        fun keyFor(hostId: String): String {
            require(hostId.isNotBlank() && hostId.length <= 128) { "invalid host id" }
            require(hostId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "invalid host id" }
            return "$KEY.$hostId"
        }
    }
}

/**
 * The on-disk form.
 *
 * Versioned on purpose: a record written by a different schema is discarded, not
 * reinterpreted. Decoding is strict — an unknown kind, a missing field, a value
 * that is not a valid identity — returns null, so the screen shows nothing rather
 * than offering an operation on a record it cannot fully vouch for.
 */
internal object ManagedDeploymentCodec {
    const val VERSION = 1

    /** A host key holding several records: `{"version":2,"records":[{v1}, …]}`. */
    const val COLLECTION_VERSION = 2

    fun encode(record: ManagedDeployment): String? {
        if (!record.isValid()) return null
        return buildString {
            append('{')
            append("\"version\":").append(VERSION).append(',')
            append("\"hostId\":").append(quoted(record.hostId)).append(',')
            append("\"hostFingerprint\":").append(quoted(record.hostFingerprint)).append(',')
            append("\"kind\":").append(quoted(record.kind.name)).append(',')
            append("\"name\":").append(quoted(record.name)).append(',')
            append("\"identity\":").append(quoted(record.identity)).append(',')
            append("\"runtime\":").append(record.runtime?.let { quoted(it.name) } ?: "null").append(',')
            append("\"workloadKind\":").append(record.workloadKind?.let { quoted(it.name) } ?: "null").append(',')
            append("\"context\":").append(record.context?.let { quoted(it) } ?: "null").append(',')
            append("\"namespace\":").append(record.namespace?.let { quoted(it) } ?: "null").append(',')
            append("\"image\":").append(record.image?.let { quoted(it) } ?: "null").append(',')
            append("\"replicas\":").append(record.replicas).append(',')
            append("\"containerPort\":").append(record.containerPort).append(',')
            append("\"createdAtMillis\":").append(record.createdAtMillis)
            append('}')
        }
    }

    fun decode(text: String?): ManagedDeployment? {
        if (text.isNullOrBlank()) return null
        val document = runCatching { MiniJson.parse(text) }.getOrNull()?.asObject() ?: return null
        if (document.int("version") != VERSION) return null
        return fromObject(document)?.takeIf { it.isValid() }
    }

    /**
     * Encodes a bounded collection. Any invalid record refuses the whole write
     * rather than persisting a prefix.
     */
    fun encodeAll(records: List<ManagedDeployment>): String? {
        if (records.any { !it.isValid() }) return null
        return buildString {
            append("{\"version\":").append(COLLECTION_VERSION).append(",\"records\":[")
            records.forEachIndexed { index, record ->
                if (index > 0) append(',')
                append(encode(record) ?: return null)
            }
            append("]}")
        }
    }

    /**
     * Decodes a collection, a legacy single record, or nothing. Unknown
     * versions, unknown kinds and invalid values are dropped, never
     * reinterpreted; garbage decodes to an empty list.
     */
    fun decodeAll(text: String?): List<ManagedDeployment> {
        if (text.isNullOrBlank()) return emptyList()
        val document = runCatching { MiniJson.parse(text) }.getOrNull()?.asObject() ?: return emptyList()
        if (document.int("version") == COLLECTION_VERSION) {
            val items = document.array("records")?.items ?: return emptyList()
            return items.mapNotNull { it.asObject()?.let(::fromObject) }
                .filter { it.isValid() }
                .take(ManagedDeploymentStore.MAX_RECORDS_PER_HOST)
        }
        // Legacy single-record form: one valid record or nothing.
        return listOfNotNull(decode(text))
    }

    private fun fromObject(document: JsonValue.Obj): ManagedDeployment? {
        val kind = when (document.string("kind")) {
            ManagedTargetKind.CONTAINER.name -> ManagedTargetKind.CONTAINER
            ManagedTargetKind.WORKLOAD.name -> ManagedTargetKind.WORKLOAD
            else -> return null
        }
        return ManagedDeployment(
            hostId = document.string("hostId") ?: return null,
            hostFingerprint = document.string("hostFingerprint") ?: return null,
            kind = kind,
            name = document.string("name") ?: return null,
            identity = document.string("identity") ?: return null,
            runtime = document.string("runtime")?.let { name ->
                DeployCommands.Runtime.entries.firstOrNull { it.name == name } ?: return null
            },
            workloadKind = document.string("workloadKind")?.let { name ->
                WorkloadKind.entries.firstOrNull { it.name == name } ?: return null
            },
            context = document.string("context"),
            namespace = document.string("namespace"),
            image = document.string("image"),
            replicas = document.int("replicas") ?: 1,
            containerPort = document.int("containerPort") ?: 8080,
            createdAtMillis = document.long("createdAtMillis") ?: 0,
        )
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
