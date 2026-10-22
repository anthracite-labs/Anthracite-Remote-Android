package dev.anthracite.remote

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
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
 * Docker, Podman and Kubernetes — read-mostly.
 *
 * The screen can list what the host reports, show a bounded log, start/stop or
 * restart one container the host just listed, and delete one pod the host just
 * listed after an RBAC check. It cannot create containers, forward the Docker
 * socket, run privileged work or elevate. Docker socket access is root-equivalent
 * and the subtitle says so next to the data rather than in a help page.
 */
class DeployActivity : AppCompatActivity() {
    private lateinit var subtitle: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout

    private var host: HostProfile? = null
    private var client: DeployClient? = null
    private var adapter: RowAdapter? = null
    private var runtime: DeployCommands.Runtime = DeployCommands.Runtime.DOCKER
    private var mode: Mode = Mode.CONTAINERS
    private var containers: List<ContainerSummary> = emptyList()
    private var pods: List<KubePod> = emptyList()
    private var kubeContext: String? = null
    private var namespace: String = "default"
    private var canDelete = false
    private var busy = false

    private enum class Mode { CONTAINERS, PODS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        subtitle = findViewById(R.id.section_subtitle)
        list = findViewById(R.id.section_list)
        empty = findViewById(R.id.section_empty)
        status = findViewById(R.id.section_status)
        progress = findViewById(R.id.section_progress)
        actions = findViewById(R.id.section_actions)
        val toolbar = findViewById<MaterialToolbar>(R.id.section_toolbar)
        toolbar.setTitle(R.string.deploy_title)
        toolbar.setNavigationOnClickListener { finish() }

        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = runCatching { hostId?.let(Workspace.store(this)::find) }.getOrNull() ?: Workspace.profile
        runtime = if (savedInstanceState?.getString(STATE_RUNTIME) == DeployCommands.Runtime.PODMAN.name) {
            DeployCommands.Runtime.PODMAN
        } else {
            DeployCommands.Runtime.DOCKER
        }
        mode = if (savedInstanceState?.getString(STATE_MODE) == Mode.PODS.name) Mode.PODS else Mode.CONTAINERS
        namespace = savedInstanceState?.getString(STATE_NAMESPACE) ?: "default"
        kubeContext = savedInstanceState?.getString(STATE_CONTEXT)

