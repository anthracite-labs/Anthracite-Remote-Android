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
import dev.anthracite.remote.gitx.GitClient
import dev.anthracite.remote.gitx.GitStatus
import dev.anthracite.remote.gitx.GitStatusEntry
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
import dev.anthracite.remote.workspace.resolveHostOrFinish
import kotlinx.coroutines.launch

/**
 * Host Git.
 *
 * Host Git uses the server's own credentials and configuration, which is why it
 * is a separate screen from GitHub. Reads are cheap and always safe; every write
 * is a distinct confirmation, and force push can only be `--force-with-lease`
 * after a second, explicit choice. Pull is fast-forward-only: Anthracite never
 * creates a merge commit or resolves a conflict on the user's behalf.
 */
class GitActivity : AppCompatActivity() {
    private lateinit var subtitle: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout

    private var host: HostProfile? = null
    private var client: GitClient? = null
    private var directory: String = "/"
    private var state: GitStatus? = null
    private var busy = false
    private var adapter: RowAdapter? = null

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
        toolbar.setTitle(R.string.git_title)
        toolbar.setNavigationOnClickListener { finish() }

        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = resolveHostOrFinish(
            this,
            hostId,
            runCatching { Workspace.store(this).list() }.getOrDefault(emptyList()),
            Workspace.profile,
        ) ?: return
        directory = savedInstanceState?.getString(STATE_DIRECTORY)
            ?: Workspace.lastFacts?.workingDirectory?.takeIf { it.startsWith("/") }
            ?: "/"

