package dev.anthracite.remote

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import dev.anthracite.remote.host.BootstrapRequest
import dev.anthracite.remote.host.CancelOutcome
import dev.anthracite.remote.host.HostFacts
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.host.HostProbe
import dev.anthracite.remote.host.JobState
import dev.anthracite.remote.host.JobStatus
import dev.anthracite.remote.host.LogRedactor
import dev.anthracite.remote.host.ManualInstallers
import dev.anthracite.remote.host.PlanAction
import dev.anthracite.remote.host.PlanDecision
import dev.anthracite.remote.host.ProvisionCatalog
import dev.anthracite.remote.host.ProvisionClient
import dev.anthracite.remote.host.ProvisionJobs
import dev.anthracite.remote.host.ProvisionPlan
import dev.anthracite.remote.host.ProvisionPlanner
import dev.anthracite.remote.terminal.TerminalActivity
import dev.anthracite.remote.ui.Row
import dev.anthracite.remote.ui.RowAdapter
import dev.anthracite.remote.ui.addActionButton
import dev.anthracite.remote.ui.confirm
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import dev.anthracite.remote.workspace.resolveHostOrFinish
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided provisioning.
 *
 * The phone never sends shell text to the host for execution. It sends a plan
 * header, a list of validated native package names, and the reviewed runner that
 * ships in `assets/host/`. The host runner re-verifies the plan digest and the
 * distribution before every run, and it never accepts a sudo password.
 *
 * A job outlives the app: it is started detached and its state lives on the host.
 * This screen only observes that state, and reports an interrupted job as
 * interrupted rather than as success.
 */
class BootstrapActivity : AppCompatActivity() {
    private lateinit var adapter: RowAdapter
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: View
    private lateinit var actions: LinearLayout

