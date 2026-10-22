package dev.anthracite.remote.terminal

import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.anthracite.remote.R
import dev.anthracite.remote.host.PosixShell
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.launch

/**
 * Multiple independent terminal tabs over independent SSH PTY channels.
 *
 * What the screen states plainly, because users must be able to trust it:
 * * an ephemeral shell ends when its tab is closed;
 * * a tmux attachment keeps running on the host after the tab is closed or the
 *   phone loses the network — until the host's own policy ends it;
 * * closing a tab is a detach, and the separate "Stop session" action is what
 *   asks the host to end the process, with verification;
 * * `Ctrl-C` is sent as a byte; whether a program stops is its own decision.
 */
class TerminalActivity : AppCompatActivity(), TerminalView.Listener {
    private lateinit var terminalView: TerminalView
    private lateinit var tabRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var noticeView: View
    private lateinit var noticeText: TextView

    private val renderHandler = Handler(Looper.getMainLooper())
    private var renderQueued = false
    private var activeIndex = -1
    private var scrollOffset = 0
    private var gridCols = 80
    private var gridRows = 24
    private var pendingState: String? = null
    private var pendingDirectory: String? = null
    private var destroyed = false
    // Each observer captures this Activity, so release every tab's observer on
    // destruction; TerminalTabs owns only sessions, never screen callbacks.
    private val boundListeners = mutableMapOf<TerminalSession, TerminalSession.Listener>()

    private val activeSession: TerminalSession?
        get() = TerminalTabs.get(activeIndex)?.session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        terminalView = findViewById(R.id.terminal_view)
        tabRow = findViewById(R.id.tab_row)
        statusText = findViewById(R.id.terminal_status)
        noticeView = findViewById(R.id.terminal_notice)
        noticeText = findViewById(R.id.terminal_notice_text)
        terminalView.listener = this

        val toolbar = findViewById<MaterialToolbar>(R.id.terminal_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_terminal)
        toolbar.setOnMenuItemClickListener(::onMenu)

        pendingDirectory = intent.getStringExtra(EXTRA_START_DIRECTORY)
            ?.takeIf { PosixShell.isQuotablePath(it) }
        buildSpecialKeys()
        TerminalTabs.all().forEach { bindSession(it.session) }
        if (!Workspace.ssh.isConnected()) showNotice(getString(R.string.terminal_not_connected))
        if (TerminalTabs.size() == 0) {
            if (Workspace.ssh.isConnected()) newShellTab() else renderTabs()
        } else {
            val restored = savedInstanceState?.getInt(STATE_ACTIVE_TAB, 0) ?: 0
            selectTab(restored.takeIf { it in 0 until TerminalTabs.size() } ?: 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ACTIVE_TAB, activeIndex.coerceAtLeast(0))
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        TerminalTabs.all().forEach { tab ->
            if (tab.session !in boundListeners) bindSession(tab.session)
        }
        scheduleRender()
    }

    override fun onResume() {
        super.onResume()
        scrollOffset = 0
        scheduleRender()
    }

    override fun onStop() {
        unbindSessions()
        renderHandler.removeCallbacksAndMessages(null)
        renderQueued = false
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        unbindSessions()
        renderHandler.removeCallbacksAndMessages(null)
        terminalView.listener = null
        super.onDestroy()
    }

    private fun unbindSessions() {
        boundListeners.forEach { (session, observer) ->
            if (session.listener === observer) session.listener = null
        }
        boundListeners.clear()
    }

    // ------------------------------------------------------------------ menu

    private fun onMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_new_shell -> {
            newShellTab()
            true
        }
        R.id.menu_new_session -> {
            promptNewSession()
            true
        }
        R.id.menu_attach_session -> {
            promptAttachSession()
            true
        }
        R.id.menu_interrupt -> {
            activeSession?.interrupt()
            showNotice(getString(R.string.terminal_interrupt_sent))
            true
        }
        R.id.menu_copy_screen -> {
            terminalView.copyScreenToClipboard()
            Toast.makeText(this, R.string.terminal_copied, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.menu_paste -> {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.terminal_clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                terminalView.sendPaste(text.toString())
            }
            true
        }
        R.id.menu_stop_session -> {
            confirmStopSession()
            true
        }
        R.id.menu_close_tab -> {
            closeActiveTab()
            true
        }
        else -> false
    }

    // ------------------------------------------------------------------ tabs

