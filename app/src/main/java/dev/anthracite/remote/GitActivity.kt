package dev.anthracite.remote

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import dev.anthracite.remote.gitx.GitClient
import dev.anthracite.remote.gitx.GitStatus
import dev.anthracite.remote.gitx.GitStatusEntry
import dev.anthracite.remote.host.HostProfile
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
 * Host Git for one directory.
 *
 * Every command is `git -C <quoted path>` built by `GitCommands`; the phone never
 * passes a path or a ref into a shell unquoted. Force push is only ever
 * `--force-with-lease`, and pull is `--ff-only`, so an accidental history rewrite
 * cannot come from this screen.
 */
class GitActivity : AppCompatActivity() {
    private lateinit var adapter: RowAdapter
    private lateinit var hostView: TextView
    private lateinit var branchView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: View
    private lateinit var host: HostProfile

    private var directory = ""
    private var status: GitStatus? = null
    private var entries: List<GitStatusEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        adapter = RowAdapter(this)
        hostView = findViewById(R.id.section_subtitle)
        branchView = findViewById(R.id.section_empty)
        statusView = findViewById(R.id.section_status)
        progress = findViewById(R.id.section_progress)
        findViewById<MaterialToolbar>(R.id.section_toolbar).apply {
            title = getString(R.string.git_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<ListView>(R.id.section_list).apply {
            adapter = this@GitActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onEntry(entries[position]) }
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
        directory = Workspace.lastFacts?.workingDirectory?.takeIf { PosixShell.isQuotablePath(it) } ?: ""
        ensureConnected()
    }

    private fun ensureConnected() {
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == host.id) {
            if (directory.isBlank()) promptDirectory() else refresh()
            return
        }
        lifecycleScope.launch {
            val error = connectWorkspace(this@GitActivity, host)
            if (error != null) {
                showStatus(error)
            } else if (directory.isBlank()) {
                promptDirectory()
            } else {
                refresh()
            }
        }
    }

    private fun buildActions() {
        val row = findViewById<LinearLayout>(R.id.section_actions)
        row.removeAllViews()
        addActionButton(this, row, getString(R.string.git_directory), getString(R.string.git_directory)) {
            promptDirectory()
        }
        addActionButton(this, row, getString(R.string.git_refresh), getString(R.string.git_refresh)) { refresh() }
        addActionButton(this, row, getString(R.string.git_log), getString(R.string.git_log)) { showLog() }
        addActionButton(this, row, getString(R.string.git_branches), getString(R.string.git_branches)) {
            showBranches()
        }
        addActionButton(this, row, getString(R.string.git_commit), getString(R.string.git_commit)) { promptCommit() }
        addActionButton(this, row, getString(R.string.git_fetch), getString(R.string.git_fetch)) { runAction("fetch") }
        addActionButton(this, row, getString(R.string.git_pull), getString(R.string.git_pull)) { runAction("pull") }
        addActionButton(this, row, getString(R.string.git_push), getString(R.string.git_push)) {
            confirm(
                this,
                getString(R.string.git_push),
                getString(R.string.git_push_detail, directory),
                getString(R.string.git_push),
            ) { runAction("push") }
        }
    }

    private fun promptDirectory() {
        promptText(
            this,
            getString(R.string.git_directory),
            getString(R.string.git_directory_detail),
            directory.ifBlank { Workspace.lastFacts?.workingDirectory ?: "" },
        ) { value ->
            val path = value.trim()
            if (!PosixShell.isQuotablePath(path)) {
                showStatus(getString(R.string.git_directory_invalid))
            } else {
                directory = path
                refresh()
            }
        }
    }