    private lateinit var host: HostProfile
    private var facts: HostFacts? = null
    private var plan: ProvisionPlan? = null
    private var jobId: String? = null
    private var polling = false
    private var selection: List<String> = ProvisionCatalog.profile("base")?.toolIds ?: emptyList()

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
            title = getString(R.string.bootstrap_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<ListView>(R.id.section_list).apply {
            adapter = this@BootstrapActivity.adapter
        }
        buildActions()

        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = resolveHostOrFinish(
            this,
            hostId,
            runCatching { Workspace.store(this).list() }.getOrDefault(emptyList()),
            Workspace.profile,
        ) ?: return
        jobId = savedInstanceState?.getString(KEY_JOB)
        subtitle.text = getString(R.string.bootstrap_subtitle, host.displayLabel)
        ensureConnected()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        jobId?.let { outState.putString(KEY_JOB, it) }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    private fun ensureConnected() {
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == host.id) {
            val existing = jobId
            if (existing != null) {
                pollJob(existing)
            } else {
                loadFacts(force = Workspace.lastFacts == null)
            }
            return
        }
        lifecycleScope.launch {
            val error = connectWorkspace(this@BootstrapActivity, host)
            if (error != null) showStatus(error) else loadFacts(force = true)
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        addActionButton(this, actions, getString(R.string.bootstrap_choose_profile), getString(R.string.bootstrap_choose_profile)) {
            chooseProfile()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_choose_tools), getString(R.string.bootstrap_choose_tools)) {
            chooseTools()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_replan), getString(R.string.bootstrap_replan)) {
            computePlan()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_show_commands), getString(R.string.bootstrap_show_commands)) {
            showCommands()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_start), getString(R.string.bootstrap_start)) {
            startPlan()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_cancel_job), getString(R.string.bootstrap_cancel_job)) {
            cancelJob()
        }
        addActionButton(this, actions, getString(R.string.bootstrap_open_terminal), getString(R.string.bootstrap_open_terminal)) {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        addActionButton(this, actions, getString(R.string.bootstrap_manual_tools), getString(R.string.bootstrap_manual_tools)) {
            showManualInstallers()
        }
    }

    /**
     * Coding-agent CLIs are installed by the user from the vendor's own
     * instructions. Anthracite never runs an unverified remote installer, and it
     * never inspects what the CLI does once it is running.
     */
    private fun showManualInstallers() {
        val entries = ManualInstallers.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.bootstrap_manual_tools)
            .setItems(entries.map { it.label }.toTypedArray()) { _, which ->
                val entry = entries.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle(entry.label)
                    .setMessage(
                        buildString {
                            append(entry.description).append("\n\n")
                            append(entry.instructions).append("\n\n")
                            append(getString(R.string.bootstrap_manual_because, entry.manualBecause))
                        },
                    )
                    .setPositiveButton(R.string.close, null)
                    .setNeutralButton(R.string.bootstrap_open_terminal) { _, _ ->
                        startActivity(Intent(this, TerminalActivity::class.java))
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadFacts(force: Boolean) {
        val cached = Workspace.lastFacts
        if (!force && cached != null) {
            facts = cached
            computePlan()
            return
        }
        if (!Workspace.ssh.isConnected()) {
            showStatus(getString(R.string.bootstrap_not_connected))
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val probe = HostProbe(Workspace.ssh, Workspace.script(this@BootstrapActivity, "anthracite-probe.sh"))
                    probe.run()
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { observed ->
                facts = observed
                Workspace.lastFacts = observed
                renderFacts(observed)
                computePlan()
            }.onFailure { showStatus(it.message ?: getString(R.string.bootstrap_probe_failed)) }
        }
    }

    private fun renderFacts(observed: HostFacts) {
        subtitle.text = getString(
            R.string.bootstrap_host_line,
            host.displayLabel,
            observed.displayName,
            observed.packageManager?.display ?: getString(R.string.bootstrap_no_manager),
        )
    }

    private fun chooseProfile() {
        val profiles = ProvisionCatalog.profiles
        val labels = profiles.map { profile -> "${profile.label} — ${profile.description}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.bootstrap_choose_profile)
            .setItems(labels.toTypedArray()) { _, which ->
                selection = profiles[which].toolIds
                computePlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseTools() {
        val tools = ProvisionCatalog.tools
        val labels = tools.map { tool -> "${tool.label} (${tool.category.label})" }.toTypedArray()
        val checked = tools.map { selection.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bootstrap_choose_tools)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.continue_action) { _, _ ->
                selection = tools.filterIndexed { index, _ -> checked[index] }.map { it.id }
                computePlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun computePlan() {
        val observed = facts ?: return
        val request = BootstrapRequest(
            toolIds = selection,
            profileId = ProvisionCatalog.profiles.firstOrNull { it.toolIds == selection }?.id,
            acknowledgeUnverified = false,
        )
        val computed = ProvisionPlanner.plan(observed, request)
        plan = computed
        val verdict = ProvisionPlanner.decide(computed, observed)
        val rows = buildList {
            add(
                Row(
                    getString(R.string.bootstrap_support_tier),
                    computed.supportTier.label,
                    body = computed.warnings.joinToString("\n"),
                ),
            )
            for (action in computed.actions) {
                when (action) {
                    is PlanAction.Install -> add(
                        Row(
                            action.tool.label,
                            getString(R.string.bootstrap_action_install, computed.manager?.display ?: ""),
                            body = action.packages.joinToString(" "),
                        ),
                    )
                    is PlanAction.AlreadyPresent -> add(
                        Row(action.tool.label, getString(R.string.bootstrap_action_present)),
                    )
                    is PlanAction.Unavailable -> add(
                        Row(action.toolId, getString(R.string.bootstrap_action_unavailable), body = action.reason),
                    )
                }
            }
            for (step in computed.steps) {
                add(Row(getString(R.string.bootstrap_step, step.index), step.packages.joinToString(" ")))
            }
        }
        adapter.submit(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = getString(R.string.bootstrap_empty)
        val blocked = when (verdict.decision) {
            PlanDecision.ALLOWED, PlanDecision.ALLOWED_UNVERIFIED -> false
            else -> true
        }
        showStatus(
            buildString {
                append(verdict.explanation)
                if (computed.steps.isEmpty()) {
                    append("\n").append(getString(R.string.bootstrap_no_steps))
                } else {
                    append("\n").append(
                        getString(
                            R.string.bootstrap_plan_summary,
                            computed.installActions.size,
                            computed.totalPackages,
                            computed.steps.size,
                        ),
                    )
                }
                if (blocked) append("\n").append(getString(R.string.bootstrap_start_blocked))
            },
        )
    }

    private fun showCommands() {
        val computed = plan ?: return
        val commands = ProvisionPlanner.previewCommands(computed)
        AlertDialog.Builder(this)
            .setTitle(R.string.bootstrap_show_commands)
            .setMessage(
                if (commands.isEmpty()) {
                    getString(R.string.bootstrap_no_steps)
                } else {
                    commands.joinToString("\n\n") + "\n\n" + getString(R.string.bootstrap_commands_note)
                },
            )
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.bootstrap_open_terminal) { _, _ ->
                startActivity(Intent(this, TerminalActivity::class.java))
            }
            .show()
    }

    private fun startPlan() {
        val computed = plan ?: run {
            showStatus(getString(R.string.bootstrap_plan_first))
            return
        }
        val observed = facts ?: return
        var request = computed.request
        var current = computed
        val verdict = ProvisionPlanner.decide(current, observed)
        when (verdict.decision) {
            PlanDecision.NEEDS_INTERACTIVE_PRIVILEGE -> {
                showStatus(verdict.explanation)
                showCommands()
                return
            }
            PlanDecision.ALLOWED_UNVERIFIED -> {
                confirm(
                    this,
                    getString(R.string.bootstrap_unverified_title),
                    getString(R.string.bootstrap_unverified_detail, observed.displayName),
                    getString(R.string.bootstrap_accept_risk),
                ) {
                    request = request.copy(acknowledgeUnverified = true)
                    current = ProvisionPlanner.plan(observed, request)
                    plan = current
                    confirmAndLaunch(current)
                }
                return
            }
            else -> if (!verdict.canLaunch) {
                showStatus(verdict.explanation)
                return
            }
        }
        confirmAndLaunch(current)
    }

    private fun confirmAndLaunch(computed: ProvisionPlan) {
        val commands = ProvisionPlanner.previewCommands(computed)
        val detail = buildString {
            append(getString(R.string.bootstrap_launch_detail, computed.installActions.size, computed.totalPackages))
            append("\n\n")
            append(commands.joinToString("\n"))
            append("\n\n")
            append(getString(R.string.bootstrap_launch_note))
        }
        confirm(
            this,
            getString(R.string.bootstrap_launch_title),
            detail,
            getString(R.string.bootstrap_launch_confirm),
        ) { launchJob(computed) }
    }

    private fun launchJob(computed: ProvisionPlan) {
        if (!Workspace.ssh.isConnected()) {
            showStatus(getString(R.string.bootstrap_not_connected))
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val created = runCatching {
                io {
                    val client = ProvisionClient(Workspace.ssh)
                    val id = ProvisionJobs.newJobId()
                    val runner = Workspace.script(this@BootstrapActivity, ProvisionJobs.RUNNER_FILE)
                    client.uploadJob(id, computed, runner)
                    val setsid = Workspace.ssh.exec("command -v setsid >/dev/null 2>&1").exitCode == 0
                    client.launch(id, setsid)
                    id
                }
            }
            progress.visibility = View.GONE
            created.onSuccess { id ->
                jobId = id
                showStatus(getString(R.string.bootstrap_job_started, id))
                pollJob(id)
            }.onFailure { showStatus(it.message ?: getString(R.string.bootstrap_job_failed)) }
        }
    }

    private fun pollJob(id: String) {
        if (polling) return
        polling = true
        lifecycleScope.launch {
            try {
                while (polling) {
                    val status = runCatching {
                        io {
                            ProvisionClient(Workspace.ssh)
                                .status(id, Workspace.script(this@BootstrapActivity, ProvisionJobs.STATUS_FILE))
                        }
                    }
                    status.onSuccess { renderJob(id, it) }
                        .onFailure { showStatus(it.message ?: getString(R.string.bootstrap_job_status_failed)) }
                    val state = status.getOrNull()?.state
                    if (state != null && state !in ACTIVE_STATES) return@launch
                    delay(POLL_MS)
                }
            } finally {
                polling = false
            }
        }
    }

    private fun renderJob(id: String, status: JobStatus) {
        val stateLabel = when (status.state) {
            JobState.PLANNED -> getString(R.string.job_planned)
            JobState.APPROVED -> getString(R.string.job_approved)
            JobState.QUEUED -> getString(R.string.job_queued)
            JobState.RUNNING -> getString(R.string.job_running)
            JobState.SUCCEEDED -> getString(R.string.job_succeeded)
            JobState.FAILED -> getString(R.string.job_failed)
            JobState.CANCELLED -> getString(R.string.job_cancelled)
            JobState.INTERRUPTED -> getString(R.string.job_interrupted)
            JobState.LOCKED -> getString(R.string.job_locked)
            JobState.UNKNOWN -> getString(R.string.job_unknown)
        }
        val rows = listOf(
            Row(stateLabel, getString(R.string.job_id, id)),
            Row(getString(R.string.job_step), status.step.ifBlank { getString(R.string.job_step_none) }),
            Row(
                getString(R.string.job_exit),
                if (status.exitCode < 0) getString(R.string.job_exit_none) else status.exitCode.toString(),
            ),
            Row(
                getString(R.string.job_log),
                getString(R.string.job_log_bytes, status.logBytes),
                body = LogRedactor.redact(status.logTail).ifBlank { getString(R.string.job_log_empty) },
            ),
        )
        adapter.submit(rows)
        emptyView.visibility = View.GONE
        showStatus(
            buildString {
                append(stateLabel)
                status.reason?.let { append(" · ").append(LogRedactor.redact(it)) }
                if (status.state in ACTIVE_STATES) {
                    append("\n").append(getString(R.string.job_running_note))
                }
                if (status.state == JobState.INTERRUPTED) {
                    append("\n").append(getString(R.string.job_interrupted_note))
                }
            },
        )
    }

    private fun cancelJob() {
        val id = jobId ?: run {
            showStatus(getString(R.string.bootstrap_no_job))
            return
        }
        confirm(
            this,
            getString(R.string.bootstrap_cancel_job),
            getString(R.string.bootstrap_cancel_detail, id),
            getString(R.string.bootstrap_cancel_confirm),
            destructive = true,
        ) {
            lifecycleScope.launch {
                val result = runCatching {
                    io {
                        ProvisionClient(Workspace.ssh)
                            .cancel(id, Workspace.script(this@BootstrapActivity, ProvisionJobs.CANCEL_FILE))
                    }
                }
                result.onSuccess { outcome ->
                    val message = when (outcome.outcome) {
                        CancelOutcome.SIGNAL_SENT -> getString(R.string.bootstrap_cancel_sent)
                        CancelOutcome.STILL_RUNNING -> getString(R.string.bootstrap_cancel_still_running)
                        CancelOutcome.CONFIRMED_STOPPED -> getString(R.string.bootstrap_cancel_stopped)
                        CancelOutcome.ALREADY_FINISHED -> getString(R.string.bootstrap_cancel_finished)
                        CancelOutcome.REFUSED_UNKNOWN_IDENTITY ->
                            getString(R.string.bootstrap_cancel_identity_unproven)
                        CancelOutcome.REFUSED_INVALID_JOB, CancelOutcome.REFUSED_NOT_OWNER ->
                            getString(R.string.bootstrap_cancel_refused)
                        CancelOutcome.SIGNAL_FAILED, CancelOutcome.UNKNOWN ->
                            getString(R.string.bootstrap_cancel_unconfirmed)
                    }
                    val reason = outcome.reason?.let { " (" + LogRedactor.redact(it) + ")" }.orEmpty()
                    showStatus(message + reason)
                }.onFailure { showStatus(it.message ?: getString(R.string.bootstrap_cancel_unconfirmed)) }
            }
        }
    }

    private fun showStatus(message: String) {
        statusView.text = LogRedactor.redact(message)
        runCatching { statusView.announceForAccessibility(message) }
    }

    private companion object {
        const val KEY_JOB = "job_id"
        const val POLL_MS = 2_000L
        val ACTIVE_STATES = setOf(JobState.APPROVED, JobState.QUEUED, JobState.RUNNING, JobState.PLANNED)
    }
}
