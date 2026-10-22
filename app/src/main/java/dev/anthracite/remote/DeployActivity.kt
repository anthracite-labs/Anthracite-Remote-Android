package dev.anthracite.remote

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import dev.anthracite.remote.deploy.ContainerSummary
import dev.anthracite.remote.deploy.DeployClient
import dev.anthracite.remote.deploy.DeployCommands
import dev.anthracite.remote.deploy.KubePod
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.host.LogRedactor
import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.ui.Row
import dev.anthracite.remote.ui.RowAdapter
import dev.anthracite.remote.ui.addActionButton
import dev.anthracite.remote.ui.choose
import dev.anthracite.remote.ui.confirm
import dev.anthracite.remote.ui.promptText
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.launch

/**
 * Read-mostly container and Kubernetes views.
 *
 * What this screen can do is limited on purpose: start, stop or restart a
 * container that the host reported, read logs, and delete exactly one pod. It
 * cannot create containers, forward the Docker socket, or run privileged work.
 * The Docker socket is root-equivalent, and the UI says so instead of hiding it.
 */
class DeployActivity : AppCompatActivity() {
    private enum class Mode { CONTAINERS, PODS }

    private lateinit var adapter: RowAdapter
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: View
    private lateinit var actions: LinearLayout

    private lateinit var host: HostProfile
    private var runtime = DeployCommands.Runtime.DOCKER
    private var mode = Mode.CONTAINERS
    private var containers: List<ContainerSummary> = emptyList()
    private var pods: List<KubePod> = emptyList()
    private var kubeContext = ""
    private var namespace = "default"
    private var canDeletePods: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        adapter = RowAdapter(this)
        subtitle = findViewById(R.id.section_subtitle)
        emptyView = findViewById(R.id.section_empty)
        statusView = findViewById(R.id.section_status)
        progress = findViewById(R.id.section_progress)
        actions = findViewById(R.id.section_actions)
        findViewById<MaterialToolbar>(R.id.section_toolbar).apply {
            title = getString(R.string.deploy_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<ListView>(R.id.section_list).apply {
            adapter = this@DeployActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onRow(position) }
            setOnItemLongClickListener { _, _, position, _ ->
                onRow(position)
                true
            }
        }
        buildActions()

        val store = Workspace.store(this)
        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = (runCatching { hostId?.let(store::find) }.getOrNull() ?: Workspace.profile)
            ?: run {
                Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        subtitle.text = getString(R.string.deploy_subtitle, host.displayLabel)
        ensureConnected()
    }

    private fun ensureConnected() {
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == host.id) {
            loadContainers()
            return
        }
        lifecycleScope.launch {
            val error = connectWorkspace(this@DeployActivity, host)
            if (error != null) showStatus(error) else loadContainers()
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        addActionButton(this, actions, getString(R.string.refresh), getString(R.string.refresh)) {
            if (mode == Mode.CONTAINERS) loadContainers() else loadPods()
        }
        addActionButton(this, actions, getString(R.string.deploy_runtime), getString(R.string.deploy_runtime)) {
            val options = listOf(
                getString(R.string.deploy_docker, DeployCommands.Runtime.DOCKER.warning),
                getString(R.string.deploy_podman, DeployCommands.Runtime.PODMAN.warning),
            )
            choose(this, getString(R.string.deploy_runtime), options) { index ->
                runtime = if (index == 0) DeployCommands.Runtime.DOCKER else DeployCommands.Runtime.PODMAN
                mode = Mode.CONTAINERS
                loadContainers()
            }
        }
        addActionButton(this, actions, getString(R.string.deploy_kubernetes), getString(R.string.deploy_kubernetes)) {
            promptKubernetes()
        }
        addActionButton(this, actions, getString(R.string.deploy_containers), getString(R.string.deploy_containers)) {
            mode = Mode.CONTAINERS
            loadContainers()
        }
    }

    private fun promptKubernetes() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { DeployClient(Workspace.ssh).kubeContexts() } }
            progress.visibility = View.GONE
            result.onSuccess { (contexts, error) ->
                if (contexts.isEmpty()) {
                    showStatus(error ?: getString(R.string.deploy_no_kube_contexts))
                    return@onSuccess
                }
                choose(this@DeployActivity, getString(R.string.deploy_context), contexts) { index ->
                    kubeContext = contexts[index]
                    promptNamespace()
                }
            }.onFailure { showStatus(it.message ?: getString(R.string.deploy_kube_failed)) }
        }
    }

    private fun promptNamespace() {
        promptText(
            this,
            getString(R.string.deploy_namespace),
            getString(R.string.deploy_namespace_detail, kubeContext),
            namespace,
        ) { value ->
            val candidate = value.trim()
            if (!PosixShell.isKubernetesName(candidate)) {
                showStatus(getString(R.string.deploy_namespace_invalid))
            } else {
                namespace = candidate
                mode = Mode.PODS
                loadPods()
            }
        }
    }

    private fun loadContainers() {
        mode = Mode.CONTAINERS
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val client = DeployClient(Workspace.ssh)
                    if (!client.dockerAvailable(runtime)) {
                        throw IllegalStateException(
                            getString(R.string.deploy_runtime_unavailable, runtime.label),
                        )
                    }
                    client.containers(runtime)
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { (observed, unparsed) ->
                containers = observed
                pods = emptyList()
                adapter.submit(
                    observed.map { container ->
                        Row(
                            title = container.name.ifBlank { container.shortId },
                            subtitle = buildString {
                                append(container.image)
                                append(" · ").append(container.state)
                                if (container.ports.isNotBlank()) append(" · ").append(container.ports)
                                append("\n").append(container.status)
                            },
                            tag = container,
                        )
                    },
                )
                emptyView.visibility = if (observed.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = getString(R.string.deploy_no_containers, runtime.label)
                subtitle.text = getString(R.string.deploy_subtitle_runtime, host.displayLabel, runtime.label)
                showStatus(
                    buildString {
                        append(getString(R.string.deploy_container_count, observed.size))
                        if (unparsed > 0) {
                            append(" · ").append(getString(R.string.deploy_unparsed, unparsed))
                        }
                        append("\n").append(runtime.warning)
                    },
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.deploy_list_failed)) }
        }
    }

    private fun loadPods() {
        mode = Mode.PODS
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val client = DeployClient(Workspace.ssh)
                    val allowed = client.canDeletePods(kubeContext, namespace)
                    client.pods(kubeContext, namespace) to allowed
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { (pair, allowed) ->
                canDeletePods = allowed
                val (observed, error) = pair
                pods = observed
                containers = emptyList()
                adapter.submit(
                    observed.map { pod ->
                        Row(
                            title = pod.name,
                            subtitle = buildString {
                                append(pod.phase)
                                append(" · ").append(pod.readyContainers).append('/')
                                    .append(pod.totalContainers).append(" ready")
                                append(" · ").append(getString(R.string.deploy_restarts, pod.restarts))
                                pod.node?.let { append(" · ").append(it) }
                            },
                            tag = pod,
                        )
                    },
                )
                emptyView.visibility = if (observed.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = error ?: getString(R.string.deploy_no_pods)
                subtitle.text = getString(R.string.deploy_subtitle_kube, host.displayLabel, kubeContext, namespace)
                showStatus(
                    buildString {
                        append(getString(R.string.deploy_pod_count, observed.size))
                        if (allowed == false) {
                            append("\n").append(getString(R.string.deploy_no_delete_permission))
                        }
                    },
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.deploy_kube_failed)) }
        }
    }

    private fun onRow(position: Int) {
        if (mode == Mode.CONTAINERS) {
            val container = containers.getOrNull(position) ?: return
            showContainerOptions(container)
        } else {
            val pod = pods.getOrNull(position) ?: return
            showPodOptions(pod)
        }
    }

    private fun showContainerOptions(container: ContainerSummary) {
        val labels = listOf(
            getString(R.string.deploy_logs),
            getString(R.string.deploy_start),
            getString(R.string.deploy_stop),
            getString(R.string.deploy_restart),
        )
        choose(this, container.name, labels) { index ->
            when (index) {
                0 -> showLogs(container)
                1 -> containerAction(container, DeployCommands.Action.START)
                2 -> confirm(
                    this,
                    getString(R.string.deploy_stop),
                    getString(R.string.deploy_stop_detail, container.name),
                    getString(R.string.deploy_stop),
                ) { containerAction(container, DeployCommands.Action.STOP) }
                else -> confirm(
                    this,
                    getString(R.string.deploy_restart),
                    getString(R.string.deploy_restart_detail, container.name),
                    getString(R.string.restart),
                ) { containerAction(container, DeployCommands.Action.RESTART) }
            }
        }
    }

    private fun containerAction(container: ContainerSummary, action: DeployCommands.Action) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io { DeployClient(Workspace.ssh).action(runtime, container.id, action) }
            }
            progress.visibility = View.GONE
            result.onSuccess { exec ->
                showStatus(
                    if (exec.exitCode == 0) {
                        getString(R.string.deploy_action_done, action.label, container.name)
                    } else {
                        getString(R.string.deploy_action_failed, action.label, exec.exitCode, exec.text.take(2000))
                    },
                )
                loadContainers()
            }.onFailure { showStatus(it.message ?: getString(R.string.deploy_action_failed, action.label, -1, "")) }
        }
    }

    private fun showLogs(container: ContainerSummary) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { DeployClient(Workspace.ssh).logs(runtime, container.id) } }
            progress.visibility = View.GONE
            result.onSuccess { text -> showText(container.name, text) }
                .onFailure { showStatus(it.message ?: getString(R.string.deploy_logs_failed)) }
        }
    }

    private fun showPodOptions(pod: KubePod) {
        val labels = listOf(
            getString(R.string.deploy_logs),
            getString(R.string.deploy_describe),
            getString(R.string.deploy_delete_pod),
        )
        choose(this, pod.name, labels) { index ->
            when (index) {
                0 -> podLogs(pod)
                1 -> podDescribe(pod)
                else -> confirm(
                    this,
                    getString(R.string.deploy_delete_pod),
                    getString(R.string.deploy_delete_pod_detail, pod.name, namespace),
                    getString(R.string.deploy_delete_pod),
                    destructive = true,
                ) { deletePod(pod) }
            }
        }
    }

    private fun podLogs(pod: KubePod) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io { DeployClient(Workspace.ssh).podLogs(kubeContext, namespace, pod.name) }
            }
            progress.visibility = View.GONE
            result.onSuccess { showText(pod.name, it) }
                .onFailure { showStatus(it.message ?: getString(R.string.deploy_logs_failed)) }
        }
    }

    private fun podDescribe(pod: KubePod) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io { DeployClient(Workspace.ssh).describePod(kubeContext, namespace, pod.name) }
            }
            progress.visibility = View.GONE
            result.onSuccess { showText(pod.name, it) }
                .onFailure { showStatus(it.message ?: getString(R.string.deploy_kube_failed)) }
        }
    }

    private fun deletePod(pod: KubePod) {
        if (canDeletePods == false) {
            showStatus(getString(R.string.deploy_no_delete_permission))
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io { DeployClient(Workspace.ssh).deletePod(kubeContext, namespace, pod.name) }
            }
            progress.visibility = View.GONE
            result.onSuccess { exec ->
                showStatus(
                    if (exec.exitCode == 0) {
                        getString(R.string.deploy_delete_requested, pod.name)
                    } else {
                        getString(R.string.deploy_action_failed, getString(R.string.deploy_delete_pod), exec.exitCode, exec.text.take(2000))
                    },
                )
                loadPods()
            }.onFailure { showStatus(it.message ?: getString(R.string.deploy_kube_failed)) }
        }
    }

    private fun showText(title: String, text: String) {
        val redacted = LogRedactor.redact(text)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                redacted.take(LOG_CHARS).ifBlank { getString(R.string.deploy_logs_empty) },
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showStatus(message: String) {
        statusView.text = LogRedactor.redact(message)
        runCatching { statusView.announceForAccessibility(message) }
    }

    private companion object {
        const val LOG_CHARS = 40_000
    }
}
