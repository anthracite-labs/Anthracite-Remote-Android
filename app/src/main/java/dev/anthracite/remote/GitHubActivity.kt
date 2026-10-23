package dev.anthracite.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import dev.anthracite.remote.github.DeviceCode
import dev.anthracite.remote.github.GitHubApi
import dev.anthracite.remote.github.GitHubAuth
import dev.anthracite.remote.github.GitHubIdentity
import dev.anthracite.remote.github.GitHubResult
import dev.anthracite.remote.github.GitHubTokenException
import dev.anthracite.remote.github.GitHubTokenStore
import dev.anthracite.remote.github.IssueComment
import dev.anthracite.remote.github.IssueDetail
import dev.anthracite.remote.github.IssueState
import dev.anthracite.remote.github.IssueSummary
import dev.anthracite.remote.github.MergeMethod
import dev.anthracite.remote.github.PullDetail
import dev.anthracite.remote.github.PullSummary
import dev.anthracite.remote.github.RepoRef
import dev.anthracite.remote.github.RepoSummary
import dev.anthracite.remote.github.ReviewEvent
import dev.anthracite.remote.github.TokenPoll
import dev.anthracite.remote.github.UrlConnectionTransport
import dev.anthracite.remote.ui.Row
import dev.anthracite.remote.ui.RowAdapter
import dev.anthracite.remote.ui.addActionButton
import dev.anthracite.remote.ui.choose
import dev.anthracite.remote.ui.confirm
import dev.anthracite.remote.ui.promptSecret
import dev.anthracite.remote.ui.promptText
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GitHub, deliberately separate from host Git.
 *
 * Host Git uses the server's own credentials; GitHub actions use a token the user
 * authorized on this phone. Every write names its exact target in a confirmation,
 * and nothing here can touch a host.
 */
class GitHubActivity : AppCompatActivity() {
    private enum class Mode { REPOS, PULLS, ISSUES, ISSUE_DETAIL, DETAIL }
    private enum class RepoView { PULLS, ISSUES }

    private lateinit var adapter: RowAdapter
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: View
    private lateinit var actions: LinearLayout

    private lateinit var transport: UrlConnectionTransport
    private lateinit var api: GitHubApi
    private lateinit var auth: GitHubAuth
    private lateinit var tokens: GitHubTokenStore

    private var token: String? = null
    private var identity: GitHubIdentity? = null
    private var mode = Mode.REPOS
    private var repo: RepoSummary? = null
    private var pull: PullDetail? = null
    private var pulls: List<PullSummary> = emptyList()
    private var issues: List<IssueSummary> = emptyList()
    private var issue: IssueDetail? = null
    private var issueComments: List<IssueComment> = emptyList()
    private var issueStateFilter = "open"
    private var issueNextPage: Int? = null
    private var pollJob: kotlinx.coroutines.Job? = null

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
            title = getString(R.string.github_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<ListView>(R.id.section_list).apply {
            adapter = this@GitHubActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onRow(position) }
        }