    private fun newShellTab() {
        val cols = gridCols
        val rows = gridRows
        lifecycleScope.launch {
            val result = runCatching {
                io { Workspace.ssh.openShell(Workspace.TERM, cols, rows) }
            }
            result.onSuccess { pty ->
                val emulator = TerminalEmulator(cols, rows)
                val session = TerminalSession(TerminalSession.Kind.SHELL, "Shell", emulator, pty)
                attachSession(session, getString(R.string.terminal_tab_shell, TerminalTabs.size() + 1))
                pendingDirectory?.let { directory ->
                    // One quoted `cd` for the initial directory only. The shell
                    // decides what to do with it; Anthracite does not parse the
                    // shell's response.
                    if (session.write(("cd " + PosixShell.quote(directory) + "\n").toByteArray(Charsets.UTF_8))) {
                        pendingDirectory = null
                    } else {
                        showNotice(getString(R.string.terminal_input_not_sent))
                    }
                }
            }.onFailure { showNotice(getString(R.string.terminal_open_failed, it.message ?: "")) }
        }
    }

    private fun promptNewSession() {
        val input = EditText(this).apply {
            hint = getString(R.string.terminal_session_name_hint)
            setText("anthracite-1")
            contentDescription = getString(R.string.terminal_session_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_new_session)
            .setMessage(R.string.terminal_new_session_detail)
            .setView(input)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                val name = input.text.toString().trim()
                if (!PosixShell.isToolName(name)) {
                    showNotice(getString(R.string.terminal_session_name_invalid))
                    return@setPositiveButton
                }
                createSession(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createSession(name: String) {
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val tmux = TmuxManager(Workspace.ssh)
                    val probe = tmux.probe()
                    if (!probe.installed) {
                        throw IllegalStateException(getString(R.string.terminal_no_tmux))
                    }
                    val created = tmux.create(name, Workspace.lastFacts?.workingDirectory)
                    if (created.exitCode != 0) {
                        throw IllegalStateException(created.text.ifBlank { "tmux could not create the session" })
                    }
                    val identity = tmux.probe().sessions.singleOrNull { it.name == name }
                        ?: throw IllegalStateException("Could not establish the tmux session identity")
                    if (identity.id.isBlank()) throw IllegalStateException("tmux returned no session id")
                    tmux.attach(identity.id, Workspace.TERM, gridCols, gridRows) to identity
                }
            }
            result.onSuccess { (pty, identity) ->
                val sessionName = identity.name
                val emulator = TerminalEmulator(gridCols, gridRows)
                val session = TerminalSession(
                    kind = TerminalSession.Kind.TMUX,
                    title = "$sessionName (tmux)",
                    emulator = emulator,
                    pty = pty,
                    tmuxSession = sessionName,
                    tmuxId = identity.id,
                )
                attachSession(session, getString(R.string.terminal_tab_session, sessionName))
            }.onFailure { showNotice(getString(R.string.terminal_session_failed, it.message ?: "")) }
        }
    }

