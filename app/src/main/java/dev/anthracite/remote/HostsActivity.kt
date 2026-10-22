package dev.anthracite.remote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.anthracite.remote.host.HostOrigin
import dev.anthracite.remote.host.HostProfile
import dev.anthracite.remote.host.HostStore
import dev.anthracite.remote.ui.Row
import dev.anthracite.remote.ui.RowAdapter
import dev.anthracite.remote.ui.confirm
import dev.anthracite.remote.ui.choose
import dev.anthracite.remote.workspace.Workspace
import dev.anthracite.remote.workspace.connectWorkspace
import dev.anthracite.remote.workspace.io
import kotlinx.coroutines.launch

/**
 * Saved hosts: add, connect, edit and forget.
 *
 * Forgetting a host removes only this phone's profile, pinned key and encrypted
 * credential. It never touches the host, its files or its sessions.
 */
class HostsActivity : AppCompatActivity() {
    private lateinit var adapter: RowAdapter
    private lateinit var emptyView: TextView
    private lateinit var addButton: MaterialButton
    private lateinit var store: HostStore
    private var pendingKey: ByteArray? = null

    private val pickKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readKey(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hosts)
        store = Workspace.store(this)
        adapter = RowAdapter(this)
        emptyView = findViewById(R.id.hosts_empty)
        findViewById<MaterialToolbar>(R.id.hosts_toolbar).setNavigationOnClickListener { finish() }
        findViewById<ListView>(R.id.hosts_list).apply {
            adapter = this@HostsActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showHostOptions(rows[position]) }
        }
        addButton = findViewById(R.id.hosts_add)
        addButton.setOnClickListener { showAddForm() }
        runCatching { Workspace.importLegacyIfNeeded(this) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private var rows: List<Pair<Row, HostProfile>> = emptyList()

    private fun load() {
        val result = runCatching { store.list() }
        val hosts = result.getOrElse { emptyList() }
        addButton.isEnabled = result.isSuccess
        emptyView.text = getString(if (result.isSuccess) R.string.hosts_empty else R.string.hosts_unreadable)
        rows = hosts.map { host ->
            val subtitle = buildString {
                append(host.endpoint)
                val fingerprint = host.fingerprint.takeIf { it.isNotBlank() }
                append("  ·  ")
                append(fingerprint ?: getString(R.string.hosts_unverified))
                if (host.origin == HostOrigin.IMPORTED_LEGACY) append("  ·  imported")
            }
            Row(host.displayLabel, subtitle, tag = host.id) to host
        }
        adapter.submit(rows.map { it.first })
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showHostOptions(row: Pair<Row, HostProfile>) {
        val host = row.second
        val options = listOf(
            getString(R.string.hosts_connect),
            getString(R.string.hosts_run_bootstrap),
            getString(R.string.hosts_forget),
        )
        choose(this, host.displayLabel, options) { index ->
            when (index) {
                0 -> connect(host)
                1 -> {
                    val intent = Intent(this, BootstrapActivity::class.java)
                    intent.putExtra(EXTRA_HOST_ID, host.id)
                    startActivity(intent)
                }
                else -> confirm(
                    this,
                    getString(R.string.hosts_forget),
                    getString(R.string.hosts_forget_detail, host.displayLabel),
                    getString(R.string.forget),
                    destructive = true,
                ) {
                    lifecycleScope.launch {
                        val failure = runCatching { io { store.forget(host.id) } }.exceptionOrNull()
                        if (failure != null) {
                            Toast.makeText(this@HostsActivity, failure.message, Toast.LENGTH_LONG).show()
                        } else {
                            if (Workspace.profile?.id == host.id) Workspace.disconnect()
                            load()
                        }
                    }
                }
            }
        }
    }

    private fun connect(host: HostProfile) {
        lifecycleScope.launch {
            val error = connectWorkspace(this@HostsActivity, host)
            if (error == null) {
                startActivity(
                    Intent(this@HostsActivity, WorkspaceActivity::class.java)
                        .putExtra(EXTRA_HOST_ID, host.id),
                )
            } else {
                Toast.makeText(this@HostsActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddForm() {
        pendingKey = null
        val form = layoutInflater.inflate(R.layout.dialog_host_form, null)
        val label = form.findViewById<EditText>(R.id.host_label)
        val host = form.findViewById<EditText>(R.id.host_name)
        val user = form.findViewById<EditText>(R.id.host_user)
        val port = form.findViewById<EditText>(R.id.host_port)
        val password = form.findViewById<EditText>(R.id.host_password)
        val keyButton = form.findViewById<MaterialButton>(R.id.host_key_button)
        val keyState = form.findViewById<TextView>(R.id.host_key_state)
        keyButton.setOnClickListener { pickKey.launch(arrayOf("*/*")) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.hosts_add)
            .setView(form)
            .setPositiveButton(R.string.hosts_save_and_connect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = host.text?.toString()?.trim().orEmpty()
                val username = user.text?.toString()?.trim().orEmpty()
                val portValue = port.text?.toString()?.trim()?.toIntOrNull()
                val key = pendingKey
                val passwordValue = password.text?.toString().orEmpty()
                if (target.isBlank() || username.isBlank() || portValue == null || portValue !in 1..65535) {
                    Toast.makeText(this, R.string.error_config_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (key == null && passwordValue.isEmpty()) {
                    Toast.makeText(this, R.string.host_form_credential_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                keyState.text = if (key != null) getString(R.string.hosts_key_loaded) else ""
                val profile = HostProfile(
                    id = store.newId(),
                    label = label.text?.toString()?.trim().orEmpty(),
                    host = target,
                    port = portValue,
                    username = username,
                    auth = if (key != null) AuthMethod.KEY else AuthMethod.PASSWORD,
                    remotePort = DEFAULT_REMOTE_PORT,
                    keyName = if (key != null) "imported-key" else "",
                    hostKey = null,
                    hostKeyType = "",
                    fingerprint = "",
                    origin = HostOrigin.CREATED,
                    lastUsedAtMillis = System.currentTimeMillis(),
                )
                lifecycleScope.launch {
                    val saved = runCatching {
                        io {
                            store.save(
                                profile,
                                SavedSecrets(
                                    password = if (key == null) passwordValue else null,
                                    privateKey = key,
                                    passphrase = null,
                                ),
                                Workspace.key(),
                            )
                        }
                    }.exceptionOrNull()
                    if (saved != null) {
                        Toast.makeText(this@HostsActivity, saved.message, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    dialog.dismiss()
                    load()
                    connect(store.find(profile.id) ?: profile)
                }
            }
        }
        dialog.show()
    }

    private fun readKey(uri: Uri) {
        lifecycleScope.launch {
            val bytes = runCatching {
                io {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(MAX_KEY_BYTES + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val step = stream.read(buffer, read, buffer.size - read)
                            if (step <= 0) break
                            read += step
                        }
                        if (read == 0 || read > MAX_KEY_BYTES) null else buffer.copyOf(read)
                    }
                }
            }.getOrNull()
            if (bytes == null) {
                Toast.makeText(this@HostsActivity, R.string.error_key_unreadable, Toast.LENGTH_LONG).show()
            } else {
                pendingKey = bytes
                Toast.makeText(this@HostsActivity, R.string.hosts_key_loaded, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_HOST_ID = "dev.anthracite.remote.extra.HOST_ID"
        private const val DEFAULT_REMOTE_PORT = 4096
        private const val MAX_KEY_BYTES = 64 * 1024
    }
}