        transport = UrlConnectionTransport()
        api = GitHubApi(transport) { token }
        val clientId = if (resources.getBoolean(R.bool.github_device_flow_enabled)) {
            getString(R.string.github_oauth_client_id)
                .takeIf { it.isNotBlank() && it != UNCONFIGURED_CLIENT_ID }
        } else {
            null
        }
        auth = GitHubAuth(transport, clientId)
        tokens = GitHubTokenStore(Workspace.secrets(this), Workspace.key())
        token = try {
            tokens.read()
        } catch (error: GitHubTokenException) {
            runCatching { tokens.clear() }
            showStatus(getString(R.string.github_token_unreadable))
            null
        }
        if (token == null) {
            showSignedOut()
        } else {
            loadIdentity()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ auth

    private fun showSignedOut() {
        mode = Mode.REPOS
        subtitle.text = getString(R.string.github_signed_out)
        emptyView.visibility = View.VISIBLE
        emptyView.text = getString(R.string.github_sign_in_detail)
        adapter.submit(emptyList())
        renderActions(signedIn = false)
        showStatus(getString(R.string.github_sign_in_hint))
    }

    private fun renderActions(signedIn: Boolean) {
        actions.removeAllViews()
        if (!signedIn) {
            if (auth.deviceFlowConfigured) {
                addActionButton(this, actions, getString(R.string.github_device_sign_in), getString(R.string.github_device_sign_in)) {
                    startDeviceFlow()
                }
            }
            addActionButton(this, actions, getString(R.string.github_use_token), getString(R.string.github_use_token)) {
                promptToken()
            }
            addActionButton(this, actions, getString(R.string.github_token_help), getString(R.string.github_token_help)) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.github_token_help)
                    .setMessage(getString(R.string.github_token_help_detail))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
            return
        }
        addActionButton(this, actions, getString(R.string.github_refresh), getString(R.string.github_refresh)) {
            when (mode) {
                Mode.REPOS -> loadRepositories()
                Mode.PULLS -> repo?.let { loadPulls(it) }
                Mode.ISSUES -> repo?.let { loadIssues(it) }
                Mode.ISSUE_DETAIL -> {
                    val target = currentRepoRef()
                    val number = issue?.number
                    if (target != null && number != null) loadIssueDetail(target, number)
                }
                Mode.DETAIL -> {
                    val target = currentRepoRef()
                    val detail = pull
                    if (target != null && detail != null) loadDetail(target, detail.summary.number)
                }
            }
        }
        if (mode == Mode.ISSUES) {
            addActionButton(this, actions, getString(R.string.github_issue_new), getString(R.string.github_issue_new)) {
                repo?.let { promptNewIssue(it) }
            }
            addActionButton(
                this,
                actions,
                getString(R.string.github_issue_filter, issueStateFilter),
                getString(R.string.github_issue_filter, issueStateFilter),
            ) {
                val options = listOf("open", "closed")
                choose(this, getString(R.string.github_issue_filter_title), options) { index ->
                    issueStateFilter = options[index]
                    repo?.let { loadIssues(it) }
                }
            }
            issueNextPage?.let { next ->
                addActionButton(this, actions, getString(R.string.github_more), getString(R.string.github_more)) {
                    repo?.let { loadIssues(it, page = next, append = true) }
                }
            }
        }
        addActionButton(this, actions, getString(R.string.github_sign_out), getString(R.string.github_sign_out)) {
            confirm(
                this,
                getString(R.string.github_sign_out),
                getString(R.string.github_sign_out_detail),
                getString(R.string.github_sign_out),
            ) {
                token = null
                identity = null
                tokens.clear()
                showSignedOut()
                Toast.makeText(this, R.string.github_signed_out_toast, Toast.LENGTH_SHORT).show()
            }
        }
        addActionButton(this, actions, getString(R.string.github_revoke_help), getString(R.string.github_revoke_help)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.github_revoke_help)
                .setMessage(getString(R.string.github_revoke_help_detail, auth.revokeGuidanceUrl()))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun promptToken() {
        promptSecret(
            this,
            getString(R.string.github_use_token),
            getString(R.string.github_token_detail),
        ) { value ->
            val candidate = value.trim()
            if (candidate.length < 20) {
                showStatus(getString(R.string.github_token_invalid))
                return@promptSecret
            }
            validateToken(candidate)
        }
    }

    /** Stores the token, reporting a storage failure instead of crashing the screen. */
    private fun remember(value: String): Boolean = try {
        tokens.write(value)
        true
    } catch (error: Exception) {
        showStatus(getString(R.string.github_token_store_failed))
        false
    }

    private fun validateToken(candidate: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = io { auth.identity(candidate) }
            progress.visibility = View.GONE
            result.onSuccess { observed ->
                token = candidate
                identity = observed
                if (!remember(candidate)) return@onSuccess
                subtitle.text = getString(R.string.github_signed_in, observed.login)
                loadRepositories()
            }.onFailure {
                showStatus(it.message ?: getString(R.string.github_token_rejected))
            }
        }
    }

    private fun startDeviceFlow() {
        lifecycleScope.launch {
            val requested = io { auth.requestDeviceCode() }
            requested.onFailure { error ->
                showStatus(error.message ?: getString(R.string.github_device_failed))
            }.onSuccess { code ->
                showDeviceCode(code)
            }
        }
    }