    private fun promptAttachSession() {
        lifecycleScope.launch {
            val result = runCatching { io { TmuxManager(Workspace.ssh).probe() } }
            result.onFailure { showNotice(getString(R.string.terminal_session_failed, it.message ?: "")) }
            result.onSuccess { probe ->
                if (!probe.installed) {
                    showNotice(getString(R.string.terminal_no_tmux))
                    return@onSuccess
                }
                if (probe.sessions.isEmpty()) {
                    showNotice(getString(R.string.terminal_no_sessions))
                    return@onSuccess
                }
                val labels = probe.sessions.map { session ->
                    val attached = if (session.attachedClients > 0) {
                        getString(R.string.terminal_session_attached)
                    } else {
                        getString(R.string.terminal_session_detached)
                    }
                    "${session.name}  ·  ${session.windows} windows  ·  $attached"
                }.toTypedArray()
                AlertDialog.Builder(this@TerminalActivity)
                    .setTitle(R.string.terminal_attach_session)
                    .setItems(labels) { _, which ->
                        val target = probe.sessions[which]
                        attachExisting(target)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun attachExisting(target: TmuxSession) {
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    TmuxManager(Workspace.ssh)
                        .attach(target.id, Workspace.TERM, gridCols, gridRows)
                }
            }
            result.onSuccess { pty ->
                val emulator = TerminalEmulator(gridCols, gridRows)
                val session = TerminalSession(
                    kind = TerminalSession.Kind.TMUX,
                    title = "${target.name} (tmux)",
                    emulator = emulator,
                    pty = pty,
                    tmuxSession = target.name,
                    tmuxId = target.id,
                )
                attachSession(session, getString(R.string.terminal_tab_session, target.name))
            }.onFailure { showNotice(getString(R.string.terminal_session_failed, it.message ?: "")) }
        }
    }

    private fun attachSession(session: TerminalSession, label: String) {
        TerminalTabs.add(session, label)
        bindSession(session)
        selectTab(TerminalTabs.indexOf(session))
    }

    private fun bindSession(session: TerminalSession) {
        val observer = object : TerminalSession.Listener {
            // The reader may have captured an observer just before onDestroy.
            // Checking again on the main thread keeps those queued callbacks from
            // touching a destroyed screen or an observer replaced by a new one.
            private fun dispatch(block: () -> Unit) {
                runOnUiThread {
                    if (!destroyed && session.listener === this && TerminalTabs.indexOf(session) >= 0) block()
                }
            }

            override fun onOutput() = dispatch {
                if (activeSession === session) scheduleRender()
            }

            override fun onClosed(state: TerminalSession.ClosedState) = dispatch {
                if (activeSession === session) {
                    pendingState = describeClosed(state)
                    scheduleRender()
                }
                renderTabs()
            }

            override fun onBlockedClipboardWrite() = dispatch {
                showNotice(getString(R.string.terminal_clipboard_blocked))
            }

            override fun onTitle(title: String) = dispatch {
                if (activeSession === session) updateStatus()
            }

            override fun onHyperlink(uri: String) = dispatch {
                showNotice(getString(R.string.terminal_link_blocked, uri.take(120)))
            }
        }
        boundListeners[session] = observer
        session.listener = observer
    }

    private fun describeClosed(state: TerminalSession.ClosedState): String = when {
        state.exitSignal != null -> getString(R.string.terminal_exit_signal, state.exitSignal)
        state.exitStatus >= 0 -> getString(R.string.terminal_exit_status, state.exitStatus)
        else -> state.reason
    }

    private fun removeSessionTab(session: TerminalSession, reason: String) {
        val index = TerminalTabs.indexOf(session)
        if (index < 0) return
        val previouslySelected = activeSession
        boundListeners.remove(session)?.let { observer ->
            if (session.listener === observer) session.listener = null
        }
        session.close(reason)
        TerminalTabs.remove(index)
        activeIndex = -1
        val next = previouslySelected?.let(TerminalTabs::indexOf) ?: -1
        if (TerminalTabs.size() > 0) selectTab(next.takeIf { it >= 0 } ?: 0)
        else {
            renderTabs()
            statusText.text = getString(R.string.terminal_no_tabs)
        }
    }

    private fun selectTab(index: Int) {
        val tab = TerminalTabs.get(index) ?: return
        activeIndex = index
        scrollOffset = 0
        pendingState = tab.session.lastCloseState?.let(::describeClosed)
        terminalView.resetScroll()
        renderTabs()
        terminalView.requestFocus()
        scheduleRender()
        updateStatus()
        if (tab.session.lastCloseState == null && !tab.session.resize(gridCols, gridRows)) {
            showNotice(getString(R.string.terminal_input_not_sent))
        }
    }

    private fun closeActiveTab() {
        val session = activeSession ?: return
        val persistent = session.tmuxSession != null
        AlertDialog.Builder(this)
            .setTitle(if (persistent) R.string.terminal_detach_title else R.string.terminal_close_title)
            .setMessage(
                if (persistent) {
                    getString(R.string.terminal_detach_detail, session.tmuxSession)
                } else {
                    getString(R.string.terminal_close_detail)
                },
            )
            .setPositiveButton(if (persistent) R.string.terminal_detach else R.string.close) { _, _ ->
                removeSessionTab(session, if (persistent) "Detached" else "Tab closed")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmStopSession() {
        val session = activeSession ?: return
        val name = session.tmuxSession
        if (name == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_stop_session)
                .setMessage(R.string.terminal_stop_ephemeral_detail)
                .setPositiveButton(R.string.terminal_force_close) { _, _ ->
                    removeSessionTab(session, "Force closed by the user")
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_stop_session)
            .setMessage(getString(R.string.terminal_stop_detail, name))
            .setPositiveButton(R.string.stop) { _, _ ->
                lifecycleScope.launch {
                    val stopped = runCatching {
                        val id = session.tmuxId ?: error("Missing selected tmux identity")
                        io { TmuxManager(Workspace.ssh).killAndVerify(name, id) }
                    }.getOrDefault(false)
                    if (stopped && TerminalTabs.indexOf(session) >= 0) {
                        removeSessionTab(session, "Host session stopped")
                        Toast.makeText(this@TerminalActivity, R.string.terminal_stopped, Toast.LENGTH_SHORT).show()
                    } else {
                        showNotice(getString(R.string.terminal_stop_unconfirmed, name))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val tabs = TerminalTabs.all()
        tabs.forEachIndexed { index, tab ->
            val button = inflater.inflate(R.layout.item_terminal_key, tabRow, false) as MaterialButton
            button.text = tab.label
            button.contentDescription = getString(R.string.terminal_tab_description, tab.label)
            button.isChecked = index == activeIndex
            button.setOnClickListener { selectTab(index) }
            tabRow.addView(button)
        }
        val add = inflater.inflate(R.layout.item_terminal_key, tabRow, false) as MaterialButton
        add.text = "+"
        add.contentDescription = getString(R.string.terminal_new_shell)
        add.setOnClickListener { newShellTab() }
        tabRow.addView(add)
    }

    private fun buildSpecialKeys() {
        val row = findViewById<LinearLayout>(R.id.special_keys)
        row.removeAllViews()
        fun add(label: String, description: String, action: () -> Unit) {
            val button = LayoutInflater.from(this)
                .inflate(R.layout.item_terminal_key, row, false) as MaterialButton
            button.text = label
            button.contentDescription = description
            button.setOnClickListener { action() }
            row.addView(button)
        }
        add("Esc", getString(R.string.key_escape), { terminalView.sendKey(TerminalKey.ESCAPE) })
        add("Tab", getString(R.string.key_tab), { terminalView.sendKey(TerminalKey.TAB) })
        add(
            "Ctrl",
            getString(R.string.key_ctrl),
            {
                terminalView.ctrlLatched = !terminalView.ctrlLatched
                showNotice(
                    if (terminalView.ctrlLatched) getString(R.string.terminal_ctrl_latched)
                    else getString(R.string.terminal_ctrl_released),
                )
            },
        )
        add(
            "Alt",
            getString(R.string.key_alt),
            {
                terminalView.altLatched = !terminalView.altLatched
                showNotice(
                    if (terminalView.altLatched) getString(R.string.terminal_alt_latched)
                    else getString(R.string.terminal_alt_released),
                )
            },
        )
        add("Ctrl-C", getString(R.string.key_interrupt), { activeSession?.interrupt() })
        add("←", getString(R.string.key_left), { terminalView.sendKey(TerminalKey.ARROW_LEFT) })
        add("↑", getString(R.string.key_up), { terminalView.sendKey(TerminalKey.ARROW_UP) })
        add("↓", getString(R.string.key_down), { terminalView.sendKey(TerminalKey.ARROW_DOWN) })
        add("→", getString(R.string.key_right), { terminalView.sendKey(TerminalKey.ARROW_RIGHT) })
        add("Home", getString(R.string.key_home), { terminalView.sendKey(TerminalKey.HOME) })
        add("End", getString(R.string.key_end), { terminalView.sendKey(TerminalKey.END) })
        add("PgUp", getString(R.string.key_page_up), { terminalView.scrollByLines(-5) })
        add("PgDn", getString(R.string.key_page_down), { terminalView.scrollByLines(5) })
    }

    // ------------------------------------------------------------- rendering

    private fun scheduleRender() {
        if (renderQueued) return
        renderQueued = true
        renderHandler.postDelayed({
            renderQueued = false
            renderNow()
        }, RENDER_INTERVAL_MS)
    }

    private fun renderNow() {
        val session = activeSession ?: return
        val emulator = session.emulator
        val modes = KeyModes(
            applicationCursorKeys = emulator.usesApplicationCursorKeys,
            bracketedPaste = emulator.acceptsBracketedPaste,
            mouseReporting = emulator.mouseReporting,
            mouseEncodingSgr = emulator.mouseEncodingSgr,
        )
        terminalView.update(emulator.snapshot(scrollOffset), modes)
        updateStatus()
    }

    private fun updateStatus() {
        val session = activeSession ?: return
        pendingState?.let {
            statusText.text = it
            return
        }
        val position = if (scrollOffset > 0) {
            getString(R.string.terminal_scrolled, scrollOffset, session.emulator.scrollbackLines)
        } else {
            ""
        }
        val prefix = when (session.kind) {
            TerminalSession.Kind.TMUX -> getString(R.string.terminal_mode_tmux, session.tmuxSession ?: "")
            TerminalSession.Kind.SHELL -> getString(R.string.terminal_mode_shell)
            TerminalSession.Kind.COMMAND -> getString(R.string.terminal_mode_command)
        }
        val title = session.emulator.terminalTitle
        statusText.text = listOf(prefix, title, position).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun showNotice(message: String) {
        noticeText.text = message
        noticeView.visibility = View.VISIBLE
        runCatching { noticeView.announceForAccessibility(message) }
    }

    override fun onGridChanged(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        gridCols = cols
        gridRows = rows
        if (activeSession?.resize(cols, rows, widthPx, heightPx) == false) {
            showNotice(getString(R.string.terminal_input_not_sent))
        }
        scheduleRender()
    }

    override fun onInput(bytes: ByteArray) {
        val session = activeSession ?: return
        if (!session.write(bytes)) showNotice(getString(R.string.terminal_input_not_sent))
    }

    override fun onUserScroll(offset: Int) {
        scrollOffset = offset
        scheduleRender()
    }

    companion object {
        /** Optional absolute directory a new shell starts in. */
        const val EXTRA_START_DIRECTORY = "dev.anthracite.remote.extra.START_DIRECTORY"
        private const val RENDER_INTERVAL_MS = 16L
        private const val STATE_ACTIVE_TAB = "selected_terminal_tab"
    }
}
