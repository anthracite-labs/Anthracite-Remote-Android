package dev.anthracite.remote

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import dev.anthracite.remote.files.RemoteEntry
import dev.anthracite.remote.files.RemotePath
import dev.anthracite.remote.files.SftpBrowser
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.ui.Row
import dev.anthracite.remote.ui.RowAdapter
import dev.anthracite.remote.ui.addActionButton
import dev.anthracite.remote.ui.confirm
import dev.anthracite.remote.ui.promptText
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * SFTP browser.
 *
 * The phone is not a sandbox and the SSH user's own permissions decide what can
 * be read or written. What this screen does control: it never leaves the tree the
 * user navigated to by accident (`..` is normalised), it never follows a symlink
 * to delete, it caps listings and transfers, and it uploads under a temporary
 * name so a final filename is never a half file.
 */
class FilesActivity : AppCompatActivity() {
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout

    private var host: HostProfile? = null
    private var browser: SftpBrowser? = null
    private var entries: List<RemoteEntry> = emptyList()
    private var adapter: RowAdapter? = null
    private var path: String = "/"
    private var showHidden = false
    private var busy = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) writeDownload(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        pathView = findViewById(R.id.files_path)
        list = findViewById(R.id.files_list)
        status = findViewById(R.id.files_status)
        progress = findViewById(R.id.files_progress)
        actions = findViewById(R.id.files_actions)
        findViewById<MaterialToolbar>(R.id.files_toolbar).setNavigationOnClickListener {
            if (path != "/" && RemotePath.parent(path) != null) {
                navigate(RemotePath.parent(path) ?: "/")
            } else {
                finish()
            }
        }

        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = runCatching { hostId?.let(Workspace.store(this)::find) }.getOrNull() ?: Workspace.profile
        path = savedInstanceState?.getString(STATE_PATH)
            ?: Workspace.lastFacts?.workingDirectory?.takeIf { RemotePath.isValid(it) }
            ?: "/"
        showHidden = savedInstanceState?.getBoolean(STATE_HIDDEN) ?: false

        list.setOnItemClickListener { _, _, index, _ -> open(entries.getOrNull(index)) }
        list.setOnItemLongClickListener { _, _, index, _ ->
            entries.getOrNull(index)?.let { entryActions(it) }
            true
        }
        buildActions()
        connect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
        outState.putBoolean(STATE_HIDDEN, showHidden)
    }

    private fun connect() {
        val target = host ?: run {
            Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == target.id) {
            refresh()
            return
        }
        busy = true
        setProgress(true)
        lifecycleScope.launch {
            val error = connectWorkspace(this@FilesActivity, target)
            busy = false
            setProgress(false)
            if (error != null) {
                setStatus(error)
            } else {
                refresh()
            }
        }
    }

    private fun buildActions() {
        actions.removeAllViews()
        addActionButton(this, actions, getString(R.string.files_up), getString(R.string.files_up)) {
            val parent = RemotePath.parent(path)
            if (parent == null) setStatus(getString(R.string.files_at_root)) else navigate(parent)
        }
        addActionButton(this, actions, getString(R.string.files_refresh), getString(R.string.files_refresh)) {
            refresh()
        }
        addActionButton(this, actions, getString(R.string.files_new_folder), getString(R.string.files_new_folder)) {
            promptText(this, getString(R.string.files_new_folder), getString(R.string.files_name_hint)) { name ->
                val target = RemotePath.join(path, name) ?: return@promptText badName()
                mutate(R.string.files_folder_created) { browser?.mkdir(target) }
            }
        }
        addActionButton(this, actions, getString(R.string.files_upload), getString(R.string.files_upload)) {
            if (busy) return@addActionButton
            openDocument.launch(arrayOf("*/*"))
        }
        addActionButton(this, actions, getString(R.string.files_hidden), getString(R.string.files_hidden)) {
            showHidden = !showHidden
            render()
        }
    }

    private fun navigate(target: String) {
        if (!RemotePath.isValid(target)) {
            setStatus(getString(R.string.files_invalid_path))
            return
        }
        path = target
        refresh()
    }

    private fun refresh() {
        if (busy) return
        val client = browser ?: SftpBrowser(Workspace.ssh).also { browser = it }
        if (!Workspace.ssh.isConnected()) {
            connect()
            return
        }
        busy = true
        setProgress(true)
        setStatus(getString(R.string.files_loading))
        val target = path
        lifecycleScope.launch {
            val result = runCatching { io { client.list(target) } }
            busy = false
            setProgress(false)
            result.onSuccess { loaded ->
                entries = loaded
                pathView.text = target
                render()
                setStatus(getString(R.string.files_loaded, loaded.size))
            }.onFailure { error ->
                entries = emptyList()
                render()
                setStatus(error.message ?: getString(R.string.files_load_failed))
            }
        }
    }

    private fun render() {
        val visible = entries.filter { showHidden || !it.hidden }
        val rows = visible.map { entry ->
            Row(
                title = entry.name + if (entry.directory) "/" else "",
                subtitle = describe(entry),
            )
        }
        val current = adapter ?: RowAdapter(this).also {
            adapter = it
            list.adapter = it
        }
        current.submit(rows)
        pathView.text = path
        if (visible.isEmpty()) {
            setStatus(getString(R.string.files_empty))
        }
    }

    private fun describe(entry: RemoteEntry): String {
        val kind = when {
            entry.symlink -> "link"
            entry.directory -> "dir"
            else -> size(entry.sizeBytes)
        }
        val modified = if (entry.modifiedSeconds > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(entry.modifiedSeconds * 1000))
        } else {
            "unknown"
        }
        return "$kind · ${entry.permissionText} · $modified"
    }

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private fun open(entry: RemoteEntry?) {
        if (entry == null) return
        if (entry.directory) {
            navigate(entry.path)
            return
        }
        val client = browser ?: return
        busy = true
        setProgress(true)
        lifecycleScope.launch {
            val result = runCatching { io { client.download(entry.path) } }
            busy = false
            setProgress(false)
            result.onSuccess { bytes -> showPreview(entry, bytes) }
                .onFailure { setStatus(it.message ?: getString(R.string.files_load_failed)) }
        }
    }

    private fun showPreview(entry: RemoteEntry, bytes: ByteArray) {
        val text = runCatching { decodeText(bytes) }.getOrNull()
        if (text == null) {
            AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage(getString(R.string.files_binary, size(entry.sizeBytes)))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(text.take(PREVIEW_CHARS))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.files_download) { _, _ -> download(entry) }
            .show()
    }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val text = String(bytes, Charsets.UTF_8)
        val replacement = text.count { it == '\uFFFD' }
        if (replacement > 0 && replacement * 20 > text.length) return null
        return text
    }

    private fun entryActions(entry: RemoteEntry) {
        val options = buildList {
            if (!entry.directory) add(getString(R.string.files_download))
            add(getString(R.string.files_rename))
            add(getString(R.string.files_delete))
        }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, index ->
                when (options[index]) {
                    getString(R.string.files_download) -> download(entry)
                    getString(R.string.files_rename) -> rename(entry)
                    else -> delete(entry)
                }
            }
            .show()
    }

    private fun rename(entry: RemoteEntry) {
        promptText(this, getString(R.string.files_rename), entry.name) { name ->
            val target = RemotePath.join(path, name) ?: return@promptText badName()
            if (target == entry.path) return@promptText
            mutate(R.string.files_renamed) { browser?.rename(entry.path, target) }
        }
    }

    private fun delete(entry: RemoteEntry) {
        val message = if (entry.directory) {
            getString(R.string.files_delete_tree, entry.name)
        } else {
            getString(R.string.files_delete_file, entry.name)
        }
        confirm(
            this,
            getString(R.string.files_delete),
            message,
            getString(R.string.files_delete_confirm),
            destructive = true,
        ) {
            mutate(R.string.files_deleted) { browser?.delete(entry.path, recursive = entry.directory) }
        }
    }

    private fun download(entry: RemoteEntry) {
        if (busy) return
        val client = browser ?: return
        busy = true
        setProgress(true)
        lifecycleScope.launch {
            val result = runCatching { io { client.download(entry.path) } }
            busy = false
            setProgress(false)
            result.onSuccess { bytes ->
                pendingDownload = bytes
                createDocument.launch(entry.name)
            }.onFailure { setStatus(it.message ?: getString(R.string.files_load_failed)) }
        }
    }

    private var pendingDownload: ByteArray? = null

    private fun writeDownload(uri: Uri) {
        val bytes = pendingDownload ?: return
        pendingDownload = null
        lifecycleScope.launch {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
                    ?: throw IllegalStateException(getString(R.string.files_write_failed))
            }
            result.onSuccess { setStatus(getString(R.string.files_downloaded)) }
                .onFailure { setStatus(it.message ?: getString(R.string.files_write_failed)) }
        }
    }

    private fun upload(uri: Uri) {
        val client = browser ?: return
        busy = true
        setProgress(true)
        lifecycleScope.launch {
            val result = runCatching {
                val name = displayName(uri)
                val target = RemotePath.join(path, name)
                    ?: throw IllegalStateException(getString(R.string.files_invalid_name))
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val limit = MAX_UPLOAD_BYTES + 1
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        if (buffer.size() > limit) {
                            throw IllegalStateException(getString(R.string.files_too_large, MAX_UPLOAD_BYTES / (1024 * 1024)))
                        }
                    }
                    buffer.toByteArray()
                } ?: throw IllegalStateException(getString(R.string.files_write_failed))
                io { client.upload(target, bytes, overwrite = false) }
                target
            }
            busy = false
            setProgress(false)
            result.onSuccess { target ->
                refresh()
                setStatus(getString(R.string.files_uploaded, RemotePath.name(target)))
            }.onFailure { setStatus(it.message ?: getString(R.string.files_write_failed)) }
        }
    }

    private fun displayName(uri: Uri): String {
        val fromResolver = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val name = fromResolver?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "upload.bin"
        return name.substringAfterLast('/')
    }

    private fun mutate(messageRes: Int, block: () -> Unit) {
        if (busy) return
        busy = true
        setProgress(true)
        lifecycleScope.launch {
            val result = runCatching { io { block() } }
            busy = false
            setProgress(false)
            result.onSuccess {
                setStatus(getString(messageRes))
                refresh()
            }.onFailure { setStatus(it.message ?: getString(R.string.files_write_failed)) }
        }
    }

    private fun badName() {
        setStatus(getString(R.string.files_invalid_name))
    }

    private fun setProgress(visible: Boolean) {
        progress.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private companion object {
        const val STATE_PATH = "files_path"
        const val STATE_HIDDEN = "files_hidden"
        const val PREVIEW_CHARS = 20_000
        const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024
    }
}