    private fun showDeviceCode(code: DeviceCode) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.github_device_sign_in)
            .setMessage(getString(R.string.github_device_detail, code.userCode, code.verificationUri))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.github_copy_code) { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("code", code.userCode))
            }
            .show()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            var interval = code.intervalSeconds.coerceIn(5, 60)
            var waited = 0
            while (waited < code.expiresInSeconds) {
                delay(interval * 1000L)
                waited += interval
                when (val poll = io { auth.poll(code) }) {
                    is TokenPoll.Success -> {
                        token = poll.token
                        if (!remember(poll.token)) return@launch
                        loadIdentity()
                        return@launch
                    }
                    is TokenPoll.Pending -> Unit
                    is TokenPoll.SlowDown -> interval = poll.intervalSeconds.coerceIn(5, 120)
                    is TokenPoll.Denied -> {
                        showStatus(poll.message)
                        return@launch
                    }
                    is TokenPoll.Expired -> {
                        showStatus(poll.message)
                        return@launch
                    }
                    is TokenPoll.Failed -> {
                        showStatus(poll.message)
                        return@launch
                    }
                }
            }
            showStatus(getString(R.string.github_device_expired))
        }
    }

    // ----------------------------------------------------------------- data

    private fun loadIdentity() {
        val current = token ?: return showSignedOut()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = io { auth.identity(current) }
            progress.visibility = View.GONE
            result.onSuccess { observed ->
                identity = observed
                subtitle.text = getString(R.string.github_signed_in, observed.login)
                renderActions(signedIn = true)
                loadRepositories()
            }.onFailure {
                token = null
                tokens.clear()
                showSignedOut()
                showStatus(it.message ?: getString(R.string.github_token_rejected))
            }
        }
    }

    private fun loadRepositories() {
        if (token == null) return showSignedOut()
        mode = Mode.REPOS
        repo = null
        pull = null
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.listRepositories() } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { page ->
                adapter.submit(
                    page.items.map { item ->
                        Row(
                            title = item.fullName,
                            subtitle = buildString {
                                if (item.private) append(getString(R.string.github_private)).append(" · ")
                                append(item.defaultBranch)
                                append(" · ").append(getString(R.string.github_open_issues, item.openIssues))
                                item.description?.let { append("\n").append(it.take(200)) }
                            },
                            tag = item,
                        )
                    },
                )
                emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = getString(R.string.github_no_repositories)
                showStatus(getString(R.string.github_repo_count, page.items.size))
            }
        }
    }

    private fun chooseRepoView(target: RepoSummary) {
        val views = listOf(RepoView.PULLS, RepoView.ISSUES)
        val labels = listOf(getString(R.string.github_open_pulls), getString(R.string.github_open_issues_action))
        choose(this, getString(R.string.github_choose_view, target.fullName), labels) { index ->
            when (views[index]) {
                RepoView.PULLS -> loadPulls(target)
                RepoView.ISSUES -> {
                    issueStateFilter = "open"
                    loadIssues(target)
                }
            }
        }
    }

    private fun loadIssues(target: RepoSummary, page: Int = 1, append: Boolean = false) {
        repo = target
        mode = Mode.ISSUES
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.listIssues(RepoRef(target.owner, target.name), issueStateFilter, page) } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { loaded ->
                // The list endpoint also returns pull requests; they are not issues
                // for this screen, and a row that opened a pull by mistake would be
                // worse than hiding it.
                val visible = loaded.items.filterNot { it.isPullRequest }
                issues = if (append) issues + visible else visible
                issueNextPage = loaded.nextPage
                adapter.submit(
                    issues.map { item ->
                        Row(
                            title = "#${item.number} ${item.title}",
                            subtitle = buildString {
                                append(item.state)
                                append(" · ").append(item.author)
                                append(" · ").append(getString(R.string.github_issue_comments, item.comments))
                            },
                            tag = item,
                        )
                    },
                )
                emptyView.visibility = if (issues.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = getString(R.string.github_no_issues)
                subtitle.text = getString(R.string.github_issues_in, target.fullName)
                renderActions(signedIn = true)
                showStatus(getString(R.string.github_issue_count, issues.size))
            }
        }
    }

    private fun loadIssueDetail(target: RepoRef, number: Int) {
        mode = Mode.ISSUE_DETAIL
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = runCatching { io { api.issueDetail(target, number) } }
            val comments = runCatching { io { api.listIssueComments(target, number) } }
            progress.visibility = View.GONE
            handle(detail.getOrNull(), detail.exceptionOrNull()) { loaded ->
                issue = loaded
                issueComments = when (val page = comments.getOrNull()) {
                    is GitHubResult.Ok -> page.value.items
                    else -> emptyList()
                }
                subtitle.text = getString(R.string.github_issue_title, target.toString(), loaded.number)
                val rows = buildList {
                    add(
                        Row(
                            title = loaded.title,
                            subtitle = buildString {
                                append(loaded.state)
                                append(" · ").append(loaded.author)
                                if (loaded.labels.isNotEmpty()) {
                                    append(" · ").append(loaded.labels.joinToString(", "))
                                }
                            },
                            body = loaded.body.take(BODY_CHARS),
                        ),
                    )
                    add(Row(getString(R.string.github_issue_comment), getString(R.string.github_issue_comment_hint)))
                    add(
                        Row(
                            if (loaded.state == "open") getString(R.string.github_issue_close) else getString(R.string.github_issue_reopen),
                            getString(R.string.github_issue_state_hint),
                        ),
                    )
                    if (issueComments.isNotEmpty()) {
                        add(Row(getString(R.string.github_issue_comments_title, issueComments.size), ""))
                        for (comment in issueComments) {
                            add(
                                Row(
                                    title = comment.author,
                                    subtitle = comment.createdAt?.take(10).orEmpty(),
                                    body = comment.body.take(COMMENT_CHARS),
                                ),
                            )
                        }
                    }
                }
                adapter.submit(rows)
                emptyView.visibility = View.GONE
                renderActions(signedIn = true)
                showStatus(
                    if (comments.getOrNull() is GitHubResult.Ok) {
                        getString(R.string.github_issue_loaded)
                    } else {
                        getString(R.string.github_issue_comments_unavailable)
                    },
                )
            }
        }
    }

    private fun promptNewIssue(target: RepoSummary) {
        promptText(
            this,
            getString(R.string.github_issue_new_title),
            getString(R.string.github_issue_new_title_hint),
        ) { title ->
            val safeTitle = title.trim()
            if (safeTitle.isEmpty()) return@promptText
            promptText(
                this,
                getString(R.string.github_issue_new),
                getString(R.string.github_issue_new_body, target.fullName),
            ) { body ->
                confirm(
                    this,
                    getString(R.string.github_issue_new),
                    getString(R.string.github_issue_new_confirm, target.fullName, safeTitle),
                    getString(R.string.github_issue_new_action),
                ) {
                    progress.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val result = runCatching { io { api.createIssue(RepoRef(target.owner, target.name), safeTitle, body) } }
                        progress.visibility = View.GONE
                        handle(result.getOrNull(), result.exceptionOrNull()) { number ->
                            showStatus(getString(R.string.github_issue_created, number))
                            loadIssues(target)
                        }
                    }
                }
            }
        }
    }

    private fun promptIssueComment() {
        val current = issue ?: return
        val target = currentRepoRef() ?: return
        promptText(
            this,
            getString(R.string.github_issue_comment),
            getString(R.string.github_issue_comment_detail, current.number),
        ) { body ->
            if (body.isBlank()) return@promptText
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = runCatching { io { api.commentOnIssue(target, current.number, body) } }
                progress.visibility = View.GONE
                handle(result.getOrNull(), result.exceptionOrNull()) { url ->
                    showStatus(getString(R.string.github_comment_posted, url))
                    loadIssueDetail(target, current.number)
                }
            }
        }
    }

    private fun promptIssueState() {
        val current = issue ?: return
        val target = currentRepoRef() ?: return
        val next = if (current.state == "open") IssueState.CLOSED else IssueState.OPEN
        confirm(
            this,
            getString(R.string.github_issue_change_state),
            getString(R.string.github_issue_change_state_detail, target.toString(), current.number, next.label),
            next.label,
        ) {
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = runCatching { io { api.setIssueState(target, current.number, next) } }
                progress.visibility = View.GONE
                handle(result.getOrNull(), result.exceptionOrNull()) { state ->
                    showStatus(getString(R.string.github_issue_state_changed, state))
                    loadIssueDetail(target, current.number)
                }
            }
        }
    }

    private fun loadPulls(target: RepoSummary) {
        repo = target
        mode = Mode.PULLS
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.listPulls(RepoRef(target.owner, target.name)) } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { page ->
                pulls = page.items
                adapter.submit(
                    page.items.map { item ->
                        Row(
                            title = "#${item.number} ${item.title}",
                            subtitle = buildString {
                                append(item.state)
                                if (item.draft) append(" · draft")
                                append(" · ").append(item.author)
                                append(" · ").append(item.headRef).append(" → ").append(item.baseRef)
                            },
                            tag = item,
                        )
                    },
                )
                emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = getString(R.string.github_no_pulls)
                subtitle.text = getString(R.string.github_pulls_in, target.fullName)
                showStatus(getString(R.string.github_pull_count, page.items.size))
            }
        }
    }

    private fun loadDetail(target: RepoRef, number: Int) {
        mode = Mode.DETAIL
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.pullDetail(target, number) } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { detail ->
                pull = detail
                subtitle.text = getString(R.string.github_pull_title, target.toString(), detail.summary.number)
                adapter.submit(
                    listOf(
                        Row(
                            title = detail.summary.title,
                            subtitle = buildString {
                                append(detail.summary.state)
                                if (detail.summary.draft) append(" · draft")
                                append(" · ").append(detail.summary.author)
                                append(" · ").append(detail.summary.headRef)
                                    .append(" → ").append(detail.summary.baseRef)
                                append(" · ").append(
                                    getString(
                                        R.string.github_pull_stats,
                                        detail.changedFiles,
                                        detail.additions,
                                        detail.deletions,
                                    ),
                                )
                            },
                            body = detail.body.take(BODY_CHARS),
                        ),
                        Row(getString(R.string.github_pull_diff), getString(R.string.github_pull_diff_hint)),
                        Row(getString(R.string.github_pull_checks), getString(R.string.github_pull_checks_hint)),
                        Row(getString(R.string.github_pull_comment), getString(R.string.github_pull_comment_hint)),
                        Row(getString(R.string.github_pull_review), getString(R.string.github_pull_review_hint)),
                        Row(getString(R.string.github_pull_merge), getString(R.string.github_pull_merge_hint)),
                    ),
                )
                emptyView.visibility = View.GONE
                showStatus(getString(R.string.github_pull_loaded))
            }
        }
    }

    private fun onRow(position: Int) {
        when (mode) {
            Mode.REPOS -> {
                val item = runCatching { adapter.getItem(position).tag as RepoSummary }.getOrNull() ?: return
                repo = item
                chooseRepoView(item)
            }
            Mode.PULLS -> {
                val item = pulls.getOrNull(position) ?: return
                val target = currentRepoRef() ?: return
                loadDetail(target, item.number)
            }
            Mode.ISSUES -> {
                val item = issues.getOrNull(position) ?: return
                val target = currentRepoRef() ?: return
                loadIssueDetail(target, item.number)
            }
            Mode.ISSUE_DETAIL -> when (position) {
                1 -> promptIssueComment()
                2 -> promptIssueState()
            }
            Mode.DETAIL -> when (position) {
                1 -> showDiff()
                2 -> showChecks()
                3 -> promptComment()
                4 -> promptReview()
                5 -> promptMerge()
            }
        }
    }

    private fun showDiff() {
        val detail = pull ?: return
        val target = currentRepoRef() ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.pullDiff(target, detail.summary.number) } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { diff ->
                androidx.appcompat.app.AlertDialog.Builder(this@GitHubActivity)
                    .setTitle(getString(R.string.github_pull_diff))
                    .setMessage(diff.take(DIFF_CHARS))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun showChecks() {
        val detail = pull ?: return
        val target = currentRepoRef() ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { api.listChecks(target, detail.headSha) } }
            progress.visibility = View.GONE
            handle(result.getOrNull(), result.exceptionOrNull()) { checks ->
                val message = if (checks.isEmpty()) {
                    getString(R.string.github_no_checks)
                } else {
                    checks.joinToString("\n") { check ->
                        "• ${check.name}: ${check.conclusion ?: check.status}"
                    }
                }
                androidx.appcompat.app.AlertDialog.Builder(this@GitHubActivity)
                    .setTitle(R.string.github_pull_checks)
                    .setMessage(message)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun promptComment() {
        val detail = pull ?: return
        val target = currentRepoRef() ?: return
        promptText(
            this,
            getString(R.string.github_pull_comment),
            getString(R.string.github_pull_comment_detail, detail.summary.number),
        ) { body ->
            if (body.isBlank()) return@promptText
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = runCatching { io { api.commentOnIssue(target, detail.summary.number, body) } }
                progress.visibility = View.GONE
                handle(result.getOrNull(), result.exceptionOrNull()) { url ->
                    showStatus(getString(R.string.github_comment_posted, url))
                }
            }
        }
    }

    private fun promptReview() {
        val detail = pull ?: return
        val target = currentRepoRef() ?: return
        val events = listOf(ReviewEvent.APPROVE, ReviewEvent.REQUEST_CHANGES, ReviewEvent.COMMENT)
        choose(this, getString(R.string.github_pull_review), events.map { it.name }) { index ->
            val event = events[index]
            promptText(
                this,
                getString(R.string.github_pull_review),
                getString(R.string.github_pull_review_detail, event.name, detail.summary.number),
            ) { body ->
                progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val result = runCatching { io { api.submitReview(target, detail.summary.number, event, body) } }
                    progress.visibility = View.GONE
                    handle(result.getOrNull(), result.exceptionOrNull()) { resultUrl ->
                        showStatus(getString(R.string.github_review_posted, resultUrl))
                    }
                }
            }
        }
    }

    private fun promptMerge() {
        val detail = pull ?: return
        val target = currentRepoRef() ?: return
        val methods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH, MergeMethod.REBASE)
        choose(this, getString(R.string.github_pull_merge), methods.map { it.name }) { index ->
            val method = methods[index]
            confirm(
                this,
                getString(R.string.github_merge_title),
                getString(R.string.github_merge_detail, target.toString(), detail.summary.number, method.name),
                getString(R.string.github_merge_confirm),
            ) {
                progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val result = runCatching {
                        io { api.mergePull(target, detail.summary.number, method, detail.headSha) }
                    }
                    progress.visibility = View.GONE
                    handle(result.getOrNull(), result.exceptionOrNull()) { outcome ->
                        showStatus(
                            if (outcome.merged) {
                                getString(R.string.github_merged, outcome.sha ?: "")
                            } else {
                                outcome.message
                            },
                        )
                        loadDetail(target, detail.summary.number)
                    }
                }
            }
        }
    }

    private fun currentRepoRef(): RepoRef? {
        val current = repo ?: return null
        return runCatching { RepoRef(current.owner, current.name) }.getOrNull()
    }

    private fun showStatus(message: String) {
        statusView.text = dev.anthracite.remote.host.LogRedactor.redact(message)
        runCatching { statusView.announceForAccessibility(message) }
    }

    private fun <T> handle(result: GitHubResult<T>?, error: Throwable?, onValue: (T) -> Unit) {
        if (error != null) {
            showStatus(error.message ?: getString(R.string.github_failed))
            return
        }
        when (result) {
            is GitHubResult.Ok -> onValue(result.value)
            is GitHubResult.Unauthorized -> {
                token = null
                tokens.clear()
                showSignedOut()
                showStatus(result.message.ifBlank { getString(R.string.github_token_rejected) })
            }
            is GitHubResult.Forbidden -> showStatus(
                if (result.rateLimited) {
                    getString(R.string.github_rate_limited, result.retryAfterSeconds ?: 60)
                } else {
                    result.message
                },
            )
            is GitHubResult.NotFound -> showStatus(result.message.ifBlank { getString(R.string.github_not_found) })
            is GitHubResult.Invalid -> showStatus(result.message)
            is GitHubResult.Failed -> showStatus(result.message)
            null -> showStatus(getString(R.string.github_failed))
        }
    }

    private companion object {
        const val BODY_CHARS = 4000
        const val UNCONFIGURED_CLIENT_ID = "unconfigured"
        const val DIFF_CHARS = 40_000
        const val COMMENT_CHARS = 2_000
    }
}
