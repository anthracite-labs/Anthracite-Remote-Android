package dev.anthracite.remote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.anthracite.remote.ui.choose
import dev.anthracite.remote.ui.promptText
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.launch

/**
 * SFTP browsing for one host.
 *
 * Deliberately narrow: no shell interpolation of names (every request goes
 * through SFTP), no symlink following on delete, a hard cap on entries and file
 * size, and every write is confirmed with the exact remote path visible.
 */
class FilesActivity : AppCompatActivity() {
    private lateinit var adapter: RowAdapter
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: View
    private lateinit var host: HostProfile

    private var currentPath = "/"
    private var entries: List<RemoteEntry> = emptyList()
    private var pendingDownload: RemoteEntry? = null

    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) download(entry, uri)
    }

    private val pickUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        adapter = RowAdapter(this)
        pathView = findViewById(R.id.files_path)
        statusView = findViewById(R.id.files_status)
        progress = findViewById(R.id.files_progress)
        findViewById<ListView>(R.id.files_list).apply {
            adapter = this@FilesActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onEntry(entries[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                onEntryOptions(entries[position])
                true
            }
        }
        findViewById<MaterialToolbar>(R.id.files_toolbar).setNavigationOnClickListener { finish() }
        buildActions()

        val store = Workspace.store(this)
        val hostId = intent.getStringExtra(HostsActivity.EXTRA_HOST_ID)
        host = (runCatching { hostId?.let(store::find) }.getOrNull() ?: Workspace.profile)
            ?: run {
                Toast.makeText(this, R.string.workspace_no_host, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        currentPath = Workspace.lastFacts?.workingDirectory?.takeIf { RemotePath.isValid(it) } ?: "/"
        ensureConnected()
    }

    private fun ensureConnected() {
        if (Workspace.ssh.isConnected() && Workspace.profile?.id == host.id) {
            browse(currentPath)
            return
        }
        lifecycleScope.launch {
            val error = connectWorkspace(this@FilesActivity, host)
            if (error != null) {
                showStatus(error)
            } else {
                browse(currentPath)
            }
        }
    }

    private fun buildActions() {
        val row = findViewById<android.widget.LinearLayout>(R.id.files_actions)
        row.removeAllViews()
        addActionButton(this, row, getString(R.string.files_up), getString(R.string.files_up)) {
            val parent = RemotePath.parent(currentPath)
            if (parent == null) showStatus(getString(R.string.files_at_root)) else browse(parent)
        }
        addActionButton(this, row, getString(R.string.files_home), getString(R.string.files_home)) {
            val home = Workspace.lastFacts?.home?.takeIf { RemotePath.isValid(it) } ?: "/"
            browse(home)
        }
        addActionButton(this, row, getString(R.string.files_new_folder), getString(R.string.files_new_folder)) {
            promptText(this, getString(R.string.files_new_folder), getString(R.string.files_new_folder_detail)) { name ->
                val target = RemotePath.join(currentPath, name.trim())
                if (target == null) {
                    showStatus(getString(R.string.files_name_invalid))
                } else {
                    run { lifecycleScope.launch { createFolder(target) } }
                }
            }
        }
        addActionButton(this, row, getString(R.string.files_upload), getString(R.string.files_upload)) {
            pickUpload.launch(arrayOf("*/*"))
        }
        addActionButton(this, row, getString(R.string.refresh), getString(R.string.refresh)) {
            browse(currentPath)
        }
        addActionButton(this, row, getString(R.string.files_terminal_here), getString(R.string.files_terminal_here)) {
            openTerminalAt(currentPath)
        }
    }

    private fun browse(path: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { SftpBrowser(Workspace.ssh).list(path) } }
            progress.visibility = View.GONE
            result.onSuccess { listing ->
                currentPath = path
                entries = listing
                pathView.text = path
                adapter.submit(listing.map { entry -> rowFor(entry) })
                showStatus(
                    getString(
                        R.string.files_count,
                        listing.size,
                        listing.count { it.directory },
                        listing.count { !it.directory },
                    ),
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.files_list_failed)) }
        }
    }

    private fun rowFor(entry: RemoteEntry): Row {
        val kind = when {
            entry.symlink -> getString(R.string.files_symlink)
            entry.directory -> getString(R.string.files_directory)
            else -> getString(R.string.files_file)
        }
        val size = if (entry.directory) "" else " · ${formatSize(entry.sizeBytes)}"
        return Row(
            title = entry.name,
            subtitle = "${entry.permissionText} · $kind$size",
            tag = entry,
        )
    }

    private fun onEntry(entry: RemoteEntry) {
        when {
            entry.directory && !entry.symlink -> browse(entry.path)
            entry.symlink -> showStatus(
                getString(R.string.files_symlink_refused, entry.name),
            )
            else -> preview(entry)
        }
    }

    private fun preview(entry: RemoteEntry) {
        if (entry.sizeBytes > PREVIEW_LIMIT) {
            showStatus(getString(R.string.files_too_large, formatSize(entry.sizeBytes)))
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { io { SftpBrowser(Workspace.ssh).download(entry.path) } }
            progress.visibility = View.GONE
            result.onSuccess { bytes ->
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                val printable = text.none { it.code < 0x09 || (it.code in 0x0e..0x1f) }
                if (!printable) {
                    showStatus(getString(R.string.files_binary))
                } else {
                    showPreview(entry, text)
                }
            }.onFailure { showStatus(it.message ?: getString(R.string.files_read_failed)) }
        }
    }

    private fun showPreview(entry: RemoteEntry, text: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(text.take(PREVIEW_CHARS))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.terminal_copy_screen) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(entry.name, text.take(PREVIEW_CHARS)),
                )
                Toast.makeText(this, R.string.files_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun onEntryOptions(entry: RemoteEntry) {
        val options = listOf(
            getString(R.string.files_delete),
            getString(R.string.files_rename),
            getString(R.string.files_copy_path),
            getString(R.string.files_download),
        )
        choose(this, entry.name, options) { index ->
            when (index) {
                0 -> confirm(
                    this,
                    getString(R.string.files_delete),
                    getString(R.string.files_delete_detail, entry.path),
                    getString(R.string.files_delete),
                    destructive = true,
                ) {
                    lifecycleScope.launch {
                        val error = runCatching {
                            io { SftpBrowser(Workspace.ssh).delete(entry.path, recursive = entry.directory) }
                        }.exceptionOrNull()
                        if (error == null) browse(currentPath)
                        else showStatus(error.message ?: getString(R.string.files_delete_failed))
                    }
                }
                1 -> promptText(
                    this,
                    getString(R.string.files_rename),
                    getString(R.string.files_rename_detail, entry.name),
                    entry.name,
                ) { newName ->
                    val target = RemotePath.join(currentPath, newName.trim())
                    if (target == null) {
                        showStatus(getString(R.string.files_name_invalid))
                    } else {
                        lifecycleScope.launch { rename(entry, target) }
                    }
                }
                2 -> {
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", entry.path))
                    showStatus(getString(R.string.files_copied))
                }
                else -> {
                    if (entry.directory) {
                        showStatus(getString(R.string.files_download_directory))
                    } else {
                        pendingDownload = entry
                        createFile.launch(entry.name)
                    }
                }
            }
        }
    }

    private fun download(entry: RemoteEntry, uri: Uri) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = runCatching {
                io {
                    val bytes = SftpBrowser(Workspace.ssh).download(entry.path)
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("The selected destination could not be opened")
                }
            }.exceptionOrNull()
            progress.visibility = View.GONE
            showStatus(
                if (error == null) getString(R.string.files_downloaded, entry.name)
                else error.message ?: getString(R.string.files_download_failed),
            )
        }
    }

    private fun upload(uri: Uri) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                io {
                    val name = displayName(uri) ?: throw IllegalStateException("The selected file has no name")
                    val target = RemotePath.join(currentPath, name)
                        ?: throw IllegalStateException("The selected file name is not safe on this host")
                    val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            val read = stream.read(chunk)
                            if (read <= 0) break
                            buffer.write(chunk, 0, read)
                            if (buffer.size() > UPLOAD_LIMIT) {
                                throw IllegalStateException(
                                    "Files larger than ${UPLOAD_LIMIT / (1024 * 1024)} MB are not uploaded from the phone",
                                )
                            }
                        }
                        buffer.toByteArray()
                    } ?: throw IllegalStateException("The selected file could not be read")
                    SftpBrowser(Workspace.ssh).upload(target, bytes, overwrite = false)
                    target
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { target ->
                showStatus(getString(R.string.files_uploaded, target))
                browse(currentPath)
            }.onFailure { showStatus(it.message ?: getString(R.string.files_upload_failed)) }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)
            }
    }.getOrNull()

    private fun createFolder(path: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = runCatching { io { SftpBrowser(Workspace.ssh).mkdir(path) } }.exceptionOrNull()
            progress.visibility = View.GONE
            if (error == null) browse(currentPath) else showStatus(error.message ?: getString(R.string.files_mkdir_failed))
        }
    }

    private fun rename(entry: RemoteEntry, target: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = runCatching { io { SftpBrowser(Workspace.ssh).rename(entry.path, target) } }
                .exceptionOrNull()
            progress.visibility = View.GONE
            if (error == null) browse(currentPath) else showStatus(error.message ?: getString(R.string.files_rename_failed))
        }
    }

    private fun openTerminalAt(path: String) {
        if (!RemotePath.isValid(path)) return
        startActivity(
            Intent(this, dev.anthracite.remote.terminal.TerminalActivity::class.java)
                .putExtra(dev.anthracite.remote.terminal.TerminalActivity.EXTRA_START_DIRECTORY, path),
        )
    }

    private fun showStatus(message: String) {
        statusView.text = message
        runCatching { statusView.announceForAccessibility(message) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private companion object {
        const val PREVIEW_LIMIT = 256 * 1024L
        const val PREVIEW_CHARS = 20_000
        const val UPLOAD_LIMIT = 8 * 1024 * 1024
    }
}