    private fun refresh() {
        if (directory.isBlank()) {
            promptDirectory()
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val client = GitClient(Workspace.ssh)
                    if (!client.isRepository(directory)) {
                        throw IllegalStateException(getString(R.string.git_not_a_repository, directory))
                    }
                    client.topLevel(directory) ?: directory
                }
            }
            result.onSuccess { root ->
                directory = root
                loadStatus()
            }.onFailure {
                progress.visibility = View.GONE
                showStatus(it.message ?: getString(R.string.git_status_failed))
            }
        }
    }

    private fun loadStatus() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { GitClient(Workspace.ssh).status(directory) } }
            progress.visibility = View.GONE
            result.onSuccess { observed ->
                status = observed
                entries = observed.entries
                val branch = observed.branch
                hostView.text = getString(R.string.git_location, host.displayLabel, directory)
                branchView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                branchView.text = getString(R.string.git_clean)
                adapter.submit(entries.map(::rowFor))
                val branchLabel = when {
                    branch == null -> getString(R.string.git_branch_unknown)
                    branch.detached -> getString(R.string.git_detached, branch.head)
                    else -> buildString {
                        append(branch.head)
                        branch.upstream?.let { append(" → ").append(it) }
                        if (branch.ahead > 0 || branch.behind > 0) {
                            append("  ↑").append(branch.ahead).append(" ↓").append(branch.behind)
                        }
                    }
                }
                showStatus(
                    getString(
                        R.string.git_status_summary,
                        branchLabel,
                        observed.stagedCount,
                        observed.unstagedCount,
                        observed.untrackedCount,
                        observed.conflictCount,
                    ),
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.git_status_failed)) }
        }
    }

    private fun rowFor(entry: GitStatusEntry): Row {
        val state = when {
            entry.conflicted -> getString(R.string.git_conflict)
            entry.untracked -> getString(R.string.git_untracked)
            entry.staged && entry.hasWorktreeChange -> getString(R.string.git_staged_and_modified)
            entry.staged -> getString(R.string.git_staged)
            else -> getString(R.string.git_modified)
        }
        return Row(
            title = entry.path,
            subtitle = state + (entry.originalPath?.let { "  ← $it" } ?: ""),
            tag = entry,
        )
    }

    private fun onEntry(entry: GitStatusEntry) {
        val options = listOf(
            getString(R.string.git_view_diff),
            getString(R.string.git_stage),
            getString(R.string.git_unstage),
        )
        choose(this, entry.path, options) { index ->
            when (index) {
                0 -> showDiff(entry)
                1 -> runGit { it.stage(directory, entry.path) }
                else -> runGit { it.unstage(directory, entry.path) }
            }
        }
    }

    private fun showDiff(entry: GitStatusEntry) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io { GitClient(Workspace.ssh).diff(directory, staged = entry.staged, path = entry.path) }
            }
            progress.visibility = View.GONE
            result.onSuccess { diff ->
                androidx.appcompat.app.AlertDialog.Builder(this@GitActivity)
                    .setTitle(entry.path)
                    .setMessage(diff.take(DIFF_CHARS).ifBlank { getString(R.string.git_no_diff) })
                    .setPositiveButton(R.string.close, null)
                    .show()
            }.onFailure { showStatus(it.message ?: getString(R.string.git_diff_failed)) }
        }
    }

    private fun showLog() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { GitClient(Workspace.ssh).log(directory) } }
            progress.visibility = View.GONE
            result.onSuccess { commits ->
                entries = emptyList()
                adapter.submit(
                    commits.map { commit ->
                        Row(commit.subject, "${commit.oid.take(8)} · ${commit.author} · ${commit.relativeDate}")
                    },
                )
                branchView.visibility = if (commits.isEmpty()) View.VISIBLE else View.GONE
                branchView.text = getString(R.string.git_log_empty)
                showStatus(getString(R.string.git_log_summary, commits.size))
            }.onFailure { showStatus(it.message ?: getString(R.string.git_log_failed)) }
        }
    }

    private fun showBranches() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { GitClient(Workspace.ssh).branches(directory) } }
            progress.visibility = View.GONE
            result.onSuccess { refs ->
                if (refs.isEmpty()) {
                    showStatus(getString(R.string.git_branches_empty))
                    return@onSuccess
                }
                androidx.appcompat.app.AlertDialog.Builder(this@GitActivity)
                    .setTitle(R.string.git_branches)
                    .setItems(refs.map { ref -> (if (ref.current) "✓ " else "") + ref.name }.toTypedArray()) { _, which ->
                        val ref = refs[which]
                        if (!ref.current) {
                            confirm(
                                this@GitActivity,
                                getString(R.string.git_checkout),
                                getString(R.string.git_checkout_detail, ref.name),
                                getString(R.string.git_checkout),
                            ) { runGit { it.checkout(directory, ref.name) } }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }.onFailure { showStatus(it.message ?: getString(R.string.git_branches_failed)) }
        }
    }

    private fun promptCommit() {
        promptText(
            this,
            getString(R.string.git_commit),
            getString(R.string.git_commit_detail),
            "",
        ) { message ->
            if (message.isBlank()) {
                showStatus(getString(R.string.git_commit_empty))
            } else {
                runGit { it.commit(directory, message) }
            }
        }
    }

    private fun runAction(action: String) {
        runGit { client ->
            when (action) {
                "fetch" -> client.fetch(directory)
                "pull" -> client.pull(directory)
                else -> client.push(directory, force = false)
            }
        }
    }

    private fun runGit(block: (GitClient) -> ExecResult) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { block(GitClient(Workspace.ssh)) } }
            progress.visibility = View.GONE
            result.onSuccess { exec ->
                if (exec.exitCode == 0) {
                    showStatus(exec.text.take(4000).ifBlank { getString(R.string.git_done) })
                } else {
                    showStatus(getString(R.string.git_failed, exec.exitCode, exec.text.take(4000)))
                }
                loadStatus()
            }.onFailure { showStatus(it.message ?: getString(R.string.git_failed, -1, "")) }
        }
    }

    private fun showStatus(message: String) {
        statusView.text = message
        runCatching { statusView.announceForAccessibility(message) }
    }

    private companion object {
        const val DIFF_CHARS = 30_000
    }
}