        list.setOnItemClickListener { _, _, index, _ -> open(index) }
        list.setOnItemLongClickListener { _, _, index, _ ->
            actionsFor(index)
            true
        }
        buildActions()
        connect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RUNTIME, runtime.name)
        outState.putString(STATE_MODE, mode.name)
        outState.putString(STATE_NAMESPACE, namespace)
        outState.putString(STATE_CONTEXT, kubeContext)
    }

    private fun connect() {
        val target = host ?: run {
            Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        subtitle.text = getString(R.string.deploy_subtitle, target.displayLabel, runtime.warning)
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == target.id) {
            refresh()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val error = connectWorkspace(this@DeployActivity, target)
            setBusy(false)
            if (error != null) setStatus(error) else refresh()
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        action(R.string.deploy_refresh) { refresh() }
        action(R.string.deploy_runtime) { chooseRuntime() }
        action(R.string.deploy_context) { chooseContext() }
        addActionButton(
            this,
            actions,
            getString(R.string.deploy_mode_pods),
            getString(R.string.deploy_mode_pods),
        ) {
            mode = Mode.PODS
            if (kubeContext == null) {
                chooseContext()
            } else {
                refresh()
            }
        }
        addActionButton(
            this,
            actions,
            getString(R.string.deploy_mode_containers),
            getString(R.string.deploy_mode_containers),
        ) {
            mode = Mode.CONTAINERS
            refresh()
        }
    }

    private fun action(label: Int, onClick: () -> Unit) {
        addActionButton(this, actions, getString(label), getString(label)) { onClick() }
    }

    private fun chooseRuntime() {
        choose(
            this,
            getString(R.string.deploy_runtime),
            DeployCommands.Runtime.values().map { it.label },
        ) { index ->
            runtime = DeployCommands.Runtime.values().getOrElse(index) { DeployCommands.Runtime.DOCKER }
            mode = Mode.CONTAINERS
            subtitle.text = getString(R.string.deploy_subtitle, host?.displayLabel ?: "", runtime.warning)
            refresh()
        }
    }

    private fun chooseContext() {
        val deploy = client ?: DeployClient(Workspace.ssh).also { client = it }
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { deploy.kubeContexts() } }
            setBusy(false)
            result.onSuccess { (contexts, current) ->
                if (contexts.isEmpty()) {
                    setStatus(getString(R.string.deploy_no_contexts))
                    return@onSuccess
                }
                val selected = kubeContext ?: current
                val labels = contexts.map { if (it == selected) "$it (current)" else it }
                choose(this@DeployActivity, getString(R.string.deploy_context), labels) { index ->
                    kubeContext = contexts.getOrNull(index) ?: return@choose
                    promptText(
                        this@DeployActivity,
                        getString(R.string.deploy_namespace),
                        getString(R.string.deploy_namespace_hint),
                        initial = namespace,
                    ) { value ->
                        val cleaned = value.trim()
                        if (!cleaned.matches(Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?"))) {
                            setStatus(getString(R.string.deploy_namespace_invalid))
                            return@promptText
                        }
                        namespace = cleaned
                        mode = Mode.PODS
                        checkDeletePermission()
                    }
                }
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_context_failed)) }
        }
    }

    private fun checkDeletePermission() {
        val deploy = client ?: return
        val active = kubeContext ?: return
        lifecycleScope.launch {
            canDelete = runCatching { io { deploy.canDeletePods(active, namespace) } }.getOrNull() == true
            refresh()
        }
    }

    private fun refresh() {
        if (busy) return
        val deploy = client ?: DeployClient(Workspace.ssh).also { client = it }
        if (!Workspace.ssh.isConnected()) {
            connect()
            return
        }
        val activeMode = mode
        val activeContext = kubeContext
        setBusy(true)
        setStatus(getString(R.string.deploy_loading))
        lifecycleScope.launch {
            if (activeMode == Mode.PODS) {
                if (activeContext == null) {
                    setBusy(false)
                    setStatus(getString(R.string.deploy_no_context))
                    return@launch
                }
                val result = runCatching { io { deploy.pods(activeContext, namespace) } }
                setBusy(false)
                result.onSuccess { (loaded, warning) ->
                    pods = loaded
                    containers = emptyList()
                    renderPods(warning)
                }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
            } else {
                val result = runCatching { io { deploy.containers(runtime) } }
                setBusy(false)
                result.onSuccess { (loaded, unparsed) ->
                    containers = loaded
                    pods = emptyList()
                    renderContainers(unparsed)
                }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
            }
        }
    }

    private fun renderContainers(unparsed: Int) {
        val rows = containers.map { container ->
            Row(
                title = container.name,
                subtitle = "${container.image} · ${container.state} · ${container.status}",
                body = container.ports,
                tag = container,
            )
        }
        submit(rows)
        empty.text = getString(R.string.deploy_no_containers, runtime.label)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        setStatus(
            if (unparsed == 0) {
                getString(R.string.deploy_containers_loaded, containers.size)
            } else {
                getString(R.string.deploy_containers_partial, containers.size, unparsed)
            },
        )
    }

    private fun renderPods(warning: String?) {
        val rows = pods.map { pod ->
            Row(
                title = pod.name,
                subtitle = "${pod.phase} · ${pod.readyContainers}/${pod.totalContainers} ready · ${pod.restarts} restarts",
                body = pod.node.orEmpty(),
                tag = pod,
            )
        }
        submit(rows)
        empty.text = getString(R.string.deploy_no_pods, namespace)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        setStatus(
            warning ?: getString(
                R.string.deploy_pods_loaded,
                pods.size,
                namespace,
                if (canDelete) getString(R.string.deploy_delete_allowed) else getString(R.string.deploy_delete_denied),
            ),
        )
    }

    private fun submit(rows: List<Row>) {
        val current = adapter ?: RowAdapter(this).also {
            adapter = it
            list.adapter = it
        }
        current.submit(rows)
    }

    private fun open(index: Int) {
        val row = adapter?.getItem(index) as? Row ?: return
        when (val tag = row.tag) {
            is ContainerSummary -> showLogs(tag.name, containerLogs(tag))
            is KubePod -> showLogs(tag.name, podLogs(tag))
            else -> Unit
        }
    }

    private fun containerLogs(container: ContainerSummary): () -> String {
        val deploy = client ?: return { "" }
        return { deploy.logs(runtime, container.id) }
    }

    private fun podLogs(pod: KubePod): () -> String {
        val deploy = client ?: return { "" }
        val active = kubeContext ?: return { "" }
        return { deploy.podLogs(active, namespace, pod.name) }
    }

    private fun showLogs(title: String, loader: () -> String) {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { loader() } }
            setBusy(false)
            result.onSuccess { text ->
                AlertDialog.Builder(this@DeployActivity)
                    .setTitle(title)
                    .setMessage(text.ifBlank { getString(R.string.deploy_no_logs) }.take(MAX_LOG_CHARS))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
        }
    }

    private fun actionsFor(index: Int) {
        val row = adapter?.getItem(index) as? Row ?: return
        when (val tag = row.tag) {
            is ContainerSummary -> containerActions(tag)
            is KubePod -> podActions(tag)
            else -> Unit
        }
    }

    private fun containerActions(container: ContainerSummary) {
        val labels = listOf(
            getString(R.string.deploy_action_logs),
            getString(R.string.deploy_action_start),
            getString(R.string.deploy_action_stop),
            getString(R.string.deploy_action_restart),
        )
        choose(this, container.name, labels) { index ->
            if (index == 0) {
                showLogs(container.name, containerLogs(container))
                return@choose
            }
            val action = when (index) {
                1 -> DeployCommands.Action.START
                2 -> DeployCommands.Action.STOP
                else -> DeployCommands.Action.RESTART
            }
            confirm(
                this,
                action.label,
                getString(R.string.deploy_action_confirm, action.label, container.name),
                action.label,
                destructive = action == DeployCommands.Action.STOP,
            ) {
                runAction { deploy ->
                    deploy.action(runtime, container.id, action)
                }
            }
        }
    }

    private fun podActions(pod: KubePod) {
        val active = kubeContext
        val labels = buildList {
            add(getString(R.string.deploy_action_logs))
            add(getString(R.string.deploy_action_describe))
            if (canDelete && active != null) add(getString(R.string.deploy_action_delete_pod))
        }
        choose(this, pod.name, labels) { index ->
            when (labels.getOrNull(index)) {
                getString(R.string.deploy_action_logs) -> showLogs(pod.name, podLogs(pod))
                getString(R.string.deploy_action_describe) -> describePod(pod)
                else -> if (active != null) deletePod(pod, active)
            }
        }
    }

    private fun describePod(pod: KubePod) {
        val deploy = client ?: return
        val active = kubeContext ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { deploy.describePod(active, namespace, pod.name) } }
            setBusy(false)
            result.onSuccess { text ->
                AlertDialog.Builder(this@DeployActivity)
                    .setTitle(pod.name)
                    .setMessage(text.ifBlank { getString(R.string.deploy_no_logs) }.take(MAX_LOG_CHARS))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
        }
    }

    private fun deletePod(pod: KubePod, active: String) {
        confirm(
            this,
            getString(R.string.deploy_action_delete_pod),
            getString(R.string.deploy_delete_pod_confirm, pod.name, namespace, active),
            getString(R.string.deploy_action_delete_pod),
            destructive = true,
        ) {
            runAction { deploy -> deploy.deletePod(active, namespace, pod.name) }
        }
    }

    private fun runAction(block: (DeployClient) -> ExecResult) {
        val deploy = client ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { block(deploy) } }
            setBusy(false)
            result.onSuccess { exec ->
                if (exec.exitCode == 0) {
                    setStatus(getString(R.string.deploy_action_done))
                    refresh()
                } else {
                    setStatus(
                        getString(
                            R.string.deploy_action_failed,
                            exec.exitCode,
                            exec.text.take(400).ifBlank { getString(R.string.deploy_action_silent) },
                        ),
                    )
                }
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private companion object {
        const val STATE_RUNTIME = "deploy_runtime"
        const val STATE_MODE = "deploy_mode"
        const val STATE_NAMESPACE = "deploy_namespace"
        const val STATE_CONTEXT = "deploy_context"
        const val MAX_LOG_CHARS = 60_000
    }
}
