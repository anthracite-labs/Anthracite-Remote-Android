package dev.anthracite.remote.workspace

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import dev.anthracite.remote.ConnectOutcome
import dev.anthracite.remote.ConnectionStore
import dev.anthracite.remote.SavedSecrets
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.SshTarget
import dev.anthracite.remote.host.HostFacts
import dev.anthracite.remote.host.HostKeys
import dev.anthracite.remote.host.HostOrigin
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.host.HostStore
import dev.anthracite.remote.host.KeystoreSecretStore
import dev.anthracite.remote.host.SecretStore
import dev.anthracite.remote.host.SharedPreferencesAdapter
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Process-wide workspace connection for the new host surfaces.
 *
 * The legacy OpenCode journey in [dev.anthracite.remote.MainActivity] keeps its
 * own [SshClient] and is deliberately untouched, so an existing installation
 * cannot regress. Both use the same host-key rules: first-use approval, refusal
 * of a changed key, and no automatic trust of a replacement.
 */
internal object Workspace {
    const val TERM = "xterm-256color"

    private var hostStore: HostStore? = null
    private var secretKey: SecretKey? = null

    val ssh: SshClient = SshClient()

    var profile: HostProfile? = null
        private set

    var lastFacts: HostFacts? = null

    fun store(context: Context): HostStore {
        hostStore?.let { return it }
        val preferences = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
        val created = HostStore(
            preferences = SharedPreferencesAdapter(preferences),
            secrets = KeystoreSecretStore(context),
        )
        hostStore = created
        return created
    }

    fun key(): SecretKey = secretKey ?: HostKeys.key().also { secretKey = it }

    /** The Keystore-backed file store, shared by host profiles and the GitHub token. */
    fun secrets(context: Context): SecretStore = KeystoreSecretStore(context)

    /** Reads a shipped host-kit script. Scripts are fixed assets, never generated per user input. */
    fun script(context: Context, name: String): String =
        context.assets.open("host/$name").bufferedReader().use { it.readText() }

    /**
     * Imports the legacy single profile once. The legacy record itself is only
     * read: forgetting the imported copy does not affect the OpenCode journey.
     */
    fun importLegacyIfNeeded(context: Context): HostProfile? {
        val store = store(context)
        val legacyStore = ConnectionStore(context)
        if (!legacyStore.hasConnection()) return null
        val legacy = legacyStore.load()
        val secrets = runCatching { legacyStore.secrets() }.getOrNull()
        return runCatching {
            store.importLegacyIfNeeded(
                legacy = HostProfile(
                    id = store.newId(),
                    label = legacy.username + "@" + legacy.host,
                    host = legacy.host,
                    port = legacy.sshPort,
                    username = legacy.username,
                    auth = legacy.auth,
                    remotePort = legacy.remotePort,
                    keyName = legacy.keyName,
                    hostKey = legacy.hostKey,
                    hostKeyType = legacy.hostKeyType,
                    fingerprint = legacy.fingerprint,
                    origin = HostOrigin.IMPORTED_LEGACY,
                    lastUsedAtMillis = 0,
                ),
                legacySecrets = secrets,
                key = key(),
            )
        }.getOrNull()
    }

    fun disconnect() {
        ssh.disconnect()
        profile = null
        lastFacts = null
    }

    fun describe(profile: HostProfile): String =
        "${profile.username}@${profile.host}:${profile.port}"

    fun identify(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}

internal class WorkspaceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Connects the workspace, asking the user to approve a first-use host key and
 * refusing to continue after a key change. Returns null on success, otherwise a
 * message that is safe to show.
 */
internal suspend fun connectWorkspace(activity: Activity, profile: HostProfile): String? {
    val store = Workspace.store(activity)
    val key = Workspace.key()
    val secrets: SavedSecrets = runCatching { store.secretsFor(profile, key) }
        .getOrElse { error -> return error.message ?: "Saved credentials are unavailable" }
    if (secrets.password == null && secrets.privateKey == null) {
        return "No credential is stored for ${profile.displayLabel}. Edit the host to add one."
    }
    val target = SshTarget(
        host = profile.host,
        port = profile.port,
        username = profile.username,
        password = secrets.password,
        privateKey = secrets.privateKey,
        passphrase = secrets.passphrase,
    )
    return when (val outcome = Workspace.ssh.connect(target, profile.hostKey)) {
        is ConnectOutcome.Connected -> {
            Workspace.profile = profile
            runCatching { store.markUsed(profile.id, System.currentTimeMillis()) }
            null
        }
        is ConnectOutcome.UnknownHostKey -> {
            val approved = askHostKeyApproval(activity, profile, outcome.fingerprint, outcome.keyType)
            if (!approved) {
                Workspace.ssh.disconnect()
                return "Host key was not approved."
            }
            runCatching { store.updateHostKey(profile.id, outcome.key, outcome.keyType, outcome.fingerprint) }
            val retry = Workspace.ssh.connect(target, outcome.key)
            if (retry is ConnectOutcome.Connected) {
                Workspace.profile = store.find(profile.id) ?: profile
                null
            } else {
                describeFailure(retry)
            }
        }
        is ConnectOutcome.ChangedHostKey ->
            "The host presented a different SSH key than the one you approved before " +
                "(${outcome.savedFingerprint}). Anthracite refused to authenticate. " +
                "Verify the new fingerprint on the host before approving it; a changed key can mean " +
                "the server was rebuilt, or that the connection is being intercepted."
        is ConnectOutcome.AuthFailed -> "Authentication failed: ${outcome.message}"
        is ConnectOutcome.Failed -> "Could not connect: ${outcome.message}"
    }
}

private suspend fun askHostKeyApproval(
    activity: Activity,
    profile: HostProfile,
    fingerprint: String,
    keyType: String,
): Boolean = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        AlertDialog.Builder(activity)
            .setTitle("Verify host key")
            .setMessage(
                "First connection to ${profile.host}:${profile.port}.\n\n" +
                    "Key type: $keyType\nFingerprint: $fingerprint\n\n" +
                    "Compare this with the server's own key (for example `ssh-keygen -lf " +
                    "/etc/ssh/ssh_host_" + keyType.replace("ssh-", "") + "_key.pub`) before approving.",
            )
            .setPositiveButton("Approve") { _, _ -> continuation.resume(true) }
            .setNegativeButton("Cancel") { _, _ -> continuation.resume(false) }
            .setOnCancelListener { continuation.resume(false) }
            .show()
    }
}

private fun describeFailure(outcome: ConnectOutcome): String = when (outcome) {
    is ConnectOutcome.AuthFailed -> "Authentication failed: ${outcome.message}"
    is ConnectOutcome.ChangedHostKey -> "Host key changed; authentication was refused."
    is ConnectOutcome.UnknownHostKey -> "Host key was not approved."
    is ConnectOutcome.Failed -> "Could not connect: ${outcome.message}"
    is ConnectOutcome.Connected -> ""
}

/** Small helper for running blocking SSH work off the main thread. */
internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
