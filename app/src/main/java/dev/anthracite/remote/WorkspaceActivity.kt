package dev.anthracite.remote

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import dev.anthracite.remote.host.CapabilityState
import dev.anthracite.remote.host.HostFacts
import dev.anthracite.remote.host.HostProbe
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.terminal.TerminalActivity
import dev.anthracite.remote.ui.addActionButton
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.launch

/**
 * Workspace hub: host identity, orthogonal capability states and navigation to
 * the terminal, files, Git, GitHub, deployments and provisioning screens.
 */
class WorkspaceActivity : AppCompatActivity() {
    private lateinit var hostView: TextView
    private lateinit var endpointView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var warningCard: MaterialCardView
    private lateinit var warningView: TextView
    private lateinit var factsView: TextView
    private lateinit var actions: LinearLayout

    private var host: HostProfile? = null
    private var facts: HostFacts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace)
        hostView = findViewById(R.id.workspace_host)
        endpointView = findViewById(R.id.workspace_endpoint)
        capabilityView = findViewById(R.id.workspace_capabilities)
        warningCard = findViewById(R.id.workspace_warning_card)
        warningView = findViewById(R.id.workspace_warning)
        factsView = findViewById(R.id.workspace_facts)
        actions = findViewById(R.id.workspace_actions)
        findViewById<MaterialToolbar>(R.id.workspace_toolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.workspace_refresh).setOnClickListener { probe() }
        buildActions()
        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        val store = Workspace.store(this)
        host = runCatching { hostId?.let(store::find) }.getOrNull() ?: Workspace.profile
        render()
        ensureConnected()
    }

    private fun ensureConnected() {
        val target = host ?: run {
            Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == target.id) {
            probe()
            return
        }
        lifecycleScope.launch {
            val error = connectWorkspace(this@WorkspaceActivity, target)
            if (error != null) {
                Toast.makeText(this@WorkspaceActivity, error, Toast.LENGTH_LONG).show()
            } else {
                probe()
            }
        }
    }

    private fun probe() {
        if (!Workspace.ssh.isConnected()) return
        val script = Workspace.script(this, "anthracite-probe.sh")
        setStatus(getString(R.string.workspace_probing))
        lifecycleScope.launch {
            val result = runCatching { io { HostProbe(Workspace.ssh, script).run() } }
            result.onSuccess { observed ->
                facts = observed
                Workspace.lastFacts = observed
                renderFacts(observed)
            }.onFailure {
                setStatus(getString(R.string.workspace_probe_failed, it.message ?: ""))
            }
        }
    }

    private fun renderFacts(observed: HostFacts) {
        val durability = observed.durability()
        val capabilities = observed.capabilities()
        val lines = buildList {
            add(getString(R.string.workspace_cap_terminal, label(capabilities.terminal)))
            add(getString(R.string.workspace_cap_files, label(capabilities.files)))
            add(getString(R.string.workspace_cap_git, label(capabilities.hostGit)))
            add(getString(R.string.workspace_cap_provision, label(capabilities.provisioning)))
            add(getString(R.string.workspace_cap_docker, label(capabilities.docker)))
            add(getString(R.string.workspace_cap_podman, label(capabilities.podman)))
            add(getString(R.string.workspace_cap_kubernetes, label(capabilities.kubernetes)))
            add(getString(R.string.workspace_cap_disconnect_durability, label(durability.disconnected)))
            add(getString(R.string.workspace_cap_logout_durability, label(durability.logout)))
            add(getString(R.string.workspace_cap_reboot_durability, label(durability.reboot)))
        }
        capabilityView.text = lines.joinToString("\n")
        warningView.text = (listOf(durability.detail) + capabilities.detail).joinToString("\n\n")
        warningCard.visibility = View.VISIBLE
        factsView.text = buildString {
            append(observed.displayName).append(' ').append(observed.architecture).append('\n')
            append("kernel ").append(observed.kernelRelease).append('\n')
            append("user ").append(observed.user).append(" (uid ").append(observed.uid).append(")\n")
            append("home ").append(observed.home).append('\n')
            append("cwd ").append(observed.workingDirectory).append('\n')
            append("package manager ").append(observed.packageManager?.display ?: "none detected").append('\n')
            append("cpu ").append(observed.cpuCount).append(" · mem ")
            append(observed.memoryTotalKb / 1024).append(" MB · free ")
            append(observed.diskAvailableKb / 1024).append(" MB\n")
            append("sudo ").append(if (observed.sudoNonInteractive) "passwordless" else "not passwordless")
            append(" · linger ").append(observed.linger?.toString() ?: "unknown").append('\n')
            append("supervision ").append(observed.supervision.name.lowercase()).append('\n')
            append("container ").append(if (observed.containerized) "yes" else "no").append('\n')
            append("tools ")
            append(observed.tools.keys.sorted().joinToString(", "))
            if (!observed.complete) append("\nprobe was truncated: some facts are missing")
        }
        setStatus(
            getString(
                R.string.workspace_probed_at,
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                    .format(java.util.Date(observed.capturedAtMillis)),
            ),
        )
    }

    private fun label(state: CapabilityState): String = when (state) {
        CapabilityState.AVAILABLE -> getString(R.string.capability_available)
        CapabilityState.DETECTED_UNUSABLE -> getString(R.string.capability_detected_unusable)
        CapabilityState.UNSUPPORTED -> getString(R.string.capability_unsupported)
        CapabilityState.UNKNOWN -> getString(R.string.capability_unknown)
    }

    private fun buildActions() {
        actions.removeAllViews()
        addActionButton(this, actions, getString(R.string.workspace_open_terminal), getString(R.string.workspace_open_terminal)) {
            open(TerminalActivity::class.java)
        }
        addActionButton(this, actions, getString(R.string.workspace_open_files), getString(R.string.workspace_open_files)) {
            open(FilesActivity::class.java)
        }
        addActionButton(this, actions, getString(R.string.workspace_open_git), getString(R.string.workspace_open_git)) {
            open(GitActivity::class.java)
        }
        addActionButton(this, actions, getString(R.string.workspace_open_github), getString(R.string.workspace_open_github)) {
            open(GitHubActivity::class.java)
        }
        addActionButton(this, actions, getString(R.string.workspace_open_deploy), getString(R.string.workspace_open_deploy)) {
            open(DeployActivity::class.java)
        }
        addActionButton(this, actions, getString(R.string.workspace_open_bootstrap), getString(R.string.workspace_open_bootstrap)) {
            val intent = Intent(this, BootstrapActivity::class.java)
            host?.let { intent.putExtra(HostsActivity.EXTRA_HOST_ID, it.id) }
            startActivity(intent)
        }
        addActionButton(this, actions, getString(R.string.disconnect), getString(R.string.disconnect)) {
            dev.anthracite.remote.terminal.TerminalTabs.clear()
            Workspace.disconnect()
            finish()
        }
    }

    private fun open(type: Class<*>) {
        val intent = Intent(this, type)
        host?.let { intent.putExtra(HostsActivity.EXTRA_HOST_ID, it.id) }
        startActivity(intent)
    }

    private fun render() {
        val target = host ?: return
        hostView.text = target.displayLabel
        endpointView.text = buildString {
            append(target.endpoint)
            if (target.fingerprint.isNotBlank()) {
                append(" · ")
                append(target.fingerprint.take(30))
                append('…')
            }
        }
    }

    private fun setStatus(text: String) {
        factsView.contentDescription = text
        if (facts == null) factsView.text = text
    }
}
