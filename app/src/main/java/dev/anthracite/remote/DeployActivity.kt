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
import dev.anthracite.remote.deploy.DeployCreator
import dev.anthracite.remote.deploy.DeployOutcome
import dev.anthracite.remote.deploy.DeployPlan
import dev.anthracite.remote.deploy.DeployPlanReview
import dev.anthracite.remote.deploy.DeployPlans
import dev.anthracite.remote.deploy.DeployWizard
import dev.anthracite.remote.deploy.KubePod
import dev.anthracite.remote.deploy.SshShell
import dev.anthracite.remote.deploy.WorkloadKind
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
    private var creator: DeployCreator? = null
    private var created: DeployPlan? = null
    private var wizardImage = ""
    private var wizardPorts = ""
    private var wizardVolumes = ""
    private var wizardEnv = ""
    private var wizardDirectory = ""
    private var wizardExecutable = ""
    private var wizardArguments = ""
    private var wizardReplicas = ""
    private var wizardContainerPort = ""
    private var wizardKind = WorkloadKind.DEPLOYMENT
    private var wizard: DeployWizard? = null

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
        action(R.string.deploy_create) { newDeployment() }
        if (created != null) {
            action(R.string.deploy_created_inspect) { inspectCreated() }
            action(R.string.deploy_created_remove) { removeCreated() }
        }
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

    /**
     * Creation always runs through a [DeployPlan]: the exact commands are shown
     * before anything executes, and the resource name is generated here so it is
     * always one this app may later inspect and remove.
     */
    private fun newDeployment() {
        val options = listOf(
            getString(R.string.deploy_create_container),
            getString(R.string.deploy_create_workload),
            getString(R.string.deploy_create_native),
        )
        choose(this, getString(R.string.deploy_create), options) { index ->
            when (index) {
                0 -> {
                    wizard = DeployWizard()
                    wizardImage = ""
                    wizardPorts = ""
                    wizardVolumes = ""
                    wizardEnv = ""
                    promptContainerImage()
                }
                1 -> {
                    wizard = DeployWizard()
                    promptWorkloadKind()
                }
                else -> {
                    wizardDirectory = ""
                    wizardExecutable = ""
                    wizardArguments = ""
                    promptNativeDirectory()
                }
            }
        }
    }

    private fun promptContainerImage() {
        promptText(
            this,
            getString(R.string.deploy_image),
            getString(R.string.deploy_image_hint),
            initial = "nginx:1.27",
        ) { value ->
            wizardImage = value.trim()
            if (wizardImage.isEmpty()) return@promptText
            promptContainerPorts()
        }
    }

    private fun promptContainerPorts() {
        promptText(this, getString(R.string.deploy_ports), getString(R.string.deploy_ports_hint)) { value ->
            wizardPorts = value
            promptContainerVolumes()
        }
    }

    private fun promptContainerVolumes() {
        promptText(this, getString(R.string.deploy_volumes), getString(R.string.deploy_volumes_hint)) { value ->
            wizardVolumes = value
            promptContainerEnv()
        }
    }

    private fun promptContainerEnv() {
        promptText(this, getString(R.string.deploy_env), getString(R.string.deploy_env_hint)) { value ->
            wizardEnv = value
            val form = wizard ?: DeployWizard().also { wizard = it }
            val plan = runCatching {
                form.container(
                    runtime = runtime,
                    image = wizardImage,
                    ports = wizardPorts,
                    volumes = wizardVolumes,
                    environment = wizardEnv,
                )
            }.getOrElse {
                setStatus(it.message ?: getString(R.string.deploy_create_invalid))
                return@promptText
            }
            confirmPlan(plan)
        }
    }

    private fun promptWorkloadKind() {
        val kinds = WorkloadKind.values().toList()
        choose(this, getString(R.string.deploy_workload_kind), kinds.map { it.apiValue }) { index ->
            wizardKind = kinds.getOrElse(index) { WorkloadKind.DEPLOYMENT }
            promptWorkloadImage()
        }
    }

    /**
     * The manifest is generated from these three fields, never pasted. The name
     * exists before the first prompt, so every hint and the review screen name the
     * same object that will be applied.
     */
    private fun promptWorkloadImage() {
        val form = wizard ?: return
        promptText(
            this,
            getString(R.string.deploy_workload_image),
            getString(R.string.deploy_workload_image_hint, form.name, wizardKind.apiValue),
            initial = "nginx:1.27",
        ) { value ->
            wizardImage = value.trim()
            if (wizardImage.isEmpty()) return@promptText
            promptWorkloadReplicas()
        }
    }

    private fun promptWorkloadReplicas() {
        promptText(
            this,
            getString(R.string.deploy_workload_replicas),
            getString(R.string.deploy_workload_replicas_hint, DeployPlans.MAX_REPLICAS),
            initial = "1",
        ) { value ->
            wizardReplicas = value
            promptWorkloadContainerPort()
        }
    }

    private fun promptWorkloadContainerPort() {
        val active = kubeContext
        if (active == null) {
            setStatus(getString(R.string.deploy_no_context))
            chooseContext()
            return
        }
        promptText(
            this,
            getString(R.string.deploy_workload_port),
            getString(R.string.deploy_workload_port_hint),
            initial = "8080",
        ) { value ->
            wizardContainerPort = value
            val form = wizard ?: DeployWizard().also { wizard = it }
            val plan = runCatching {
                form.workload(
                    kind = wizardKind,
                    context = active,
                    namespace = namespace,
                    image = wizardImage,
                    replicas = wizardReplicas,
                    containerPort = wizardContainerPort,
                )
            }.getOrElse {
                setStatus(it.message ?: getString(R.string.deploy_create_invalid))
                return@promptText
            }
            confirmPlan(plan)
        }
    }

    private fun promptNativeDirectory() {
        promptText(
            this,
            getString(R.string.deploy_native_directory),
            getString(R.string.deploy_native_directory_hint),
            initial = "~/",
        ) { value ->
            wizardDirectory = value.trim()
            if (wizardDirectory.isEmpty() || wizardDirectory == "~/") return@promptText
            promptNativeExecutable()
        }
    }

    private fun promptNativeExecutable() {
        promptText(
            this,
            getString(R.string.deploy_native_executable),
            getString(R.string.deploy_native_executable_hint),
        ) { value ->
            wizardExecutable = value.trim()
            if (wizardExecutable.isEmpty()) return@promptText
            promptNativeArguments()
        }
    }

    private fun promptNativeArguments() {
        promptText(
            this,
            getString(R.string.deploy_native_arguments),
            getString(R.string.deploy_native_arguments_hint),
        ) { value ->
            wizardArguments = value
            val plan = runCatching {
                DeployPlan.Native(
                    name = DeployPlans.newName(),
                    workingDirectory = wizardDirectory,
                    executable = wizardExecutable,
                    arguments = DeployPlans.parseArguments(wizardArguments),
                )
            }.getOrElse {
                setStatus(it.message ?: getString(R.string.deploy_create_invalid))
                return@promptText
            }
            confirmPlan(plan)
        }
    }

    private fun confirmPlan(plan: DeployPlan) {
        val detail = DeployPlanReview.detail(
            plan = plan,
            review = getString(R.string.deploy_plan_review, plan.name),
            manifestHeader = getString(R.string.deploy_plan_manifest),
            environmentHeader = getString(R.string.deploy_plan_environment),
        )
        confirm(
            this,
            getString(R.string.deploy_plan_title),
            detail,
            getString(R.string.deploy_plan_run),
        ) { runPlan(plan) }
    }

    private fun runPlan(plan: DeployPlan) {
        val deploy = creator ?: DeployCreator(SshShell(Workspace.ssh)).also { creator = it }
        if (busy) return
        setBusy(true)
        setStatus(getString(R.string.deploy_plan_running))
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    when (plan) {
                        is DeployPlan.Container -> {
                            val pull = deploy.containerPull(plan)
                            DeployOutcome(if (pull.exitCode != 0) pull else deploy.containerRun(plan))
                        }
                        is DeployPlan.Workload -> deploy.workloadApply(plan)
                        is DeployPlan.Native -> DeployOutcome(deploy.nativeStart(plan))
                    }
                }
            }
            setBusy(false)
            result.onSuccess { outcome ->
                val exec = outcome.result
                if (exec.exitCode == 0 && plan is DeployPlan.Workload) {
                    // Only an object whose identity the cluster confirmed may be
                    // rolled back or deleted later: no UID, no cleanup offered.
                    val uid = outcome.appliedUid
                    if (uid == null) {
                        created = null
                        setStatus(getString(R.string.deploy_created_unverified, plan.name))
                        buildActions()
                        refresh()
                        return@onSuccess
                    }
                    val confirmed = plan.withAppliedUid(uid)
                    created = confirmed
                    setStatus(getString(R.string.deploy_created, plan.name))
                    buildActions()
                    reportRollout(deploy, confirmed)
                    return@onSuccess
                }
                if (exec.exitCode == 0) {
                    created = plan
                    setStatus(getString(R.string.deploy_created, plan.name))
                    buildActions()
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
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_create_invalid)) }
        }
    }

    private fun reportRollout(deploy: DeployCreator, plan: DeployPlan.Workload) {
        lifecycleScope.launch {
            val rollout = runCatching { io { deploy.workloadRollout(plan) } }.getOrNull()
            setStatus(
                if (rollout?.exitCode == 0) {
                    getString(R.string.deploy_created, plan.name)
                } else {
                    getString(
                        R.string.deploy_created_pending,
                        plan.name,
                        rollout?.text?.take(200).orEmpty(),
                    )
                },
            )
        }
    }

    private fun inspectCreated() {
        val plan = created ?: return
        val creatorClient = creator ?: DeployCreator(SshShell(Workspace.ssh)).also { creator = it }
        val deploy = client ?: DeployClient(Workspace.ssh).also { client = it }
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val text = runCatching {
                io {
                    when (plan) {
                        is DeployPlan.Container -> deploy.logs(runtime, plan.name)
                        is DeployPlan.Workload -> creatorClient.workloadLogs(plan)
                        is DeployPlan.Native -> creatorClient.nativeLogs(plan)
                    }
                }
            }
            setBusy(false)
            text.onSuccess { logs ->
                AlertDialog.Builder(this@DeployActivity)
                    .setTitle(plan.name)
                    .setMessage(logs.ifBlank { getString(R.string.deploy_no_logs) }.take(MAX_LOG_CHARS))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }.onFailure { setStatus(it.message ?: getString(R.string.deploy_load_failed)) }
        }
    }

    private fun removeCreated() {
        val plan = created ?: return
        val deploy = creator ?: DeployCreator(SshShell(Workspace.ssh)).also { creator = it }
        val warning = when (plan) {
            is DeployPlan.Container ->
                getString(R.string.deploy_remove_container_confirm, plan.name, runtime.label)
            is DeployPlan.Workload ->
                getString(R.string.deploy_remove_workload_confirm, plan.kind.apiValue, plan.name, plan.context, plan.namespace)
            is DeployPlan.Native ->
                getString(R.string.deploy_remove_native_confirm, plan.name)
        }
        confirm(this, getString(R.string.deploy_created_remove), warning, getString(R.string.deploy_created_remove), destructive = true) {
            if (busy) return@confirm
            setBusy(true)
            lifecycleScope.launch {
                val result = runCatching {
                    io {
                        when (plan) {
                            is DeployPlan.Container ->
                                deploy.removeOwnedContainer(runtime, plan.name)
                                    ?: return@io ExecResult(64, "", getString(R.string.deploy_remove_refused))
                            is DeployPlan.Workload -> deploy.workloadDelete(plan)
                            is DeployPlan.Native -> deploy.nativeStop(plan)
                        }
                    }
                }
                setBusy(false)
                result.onSuccess { exec ->
                    if (exec.exitCode == 0) {
                        created = null
                        setStatus(getString(R.string.deploy_removed, plan.name))
                        buildActions()
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