        list.setOnItemClickListener { _, _, index, _ -> entryAt(index)?.let { showDiff(it) } }
        list.setOnItemLongClickListener { _, _, index, _ ->
            entryAt(index)?.let { entryActions(it) }
            true
        }
        buildActions()
        connect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DIRECTORY, directory)
    }

    private fun connect() {
        val target = host ?: run {
            Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        subtitle.text = target.displayLabel
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == target.id) {
            refresh()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val error = connectWorkspace(this@GitActivity, target)
            setBusy(false)
            if (error != null) setStatus(error) else refresh()
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        action(R.string.git_refresh) { refresh() }
        action(R.string.git_directory) { chooseDirectory() }
        action(R.string.git_diff) { diffAll(staged = false) }
        action(R.string.git_diff_staged) { diffAll(staged = true) }
        action(R.string.git_commit) { commit() }
        action(R.string.git_fetch) { runWrite(R.string.git_fetched) { client?.fetch(directory) } }
        action(R.string.git_pull) { pull() }
        action(R.string.git_push) { push() }
        action(R.string.git_branches) { branches() }
        action(R.string.git_log) { log() }
    }

    private fun action(label: Int, onClick: () -> Unit) {
        addActionButton(this, actions, getString(label), getString(label)) { onClick() }
    }

    private fun chooseDirectory() {
        promptText(
            this,
            getString(R.string.git_directory),
            getString(R.string.git_directory_hint),
            initial = directory,
        ) { value ->
            val cleaned = value.trim()
            if (!cleaned.startsWith("/") || cleaned.contains('\u0000')) {
                setStatus(getString(R.string.git_directory_invalid))
                return@promptText
            }
            directory = cleaned.trimEnd('/').ifEmpty { "/" }
            refresh()
        }
    }

    private fun refresh() {
        if (busy) return
        val git = client ?: GitClient(Workspace.ssh).also { client = it }
        if (!Workspace.ssh.isConnected()) {
            connect()
            return
        }
        setBusy(true)
        setStatus(getString(R.string.git_loading))
        lifecycleScope.launch {
            val result = runCatching {
                io { if (git.isRepository(directory)) git.status(directory) else null }
            }
            setBusy(false)
            result.onSuccess { parsed ->
                if (parsed == null) {
                    state = null
                    renderNotRepository()
                    return@onSuccess
                }
                state = parsed
                render(parsed)
            }.onFailure { setStatus(it.message ?: getString(R.string.git_load_failed)) }
        }
    }

    private fun renderNotRepository() {
        subtitle.text = getString(R.string.git_not_repository, directory)
        val current = adapter ?: RowAdapter(this).also {
            adapter = it
            list.adapter = it
        }
        current.submit(emptyList())
        empty.text = getString(R.string.git_not_repository_detail)
        empty.visibility = View.VISIBLE
        setStatus(getString(R.string.git_not_repository_hint))
    }

    private fun render(parsed: GitStatus) {
        val branch = parsed.branch
        subtitle.text = if (branch == null) {
            getString(R.string.git_detached_or_unknown, directory)
        } else {
            getString(
                R.string.git_status_summary,
                directory,
                branch.head,
                branch.ahead,
                branch.behind,
                parsed.entries.size,
            )
        }
        val current = adapter ?: RowAdapter(this).also {
            adapter = it
            list.adapter = it
        }
        val rows = parsed.entries.map { entry ->
            Row(
                title = entry.path,
                subtitle = entry.statusLabel + if (entry.originalPath != null) " from ${entry.originalPath}" else "",
                tag = entry,
            )
        }
        current.submit(rows)
        empty.text = getString(R.string.git_clean)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        setStatus(
            if (parsed.unparsedRecords == 0) {
                getString(R.string.git_status_ok)
            } else {
                getString(R.string.git_status_partial, parsed.unparsedRecords)
            },
        )
    }

    private fun entryAt(index: Int): GitStatusEntry? =
        (adapter?.getItem(index) as? Row)?.tag as? GitStatusEntry

    private fun showDiff(entry: GitStatusEntry) {
        val git = client ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val unstaged = git.diff(directory, staged = false, path = entry.path)
                    val staged = git.diff(directory, staged = true, path = entry.path)
                    if (unstaged.isNotBlank()) unstaged else staged
                }
            }
            setBusy(false)
            result.onSuccess { showText(entry.path, it) }
                .onFailure { setStatus(it.message ?: getString(R.string.git_diff_failed)) }
        }
    }

    private fun diffAll(staged: Boolean) {
        val git = client ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { git.diff(directory, staged = staged) } }
            setBusy(false)
            result.onSuccess { text ->
                showText(getString(if (staged) R.string.git_diff_staged else R.string.git_diff), text)
            }.onFailure { setStatus(it.message ?: getString(R.string.git_diff_failed)) }
        }
    }

    private fun entryActions(entry: GitStatusEntry) {
        val options = mutableListOf(getString(R.string.git_stage), getString(R.string.git_unstage))
        if (entry.untracked) options[0] = getString(R.string.git_stage)
        choose(this, entry.path, options) { index ->
            if (index == 0) {
                runWrite(R.string.git_staged) { client?.stage(directory, entry.path) }
            } else {
                runWrite(R.string.git_unstaged) { client?.unstage(directory, entry.path) }
            }
        }
    }

    private fun commit() {
        val pending = state ?: return
        if (pending.entries.isEmpty()) {
            setStatus(getString(R.string.git_nothing_to_commit))
            return
        }
        promptText(this, getString(R.string.git_commit), getString(R.string.git_commit_hint)) { message ->
            if (message.isBlank()) return@promptText
            val staged = pending.stagedCount
            confirm(
                this,
                getString(R.string.git_commit),
                getString(R.string.git_commit_confirm, message.trim(), staged),
                getString(R.string.git_commit_action),
            ) {
                runWrite(R.string.git_committed) { client?.commit(directory, message) }
            }
        }
    }

    private fun pull() {
        confirm(
            this,
            getString(R.string.git_pull),
            getString(R.string.git_pull_confirm),
            getString(R.string.git_pull),
        ) {
            runWrite(R.string.git_pulled) { client?.pull(directory) }
        }
    }

    private fun push() {
        choose(this, getString(R.string.git_push), listOf(getString(R.string.git_push_normal), getString(R.string.git_push_force))) { index ->
            val force = index == 1
            if (!force) {
                runWrite(R.string.git_pushed) { client?.push(directory, force = false) }
                return@choose
            }
            confirm(
                this,
                getString(R.string.git_push_force),
                getString(R.string.git_push_force_confirm),
                getString(R.string.git_push_force),
                destructive = true,
            ) {
                runWrite(R.string.git_pushed) { client?.push(directory, force = true) }
            }
        }
    }

    private fun branches() {
        val git = client ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { git.branches(directory) } }
            setBusy(false)
            result.onSuccess { refs ->
                if (refs.isEmpty()) {
                    setStatus(getString(R.string.git_no_branches))
                    return@onSuccess
                }
                val names = refs.map { if (it.current) "* ${it.name}" else it.name }
                choose(this@GitActivity, getString(R.string.git_branches), names) { index ->
                    val ref = refs.getOrNull(index) ?: return@choose
                    if (ref.current) {
                        setStatus(getString(R.string.git_already_on_branch, ref.name))
                        return@choose
                    }
                    confirm(
                        this@GitActivity,
                        getString(R.string.git_checkout),
                        getString(R.string.git_checkout_confirm, ref.name),
                        getString(R.string.git_checkout),
                    ) {
                        runWrite(R.string.git_checked_out) { client?.checkout(directory, ref.name) }
                    }
                }
            }.onFailure { setStatus(it.message ?: getString(R.string.git_load_failed)) }
        }
    }

    private fun log() {
        val git = client ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { git.log(directory) } }
            setBusy(false)
            result.onSuccess { commits ->
                if (commits.isEmpty()) {
                    setStatus(getString(R.string.git_no_commits))
                    return@onSuccess
                }
                val text = commits.joinToString("\n") { commit ->
                    "${commit.oid.take(8)} ${commit.relativeDate} ${commit.author}: ${commit.subject}"
                }
                showText(getString(R.string.git_log), text)
            }.onFailure { setStatus(it.message ?: getString(R.string.git_load_failed)) }
        }
    }

    private fun runWrite(messageRes: Int, block: () -> dev.anthracite.remote.ExecResult?) {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val result = runCatching { io { block() } }
            setBusy(false)
            result.onSuccess { exec ->
                if (exec == null) {
                    setStatus(getString(R.string.git_write_unavailable))
                    return@onSuccess
                }
                if (exec.exitCode == 0) {
                    setStatus(getString(messageRes))
                    refresh()
                } else {
                    setStatus(
                        getString(
                            R.string.git_failed,
                            exec.exitCode,
                            (exec.stderr.ifBlank { exec.stdout }).take(600).ifBlank { getString(R.string.git_failed_silent) },
                        ),
                    )
                }
            }.onFailure { setStatus(it.message ?: getString(R.string.git_load_failed)) }
        }
    }

    private fun showText(title: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text.ifBlank { getString(R.string.git_empty_output) }.take(MAX_OUTPUT_CHARS))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private companion object {
        const val STATE_DIRECTORY = "git_directory"
        const val MAX_OUTPUT_CHARS = 60_000
    }
}
