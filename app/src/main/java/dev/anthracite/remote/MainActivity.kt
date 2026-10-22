package dev.anthracite.remote

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var store: ConnectionStore
    private val ssh = SshClient()
    private val log = StringBuilder()
    private var job: Job? = null
    private var wantConnected = false
    private val reconnectGate = ReconnectGate()
    private var autoReconnectJob: Job? = null
    private val tunnelLock = Mutex()
    private var acceptedIdentity: String? = null
    private var pendingKey: ByteArray? = null
    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var localPort = -1
    private var tunneledRemotePort = MANAGED_PORT
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingChooser: WebChromeClient.FileChooserParams? = null
    private var lastCrashAt = 0L
    private var webUser = "opencode"
    private var webPassword: CharArray? = null
    private var usedSavedWebPassword = false
    private var pendingDownload: PendingDownload? = null
    private var authDialog: AlertDialog? = null

    private val keyPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_KEY + 1) } }.getOrNull()
        if (bytes == null || bytes.size > MAX_KEY) {
            toast("Could not read that private key")
            return@registerForActivityResult
        }
        pendingKey?.fill(0)
        pendingKey = bytes
        findViewById<TextView>(R.id.key_name).text = uri.lastPathSegment ?: getString(R.string.choose_key)
        findViewById<RadioButton>(R.id.auth_key).isChecked = true
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        val params = pendingChooser
        lifecycleScope.launch {
            val accepted = withContext(Dispatchers.IO) {
                val parsed = params?.let { WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data) }.orEmpty()
                parsed.filter { uri -> params != null && runCatching { isSafePickerResult(uri, params) }.getOrDefault(false) }
                    .takeIf { params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE || it.size == 1 }
                    ?.toTypedArray()
            }
            if (fileCallback === callback) {
                fileCallback = null
                pendingChooser = null
                callback.onReceiveValue(accepted)
            }
        }
    }

    private val saveDownload = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingDownload
        pendingDownload = null
        val uri = result.data?.data
        if (request == null || uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { saveDownload(request, uri) }
            toast(if (saved) "Download saved" else "Download failed")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { scheduleReconnect() }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                showWebStatus("Network lost. The tunnel will reconnect when the network returns.")
                if (!wantConnected) return@runOnUiThread
                ssh.disconnect()
                localPort = -1
                append("Network lost. The tunnel will reconnect when the network returns.")
                scheduleReconnect()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ConnectionStore(this)
        val root = findViewById<View>(R.id.root_container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(this) {
            val page = webView
            when {
                findViewById<View>(R.id.web_panel).visibility == View.VISIBLE && page?.canGoBack() == true -> page.goBack()
                findViewById<View>(R.id.web_panel).visibility == View.VISIBLE -> showManage()
                findViewById<View>(R.id.manage_panel).visibility == View.VISIBLE -> showSetup()
                else -> moveTaskToBack(true)
            }
        }
        findViewById<Button>(R.id.save_button).setOnClickListener { if (saveForm()) showManage() }
        findViewById<Button>(R.id.key_button).setOnClickListener { keyPicker.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.connect_button).setOnClickListener { connectExisting() }
        findViewById<Button>(R.id.install_button).setOnClickListener { install() }
        findViewById<Button>(R.id.open_web_button).setOnClickListener { openWeb() }
        findViewById<Button>(R.id.reconnect_button).setOnClickListener { reconnect() }
        findViewById<Button>(R.id.web_reconnect).setOnClickListener { reconnect() }
        findViewById<Button>(R.id.restart_button).setOnClickListener { managed("restart") }
        findViewById<Button>(R.id.update_button).setOnClickListener { managed("update") }
        findViewById<Button>(R.id.uninstall_button).setOnClickListener {
            confirm(R.string.uninstall_title, R.string.uninstall_body) { managed("uninstall") }
        }
        findViewById<Button>(R.id.disconnect_button).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.forget_button).setOnClickListener {
            confirm(R.string.forget_title, R.string.forget_body) { forget() }
        }
        findViewById<Button>(R.id.edit_button).setOnClickListener { showSetup() }
        findViewById<Button>(R.id.web_back).setOnClickListener {
            if (webView?.canGoBack() == true) webView?.goBack() else showManage()
        }
        findViewById<Button>(R.id.web_refresh).setOnClickListener { webView?.reload() }
        findViewById<Button>(R.id.web_manage).setOnClickListener { showManage() }
        restoreForm()
        if (store.hasConnection()) showManage() else showSetup()
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
        webView?.onResume()
        if (wantConnected && !ssh.isConnected()) scheduleReconnect()
    }

    override fun onStop() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        webView?.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        authDialog?.dismiss()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        destroyWebView()
        ssh.disconnect()
        pendingKey?.fill(0)
        webPassword?.fill('\u0000')
        super.onDestroy()
    }

    private fun connectExisting() {
        launchJob { connectExistingNow() }
    }

    private suspend fun connectExistingNow() {
        if (!ensureSsh()) return
        val port = store.load().remotePort
        append("Connecting to the existing OpenCode service. This does not change it.")
        openTunnel(port)
    }

    private fun install() {
        if (!saveForm()) return
        promptWebPassword { password -> launchJob { installNow(password) } }
    }

    private suspend fun installNow(password: CharArray) {
        try {
            if (!ensureSsh()) return
            val script = assets.open("install-opencode.sh").use { it.readBytes() }
            val made = withContext(Dispatchers.IO) { ssh.exec(MKTEMP, timeoutMs = 20_000) }
            val dir = made.stdout.trim()
            if (made.exitCode != 0 || !uploadDirAllowed(dir)) {
                append(made.text.ifBlank { "Could not create a remote temporary directory" })
                return
            }
            val scriptPath = "$dir/install-opencode.sh"
            try {
                withContext(Dispatchers.IO) { ssh.upload(script, scriptPath) }
                val preflight = runScript(scriptPath, "preflight", timeoutMs = 60_000)
                append(preflight.text)
                if (preflight.exitCode != 0) {
                    append("Provisioning failed")
                    return
                }
                val secret = password.concatToString()
                val stdin = "$secret\n".toByteArray(Charsets.UTF_8)
                val installed = try {
                    runScript(scriptPath, "install", stdin, timeoutMs = 10 * 60_000)
                } finally {
                    stdin.fill(0)
                }
                append(redact(installed.text, secret))
                if (installed.exitCode != 0) {
                    append("Provisioning failed")
                    return
                }
                append("Install finished. HTTP 401 means the service is up and still requires a browser login.")
                openTunnel(MANAGED_PORT)
            } finally {
                val quoted = shellQuote(dir)
                if (quoted != null) withContext(Dispatchers.IO) { runCatching { ssh.exec("rm -rf -- $quoted", timeoutMs = 20_000) } }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun managed(command: String) {
        launchJob { managedNow(command) }
    }

    private suspend fun managedNow(command: String) {
        if (!ensureSsh()) return
        val script = assets.open("install-opencode.sh").use { it.readBytes() }
        val made = withContext(Dispatchers.IO) { ssh.exec(MKTEMP, timeoutMs = 20_000) }
        val dir = made.stdout.trim()
        if (made.exitCode != 0 || !uploadDirAllowed(dir)) {
            append(made.text.ifBlank { "Could not create a remote temporary directory" })
            return
        }
        val scriptPath = "$dir/install-opencode.sh"
        try {
            withContext(Dispatchers.IO) { ssh.upload(script, scriptPath) }
            val result = runScript(scriptPath, command, timeoutMs = 10 * 60_000)
            append(result.text.ifBlank { "$command finished" })
            if (result.exitCode != 0) append("Command failed")
            if (command == "restart" && result.exitCode == 0 && localPort > 0) openTunnel(MANAGED_PORT)
            if (command == "uninstall") append("Android connection settings were kept. Forget connection is separate.")
        } finally {
            val quoted = shellQuote(dir)
            if (quoted != null) withContext(Dispatchers.IO) { runCatching { ssh.exec("rm -rf -- $quoted", timeoutMs = 20_000) } }
        }
    }

    private fun openWeb() {
        if (localPort > 0 && ssh.isConnected()) showWeb(localPort) else connectExisting()
    }

    private fun reconnect() {
        launchJob { reconnectFromUser() }
    }

    private suspend fun reconnectFromUser() {
        reconnectGate.userConnected()
        wantConnected = true
        val token = reconnectGate.networkChanged() ?: return
        autoReconnectJob?.cancel()
        reconnectSerialized(token)
    }

    private fun scheduleReconnect() {
        val token = reconnectGate.networkChanged() ?: return
        autoReconnectJob?.cancel()
        autoReconnectJob = lifecycleScope.launch {
            delay(RECONNECT_SETTLE_MS)
            if (!reconnectGate.stillCurrent(token) || !wantConnected) return@launch
            if (!defaultNetworkUp()) return@launch
            if (job?.isActive == true) return@launch
            if (ssh.isConnected() && localPort > 0) return@launch
            reconnectSerialized(token)
        }
    }

    private suspend fun reconnectSerialized(token: Int) {
        tunnelLock.withLock {
            if (!reconnectGate.stillCurrent(token) || !wantConnected) return
            val remote = tunneledRemotePort
            withContext(Dispatchers.IO) { ssh.disconnect() }
            destroyWebView()
            localPort = -1
            if (!reconnectGate.stillCurrent(token)) return
            if (!ensureSsh()) return
            if (!reconnectGate.stillCurrent(token)) {
                ssh.disconnect()
                return
            }
            openTunnel(remote)
        }
    }

    private fun defaultNetworkUp(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork != null
    }

    private fun disconnect() {
        job?.cancel()
        autoReconnectJob?.cancel()
        reconnectGate.userDisconnected()
        wantConnected = false
        ssh.disconnect()
        localPort = -1
        destroyWebView()
        clearWebCredentials()
        acceptedIdentity = null
        append("Disconnected")
        showManage()
    }

    private fun forget() {
        disconnect()
        pendingKey?.fill(0)
        pendingKey = null
        tunneledRemotePort = MANAGED_PORT
        store.forget()
        findViewById<EditText>(R.id.host_input).text.clear()
        findViewById<EditText>(R.id.username_input).text.clear()
        findViewById<EditText>(R.id.password_input).text.clear()
        findViewById<EditText>(R.id.passphrase_input).text.clear()
        findViewById<TextView>(R.id.key_name).text = getString(R.string.no_key)
        log.clear()
        showSetup()
    }

    private suspend fun ensureSsh(): Boolean {
        if (ssh.isConnected()) return true
        val target = buildTarget() ?: return false
        val savedKey = store.load().hostKey
        val outcome = withContext(Dispatchers.IO) { ssh.connect(target, savedKey) }
        target.privateKey?.fill(0)
        return when (outcome) {
            is ConnectOutcome.Connected -> {
                append("SSH connected. Host key ${outcome.keyType} ${outcome.fingerprint}".trim())
                true
            }
            is ConnectOutcome.UnknownHostKey -> approveHostKey(outcome)
            is ConnectOutcome.ChangedHostKey -> refuseChangedKey(outcome)
            is ConnectOutcome.AuthFailed -> {
                stopWantingConnection()
                append("SSH authentication failed. Check the username, password, or private key.")
                false
            }
            is ConnectOutcome.Failed -> {
                stopWantingConnection()
                append("SSH failed: ${outcome.message}")
                false
            }
        }
    }

    private suspend fun approveHostKey(outcome: ConnectOutcome.UnknownHostKey): Boolean {
        val saved = store.load()
        val approved = ask(
            getString(R.string.host_key_title),
            "First connection to ${saved.host}:${saved.sshPort}.\n\n${outcome.keyType}\n${outcome.fingerprint}\n\nThe key is checked before authentication. Approve only if it matches the server.",
            getString(R.string.approve),
            getString(R.string.cancel),
        )
        if (!approved) {
            append("Host key was not approved.")
            return false
        }
        store.saveHostKey(outcome.key, outcome.keyType, outcome.fingerprint)
        return ensureSsh()
    }

    private suspend fun refuseChangedKey(outcome: ConnectOutcome.ChangedHostKey): Boolean {
        stopWantingConnection()
        val approved = ask(
            getString(R.string.host_key_changed_title),
            "Connection refused. The server key changed.\n\nSaved: ${outcome.savedFingerprint}\nPresented: ${outcome.keyType} ${outcome.fingerprint}\n\nApprove the new fingerprint only after checking it out of band.",
            getString(R.string.approve_new_key),
            getString(R.string.cancel),
        )
        if (!approved) {
            append("Host key change refused.")
            showManage()
            return false
        }
        store.saveHostKey(outcome.key, outcome.keyType, outcome.fingerprint)
        return ensureSsh()
    }

    private fun stopWantingConnection() {
        wantConnected = false
        reconnectGate.userDisconnected()
        autoReconnectJob?.cancel()
    }

    private suspend fun openTunnel(remotePort: Int) {
        syncTunnelIdentity(remotePort)
        val assigned = withContext(Dispatchers.IO) { ssh.forward(remotePort) }
        localPort = assigned
        tunneledRemotePort = remotePort
        val code = withContext(Dispatchers.IO) { awaitHttp(assigned) }
        ensureActive()
        reconnectGate.userConnected()
        wantConnected = true
        append("Tunnel 127.0.0.1:$assigned -> 127.0.0.1:$remotePort HTTP $code")
        when (code) {
            401 -> {
                append("Service is up and requires authentication. The browser session is not authenticated yet.")
                showWeb(assigned)
            }
            200, 301, 302, 303, 307, 308 -> showWeb(assigned)
            else -> {
                append("Service unavailable. SSH is connected, but OpenCode did not respond on the remote loopback port.")
                showManage()
            }
        }
    }

    private fun awaitHttp(port: Int): Int {
        repeat(10) {
            val code = probe(port)
            if (code > 0) return code
            Thread.sleep(400)
        }
        return probe(port)
    }

    private fun probe(port: Int): Int = runCatching {
        val connection = URL("http://$LOOPBACK:$port/").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.responseCode.also { connection.disconnect() }
    }.getOrDefault(-1)

    private suspend fun runScript(path: String, command: String, stdin: ByteArray? = null, timeoutMs: Long): ExecResult {
        require(command in SCRIPT_COMMANDS)
        val quoted = shellQuote(path) ?: error("unsafe remote path")
        return withContext(Dispatchers.IO) { ssh.exec("bash $quoted $command", stdin, timeoutMs) }
    }

    private fun launchJob(block: suspend () -> Unit) {
        if (job?.isActive == true) {
            toast("Wait for the current step to finish")
            return
        }
        job = lifecycleScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                append(error.message ?: "Failed")
            }
            refreshSummary()
        }
    }

    private fun saveForm(): Boolean {
        val host = findViewById<EditText>(R.id.host_input).text.toString()
        val username = findViewById<EditText>(R.id.username_input).text.toString()
        val sshPort = parsePort(findViewById<EditText>(R.id.ssh_port_input).text.toString())
        val remotePort = parsePort(findViewById<EditText>(R.id.remote_port_input).text.toString())
        val auth = if (findViewById<RadioButton>(R.id.auth_key).isChecked) AuthMethod.KEY else AuthMethod.PASSWORD
        if (!sshHostAllowed(host) || !sshUsernameAllowed(username) || sshPort == null || remotePort == null) {
            toast("Enter a host, username, and valid ports")
            return false
        }
        val typedPassword = findViewById<EditText>(R.id.password_input).text.toString()
        val typedPassphrase = findViewById<EditText>(R.id.passphrase_input).text.toString()
        val existing = runCatching { store.secrets() }.getOrDefault(SavedSecrets(null, null, null))
        val password = typedPassword.ifBlank { existing.password }
        val passphrase = typedPassphrase.ifBlank { existing.passphrase }
        val key = pendingKey ?: existing.privateKey
        if (auth == AuthMethod.PASSWORD && password.isNullOrEmpty()) {
            toast("Enter the SSH password")
            return false
        }
        if (auth == AuthMethod.KEY && key == null) {
            toast("Choose a private key")
            return false
        }
        store.saveProfile(host, sshPort, username, auth, remotePort, findViewById<TextView>(R.id.key_name).text.toString())
        store.saveSecrets(
            SavedSecrets(
                password = if (auth == AuthMethod.PASSWORD) password else null,
                privateKey = if (auth == AuthMethod.KEY) key else null,
                passphrase = if (auth == AuthMethod.KEY) passphrase else null,
            )
        )
        if (typedPassword.isNotEmpty()) findViewById<EditText>(R.id.password_input).text.clear()
        if (typedPassphrase.isNotEmpty()) findViewById<EditText>(R.id.passphrase_input).text.clear()
        refreshSummary()
        return true
    }

    private fun buildTarget(): SshTarget? {
        if (!saveForm()) return null
        val saved = store.load()
        val secrets = store.secrets()
        return SshTarget(
            host = saved.host,
            port = saved.sshPort,
            username = saved.username,
            password = secrets.password,
            privateKey = secrets.privateKey,
            passphrase = secrets.passphrase,
        )
    }

    private fun restoreForm() {
        if (!store.hasConnection()) return
        val saved = store.load()
        findViewById<EditText>(R.id.host_input).setText(saved.host)
        findViewById<EditText>(R.id.ssh_port_input).setText(saved.sshPort.toString())
        findViewById<EditText>(R.id.username_input).setText(saved.username)
        findViewById<EditText>(R.id.remote_port_input).setText(saved.remotePort.toString())
        findViewById<RadioButton>(if (saved.auth == AuthMethod.KEY) R.id.auth_key else R.id.auth_password).isChecked = true
        if (saved.keyName.isNotBlank()) findViewById<TextView>(R.id.key_name).text = saved.keyName
        refreshSummary()
    }

    private fun refreshSummary() {
        if (!store.hasConnection()) return
        val saved = store.load()
        val fingerprint = if (saved.fingerprint.isBlank()) "not approved yet" else saved.fingerprint
        findViewById<TextView>(R.id.summary_text).text =
            "${saved.username}@${saved.host}:${saved.sshPort}\nHost key: $fingerprint\nExisting OpenCode port: ${saved.remotePort}"
        findViewById<TextView>(R.id.log_text).text = log.toString()
    }

    private fun append(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        if (log.isNotEmpty()) log.append('\n')
        log.append(clean)
        if (log.length > 60_000) log.delete(0, log.length - 40_000)
        findViewById<TextView>(R.id.log_text).text = log.toString()
    }

    private fun showSetup() {
        findViewById<View>(R.id.setup_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.manage_panel).visibility = View.GONE
        findViewById<View>(R.id.web_panel).visibility = View.GONE
    }

    private fun showManage() {
        refreshSummary()
        findViewById<View>(R.id.setup_panel).visibility = View.GONE
        findViewById<View>(R.id.manage_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.web_panel).visibility = View.GONE
    }

    private fun showWeb(port: Int) {
        findViewById<TextView>(R.id.web_status).visibility = View.GONE
        findViewById<View>(R.id.setup_panel).visibility = View.GONE
        findViewById<View>(R.id.manage_panel).visibility = View.GONE
        findViewById<View>(R.id.web_panel).visibility = View.VISIBLE
        attachWebView("http://$LOOPBACK:$port/")
    }

    private fun showWebStatus(message: String) {
        val status = findViewById<TextView>(R.id.web_status)
        status.text = message
        status.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView(url: String) {
        if (webView != null && loadedUrl == url) return
        destroyWebView()
        val page = WebView(this)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            textZoom = (resources.configuration.fontScale * 100).toInt()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url?.toString() ?: return true
                if (!request.isForMainFrame) return classifyNavigation(target, localPort) != WebNavigation.LOAD
                return when (classifyNavigation(target, localPort)) {
                    WebNavigation.LOAD -> false
                    WebNavigation.EXTERNAL -> {
                        openExternal(request.url)
                        true
                    }
                    WebNavigation.BLOCK -> true
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val target = request.url?.toString() ?: return forbiddenResponse()
                if (classifyNavigation(target, localPort) == WebNavigation.LOAD) return null
                if (request.isForMainFrame && classifyNavigation(target, localPort) == WebNavigation.EXTERNAL) {
                    view.post { openExternal(request.url) }
                }
                return forbiddenResponse()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && wantConnected && !ssh.isConnected()) scheduleReconnect()
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                if (!authReleaseAllowed(view.url, loadedUrl, host, localPort)) {
                    handler.cancel()
                    return
                }
                val saved = webPassword
                if (saved != null && !usedSavedWebPassword) {
                    usedSavedWebPassword = true
                    handler.proceed(webUser, saved.concatToString())
                    return
                }
                usedSavedWebPassword = false
                promptBasicAuth(handler)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyWebView()
                val now = SystemClock.elapsedRealtime()
                if (detail.didCrash() && now - lastCrashAt < 10_000) {
                    showWebStatus("WebView renderer crashed repeatedly.")
                    showManage()
                } else {
                    lastCrashAt = if (detail.didCrash()) now else 0L
                    if (localPort > 0) attachWebView("http://$LOOPBACK:$localPort/")
                }
                return true
            }
        }
        page.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                pendingChooser = fileChooserParams
                return try {
                    filePicker.launch(createSafPickerIntent(fileChooserParams) ?: error("unsupported picker"))
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    pendingChooser = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.deny()
            }
        }
        page.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (!loopbackHttp(url, localPort)) return@setDownloadListener
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            pendingDownload = PendingDownload(url, mimeType ?: "application/octet-stream", name)
            saveDownload.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType ?: "*/*")
                    .putExtra(Intent.EXTRA_TITLE, name)
            )
        }
        findViewById<FrameLayout>(R.id.web_container).addView(
            page,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        webView = page
        loadedUrl = url
        page.loadUrl(url)
    }

    private fun createSafPickerIntent(params: WebChromeClient.FileChooserParams): Intent? {
        val mode = when (params.mode) {
            WebChromeClient.FileChooserParams.MODE_OPEN -> PickerMode.OPEN
            WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE -> PickerMode.OPEN_MULTIPLE
            WebChromeClient.FileChooserParams.MODE_SAVE -> PickerMode.SAVE
            WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER -> PickerMode.OPEN_FOLDER
            else -> return null
        }
        val intent = Intent(pickerAction(mode))
        if (mode != PickerMode.OPEN_FOLDER) intent.addCategory(Intent.CATEGORY_OPENABLE)
        if (mode == PickerMode.OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (mode == PickerMode.SAVE) {
            intent.putExtra(Intent.EXTRA_TITLE, params.filenameHint?.takeIf { it.isNotBlank() } ?: "upload")
        }
        if (mode != PickerMode.OPEN_FOLDER) {
            val types = params.acceptTypes?.map { it.trim() }
                ?.filter { it != "*/*" && it.contains('/') && it.none { char -> char.isWhitespace() } }
                ?.distinct()
                .orEmpty()
            intent.type = if (mode == PickerMode.SAVE) saveDocumentMimeType(types) else types.singleOrNull() ?: "*/*"
            if (mode != PickerMode.SAVE && types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (mode == PickerMode.SAVE) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return intent
    }

    private fun isSafePickerResult(uri: Uri, params: WebChromeClient.FileChooserParams): Boolean {
        val mode = when (params.mode) {
            WebChromeClient.FileChooserParams.MODE_OPEN -> PickerMode.OPEN
            WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE -> PickerMode.OPEN_MULTIPLE
            WebChromeClient.FileChooserParams.MODE_SAVE -> PickerMode.SAVE
            WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER -> PickerMode.OPEN_FOLDER
            else -> return false
        }
        if (!pickerUriAllowed(
                uri.toString(),
                packageName,
                mode,
                hasReadGrant = checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                hasWriteGrant = checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        ) return false
        if (packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)?.packageName == packageName) return false
        if (mode == PickerMode.OPEN_FOLDER && !DocumentsContract.isTreeUri(uri)) return false
        if (mode == PickerMode.SAVE || mode == PickerMode.OPEN_FOLDER) return true
        val actualType = contentResolver.getType(uri)
        val accepted = params.acceptTypes?.filter { it.isNotBlank() && it != "*/*" }.orEmpty()
        if (actualType != null && accepted.isNotEmpty() && accepted.none { accept ->
                accept == actualType || (accept.endsWith("/*") && actualType.startsWith(accept.substringBefore('/') + "/"))
            }
        ) return false
        return contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }

    private fun promptBasicAuth(handler: HttpAuthHandler) {
        if (authDialog?.isShowing == true) {
            handler.cancel()
            return
        }
        val user = EditText(this).apply {
            hint = getString(R.string.username)
            setText(webUser)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pass = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(user)
            addView(pass)
        }
        var handled = false
        fun finish(proceed: Boolean) {
            if (handled) return
            handled = true
            if (!proceed) {
                handler.cancel()
                return
            }
            webUser = user.text.toString().ifBlank { "opencode" }
            webPassword?.fill('\u0000')
            webPassword = pass.text.toString().toCharArray()
            usedSavedWebPassword = true
            handler.proceed(webUser, pass.text.toString())
        }
        authDialog = AlertDialog.Builder(this)
            .setTitle(R.string.auth_title)
            .setMessage("Sign in to the forwarded OpenCode origin. Credentials are not put in the URL or saved on this phone.")
            .setView(form)
            .setPositiveButton(R.string.sign_in) { _, _ -> finish(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .show()
    }

    private fun promptWebPassword(onReady: (CharArray) -> Unit) {
        val first = EditText(this).apply {
            hint = getString(R.string.web_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val second = EditText(this).apply {
            hint = getString(R.string.web_password_confirm_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.web_password_title)
            .setMessage(R.string.web_password_body)
            .setView(form)
            .setPositiveButton(R.string.continue_action, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text.toString()
                if (password != second.text.toString() || !webPasswordAccepted(password)) {
                    toast("Use matching passwords of at least 12 characters with a letter and a digit")
                    return@setOnClickListener
                }
                dialog.dismiss()
                onReady(password.toCharArray())
            }
        }
        dialog.show()
    }

    private fun saveDownload(request: PendingDownload, destination: Uri): Boolean = runCatching {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        CookieManager.getInstance().getCookie(request.url)?.let { connection.setRequestProperty("Cookie", it) }
        webPassword?.let { secret ->
            val token = Base64.encodeToString("${webUser}:${secret.concatToString()}".toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        connection.inputStream.use { input ->
            contentResolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                ?: error("no output")
        }
        true
    }.getOrDefault(false)

    private fun confirm(title: Int, message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.continue_action) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun ask(title: String, message: String, positive: String, negative: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(negative) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun syncTunnelIdentity(remotePort: Int) {
        val saved = store.load()
        val next = tunnelIdentity(saved.host, saved.sshPort, saved.username, remotePort)
        if (acceptedIdentity != null && acceptedIdentity != next) clearWebCredentials()
        acceptedIdentity = next
    }

    private fun clearWebCredentials() {
        webPassword?.fill('\u0000')
        webPassword = null
        usedSavedWebPassword = false
        CookieManager.getInstance().removeAllCookies(null)
        @Suppress("DEPRECATION")
        runCatching { WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword() }
    }

    private var lastExternalUrl: String? = null
    private var lastExternalAt = 0L

    private fun openExternal(uri: Uri?) {
        if (uri == null) return
        val value = uri.toString()
        val now = SystemClock.elapsedRealtime()
        if (value == lastExternalUrl && now - lastExternalAt < 1_000) return
        lastExternalUrl = value
        lastExternalAt = now
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    private fun forbiddenResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            emptyMap<String, String>(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun destroyWebView() {
        webView?.let {
            findViewById<FrameLayout>(R.id.web_container).removeView(it)
            it.destroy()
        }
        webView = null
        loadedUrl = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class PendingDownload(val url: String, val mimeType: String, val name: String)

    private companion object {
        const val MANAGED_PORT = 4096
        const val RECONNECT_SETTLE_MS = 400L
        const val MAX_KEY = 256 * 1024
        val SCRIPT_COMMANDS = setOf("preflight", "install", "status", "restart", "update", "uninstall")
        const val MKTEMP =
            "mkdir -p \"\$HOME/.local/share/anthracite\" && mktemp -d \"\$HOME/.local/share/anthracite/upload.XXXXXX\""
    }
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val read = read(buffer, offset, limit - offset)
        if (read < 0) break
        offset += read
    }
    return buffer.copyOf(offset)
}
