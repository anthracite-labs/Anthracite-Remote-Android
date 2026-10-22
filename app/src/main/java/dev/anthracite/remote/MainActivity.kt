package dev.anthracite.remote

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.LinearInterpolator
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Single-activity shell for the redesigned UI. Screen order and back targets
 * come from [NavigationPolicy]; the Home surface from [HomeStatePolicy];
 * deployment phases from [DeployProgress]. Everything security-relevant
 * (host-key trust, credential handling, tunnel probing, WebView isolation)
 * keeps its previous behavior.
 */
class MainActivity : AppCompatActivity() {
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
    private var lastExternalUrl: String? = null
    private var lastExternalAt = 0L

    // Redesign state: navigation, Home, deployment progress, and the two
    // full-screen suspension points (host-key trust, WebUI password).
    private var currentScreen = Screen.HOME
    private var homeUi: HomeUi? = null
    private var errorBannerText: String? = null
    private var lastConnectError: String? = null
    private var homeDetailsExpanded = false
    private var setupAdvancedExpanded = false
    private var progressDetailsExpanded = false
    private var progress: DeployProgressState? = null
    private var progressOp: DeployOp? = null
    private var progressRetryAction: (() -> Unit)? = null
    private var phaseRows: List<PhaseRow> = emptyList()
    private var activePhaseAnimator: ObjectAnimator? = null
    private var lineStream = LineStream()
    private var verifyContinuation: CancellableContinuation<Boolean>? = null
    private var prepareContinuation: CancellableContinuation<CharArray?>? = null
    private var screenBeforeVerify = Screen.HOME

    private data class PhaseRow(val icon: ImageView, val label: TextView, val state: TextView)

    private val keyPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_KEY + 1) } }.getOrNull()
        if (bytes == null || bytes.size > MAX_KEY) {
            snackbar(getString(R.string.error_key_unreadable))
            return@registerForActivityResult
        }
        pendingKey?.fill(0)
        pendingKey = bytes
        val name = uri.lastPathSegment.orEmpty()
        val keyName = findViewById<TextView>(R.id.key_name)
        keyName.tag = name
        keyName.text = if (name.isNotBlank()) getString(R.string.key_selected, name) else getString(R.string.choose_key)
        findViewById<MaterialButtonToggleGroup>(R.id.auth_group).check(R.id.auth_key)
        updateAuthVisibility()
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        val params = pendingChooser
        lifecycleScope.launch {
            val accepted = withContext(Dispatchers.IO) {
                val parsed = params?.let { WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data) }.orEmpty()
                parsed.filter { uri -> params != null && runCatching { isSafePickerResult(uri, params) }.getOrDefault(false) }
                    .takeIf {
                        it.isNotEmpty() &&
                            (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE || it.size == 1)
                    }
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
            snackbar(getString(if (saved) R.string.download_saved else R.string.download_failed))
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { scheduleReconnect() }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                showWebBanner(getString(R.string.banner_network_lost))
                if (!wantConnected) return@runOnUiThread
                // Disconnect is generation-safe inside SshClient and also aborts a
                // connect that is waiting on a network handshake. Cancelling the
                // current provisioning action prevents it from continuing over a
                // newly selected network with a half-open session.
                job?.cancel()
                ssh.disconnect()
                localPort = -1
                destroyWebView()
                append(getString(R.string.banner_network_lost))
                updateProgress { DeployProgress.stop(it) }
                if (currentScreen == Screen.WEB) {
                    // Stay on the (now blank) Web surface: the automatic
                    // reconnect restores it in place when the network returns.
                    scheduleReconnect()
                    return@runOnUiThread
                }
                errorBannerText = getString(R.string.banner_network_lost)
                showScreen(Screen.HOME)
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
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        wireWelcome()
        wireSetup()
        wireVerify()
        wireHome()
        wirePrepare()
        wireProgress()
        wireWeb()
        wireManage()
        wireBanners()
        restoreForm()
        showScreen(NavigationPolicy.firstScreen(store.hasConnection(), store.introSeen()))
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
        webView?.onResume()
        if (wantConnected && !ssh.isConnected()) scheduleReconnect()
        if (currentScreen == Screen.HOME) renderHome()
    }

    override fun onStop() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        webView?.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        stopPhaseAnimator()
        authDialog?.dismiss()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        destroyWebView()
        ssh.disconnect()
        pendingKey?.fill(0)
        webPassword?.fill('\u0000')
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Screen switching
    // ---------------------------------------------------------------------

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        SCREEN_IDS.forEach { (candidate, id) ->
            findViewById<View>(id).visibility = if (candidate == screen) View.VISIBLE else View.GONE
        }
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.MANAGE -> renderManage()
            Screen.SETUP -> renderSetup()
            Screen.PROGRESS -> renderProgress()
            else -> Unit
        }
        findViewById<View>(SCREEN_IDS.getValue(screen))
            .sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun handleBack() {
        when (currentScreen) {
            Screen.PROGRESS ->
                if (job?.isActive == true) confirmStopOperation() else showScreen(Screen.HOME)
            Screen.PREPARE_INSTALL ->
                if (prepareContinuation != null) finishPrepare(null) else showScreen(Screen.HOME)
            Screen.VERIFY ->
                if (verifyContinuation != null) resolveVerify(false) else showScreen(screenBeforeVerify)
            else ->
                when (val action = NavigationPolicy.backFrom(currentScreen, webView?.canGoBack() == true, store.hasConnection())) {
                    is BackAction.To -> showScreen(action.screen)
                    BackAction.WebHistory -> webView?.goBack()
                    BackAction.ExitTask -> moveTaskToBack(true)
                }
        }
    }

    // ---------------------------------------------------------------------
    // Welcome
    // ---------------------------------------------------------------------

    private fun wireWelcome() {
        findViewById<Button>(R.id.welcome_cta).setOnClickListener {
            store.setIntroSeen(true)
            showScreen(Screen.SETUP)
        }
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private fun wireSetup() {
        findViewById<Button>(R.id.save_button).setOnClickListener { verifyIdentityAndGoHome() }
        findViewById<Button>(R.id.key_button).setOnClickListener { keyPicker.launch(arrayOf("*/*")) }
        findViewById<MaterialButtonToggleGroup>(R.id.auth_group).addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateAuthVisibility()
        }
        findViewById<View>(R.id.setup_advanced_toggle).setOnClickListener {
            setupAdvancedExpanded = !setupAdvancedExpanded
            findViewById<View>(R.id.setup_advanced_section).visibility =
                if (setupAdvancedExpanded) View.VISIBLE else View.GONE
            val chevron = findViewById<ImageView>(R.id.setup_advanced_chevron)
            if (NavigationPolicy.animationsEnabled(animatorScale())) {
                chevron.animate().rotation(if (setupAdvancedExpanded) 180f else 0f).setDuration(150).start()
            } else {
                chevron.rotation = if (setupAdvancedExpanded) 180f else 0f
            }
            updateAdvancedSummary()
        }
        val summaryWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateAdvancedSummary()
        }
        findViewById<EditText>(R.id.ssh_port_input).addTextChangedListener(summaryWatcher)
        findViewById<EditText>(R.id.remote_port_input).addTextChangedListener(summaryWatcher)
    }

    private fun updateAuthVisibility() {
        val keyMode = findViewById<MaterialButtonToggleGroup>(R.id.auth_group).checkedButtonId == R.id.auth_key
        findViewById<View>(R.id.password_field).visibility = if (keyMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.key_button).visibility = if (keyMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.key_name).visibility = if (keyMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.passphrase_field).visibility = if (keyMode) View.VISIBLE else View.GONE
    }

    private fun updateAdvancedSummary() {
        val sshPort = findViewById<EditText>(R.id.ssh_port_input).text.toString().ifBlank { getString(R.string.default_ssh_port) }
        val remotePort = findViewById<EditText>(R.id.remote_port_input).text.toString().ifBlank { getString(R.string.default_remote_port) }
        findViewById<TextView>(R.id.setup_advanced_summary).text =
            getString(R.string.setup_advanced_summary, sshPort.trim(), remotePort.trim())
    }

    private fun setSetupBusy(busy: Boolean) {
        val button = findViewById<Button>(R.id.save_button)
        button.isEnabled = !busy
        button.setText(if (busy) R.string.setup_connecting else R.string.continue_action)
    }

    private fun renderSetup() {
        updateAdvancedSummary()
        bindBanner(findViewById(R.id.setup_error_banner), errorBannerText, detail = null, warning = false)
    }

    /** Continue: save the form, then verify the server identity before Home. */
    private fun verifyIdentityAndGoHome() {
        clearFieldErrors()
        if (!saveForm()) return
        launchJob {
            setSetupBusy(true)
            lastConnectError = null
            val ok = ensureSsh()
            setSetupBusy(false)
            if (ok) {
                errorBannerText = null
                showScreen(Screen.HOME)
            } else {
                errorBannerText = lastConnectError ?: getString(R.string.error_unreachable)
                showScreen(Screen.SETUP)
            }
        }
    }

    private fun clearFieldErrors() {
        findViewById<TextInputLayout>(R.id.password_field).error = null
        findViewById<TextInputLayout>(R.id.web_password_confirm_field).error = null
    }

    private fun saveForm(): Boolean {
        val host = findViewById<EditText>(R.id.host_input).text.toString()
        val username = findViewById<EditText>(R.id.username_input).text.toString()
        val sshPort = parsePort(findViewById<EditText>(R.id.ssh_port_input).text.toString())
        val remotePort = parsePort(findViewById<EditText>(R.id.remote_port_input).text.toString())
        val auth = if (findViewById<MaterialButtonToggleGroup>(R.id.auth_group).checkedButtonId == R.id.auth_key) {
            AuthMethod.KEY
        } else {
            AuthMethod.PASSWORD
        }
        if (!sshHostAllowed(host) || !sshUsernameAllowed(username) || sshPort == null || remotePort == null) {
            snackbar(getString(R.string.error_config_invalid))
            return false
        }
        val typedPassword = findViewById<EditText>(R.id.password_input).text.toString()
        val typedPassphrase = findViewById<EditText>(R.id.passphrase_input).text.toString()
        val existing = runCatching { store.secrets() }.getOrElse { _ ->
            // Do not silently replace an unreadable Keystore blob unless the user
            // actually supplied a full replacement credential for this method.
            val replacing = (auth == AuthMethod.PASSWORD && typedPassword.isNotBlank()) ||
                (auth == AuthMethod.KEY && pendingKey != null)
            if (!replacing) {
                append(getString(R.string.error_credentials_unavailable))
                snackbar(getString(R.string.error_credentials_unavailable))
                return false
            }
            SavedSecrets(null, null, null)
        }
        val password = typedPassword.ifBlank { existing.password }
        val passphrase = typedPassphrase.ifBlank { existing.passphrase }
        val key = pendingKey ?: existing.privateKey
        if (auth == AuthMethod.PASSWORD && password.isNullOrEmpty()) {
            findViewById<TextInputLayout>(R.id.password_field).error = getString(R.string.error_password_required)
            return false
        }
        if (auth == AuthMethod.KEY && key == null) {
            snackbar(getString(R.string.error_key_required))
            return false
        }
        val previous = store.load()
        val changed = profileRequiresNewSession(previous, host, sshPort, username, auth, remotePort)
        try {
            store.saveConnection(
                host,
                sshPort,
                username,
                auth,
                remotePort,
                findViewById<TextView>(R.id.key_name).tag?.toString().orEmpty(),
                SavedSecrets(
                    password = if (auth == AuthMethod.PASSWORD) password else null,
                    privateKey = if (auth == AuthMethod.KEY) key else null,
                    passphrase = if (auth == AuthMethod.KEY) passphrase else null,
                ),
            )
        } catch (error: Exception) {
            append("Could not save SSH credentials: ${error.message ?: "Keystore or storage error"}")
            snackbar(getString(R.string.error_save_failed))
            return false
        }
        if (changed) invalidateConnectionContext()
        if (typedPassword.isNotEmpty()) findViewById<EditText>(R.id.password_input).text.clear()
        if (typedPassphrase.isNotEmpty()) findViewById<EditText>(R.id.passphrase_input).text.clear()
        refreshSurfaces()
        return true
    }

    private fun buildTarget(): SshTarget? {
        if (!saveForm()) return null
        val saved = store.load()
        val secrets = try {
            store.secrets()
        } catch (error: StoredSecretsException) {
            append(getString(R.string.error_credentials_unavailable))
            return null
        }
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
        if (!store.hasConnection()) {
            updateAuthVisibility()
            return
        }
        val saved = store.load()
        findViewById<EditText>(R.id.host_input).setText(saved.host)
        findViewById<EditText>(R.id.ssh_port_input).setText(saved.sshPort.toString())
        findViewById<EditText>(R.id.username_input).setText(saved.username)
        findViewById<EditText>(R.id.remote_port_input).setText(saved.remotePort.toString())
        findViewById<MaterialButtonToggleGroup>(R.id.auth_group)
            .check(if (saved.auth == AuthMethod.KEY) R.id.auth_key else R.id.auth_password)
        val keyName = findViewById<TextView>(R.id.key_name)
        if (saved.keyName.isNotBlank() && saved.keyName != getString(R.string.no_key)) {
            keyName.tag = saved.keyName
            keyName.text = getString(R.string.key_selected, saved.keyName)
        } else {
            keyName.tag = null
            keyName.setText(R.string.no_key)
        }
        updateAuthVisibility()
        refreshSurfaces()
    }

    // ---------------------------------------------------------------------
    // Verify (host-key trust)
    // ---------------------------------------------------------------------

    private fun wireVerify() {
        findViewById<Button>(R.id.verify_approve_button).setOnClickListener { resolveVerify(true) }
        findViewById<Button>(R.id.verify_decline_button).setOnClickListener { resolveVerify(false) }
        findViewById<Button>(R.id.verify_copy_button).setOnClickListener {
            val fingerprint = findViewById<TextView>(R.id.verify_fingerprint_value).text.toString()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("host key fingerprint", fingerprint))
            snackbar(getString(R.string.verify_copied))
        }
    }

    private suspend fun awaitVerify(firstUse: Boolean, keyType: String, fingerprint: String, savedFingerprint: String?): Boolean =
        suspendCancellableCoroutine { cont ->
            verifyContinuation = cont
            screenBeforeVerify = currentScreen
            populateVerify(firstUse, keyType, fingerprint, savedFingerprint)
            showScreen(Screen.VERIFY)
            cont.invokeOnCancellation { verifyContinuation = null }
        }

    private fun resolveVerify(approved: Boolean) {
        val continuation = verifyContinuation ?: return
        verifyContinuation = null
        // The trust decision belongs to the journey that asked for it: return
        // there so a refusal lands on its own failure surface.
        if (currentScreen == Screen.VERIFY) showScreen(screenBeforeVerify)
        if (continuation.isActive) continuation.resume(approved)
    }

    private fun populateVerify(firstUse: Boolean, keyType: String, fingerprint: String, savedFingerprint: String?) {
        val saved = store.load()
        val icon = findViewById<ImageView>(R.id.verify_icon)
        icon.setImageResource(if (firstUse) R.drawable.ic_shield else R.drawable.ic_warning)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(if (firstUse) R.color.primary else R.color.error)))
        findViewById<TextView>(R.id.verify_title)
            .setText(if (firstUse) R.string.host_key_title else R.string.host_key_changed_title)
        findViewById<TextView>(R.id.verify_body)
            .setText(if (firstUse) R.string.verify_first_body else R.string.verify_changed_body)
        findViewById<TextView>(R.id.verify_server_value).text = "${saved.host}:${saved.sshPort}"
        findViewById<TextView>(R.id.verify_keytype_value).text = keyType
        findViewById<View>(R.id.verify_saved_label).visibility = if (firstUse) View.GONE else View.VISIBLE
        val savedValue = findViewById<TextView>(R.id.verify_saved_value)
        savedValue.visibility = if (firstUse) View.GONE else View.VISIBLE
        savedValue.text = savedFingerprint.orEmpty()
        findViewById<TextView>(R.id.verify_fingerprint_label)
            .setText(if (firstUse) R.string.verify_fingerprint_label else R.string.verify_presented_label)
        findViewById<TextView>(R.id.verify_fingerprint_value).text = fingerprint
        findViewById<TextView>(R.id.verify_hint)
            .setText(if (firstUse) R.string.verify_hint else R.string.verify_changed_hint)
        findViewById<Button>(R.id.verify_approve_button)
            .setText(if (firstUse) R.string.approve else R.string.approve_new_key)
    }

    // ---------------------------------------------------------------------
    // Home
    // ---------------------------------------------------------------------

    private fun wireHome() {
        findViewById<Button>(R.id.home_cta).setOnClickListener {
            when (homeUi?.cta) {
                HomeCta.SET_UP -> showScreen(Screen.SETUP)
                HomeCta.INSTALL_AND_LAUNCH -> installFlow()
                HomeCta.OPEN_OPENCODE -> openFlow()
                null -> Unit
            }
        }
        findViewById<Button>(R.id.home_secondary).setOnClickListener {
            when (homeUi?.secondary) {
                HomeSecondary.CONNECT_EXISTING -> connectExistingFlow()
                HomeSecondary.INSTALL_MANAGED -> installFlow()
                HomeSecondary.NONE, null -> Unit
            }
        }
        findViewById<Button>(R.id.home_details_toggle).setOnClickListener {
            homeDetailsExpanded = !homeDetailsExpanded
            findViewById<View>(R.id.home_details_section).visibility =
                if (homeDetailsExpanded) View.VISIBLE else View.GONE
            renderHome()
        }
        findViewById<Button>(R.id.home_overflow_button).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.menu_home_overflow, menu)
                val hasConnection = store.hasConnection()
                menu.findItem(R.id.menu_reconnect).isEnabled = hasConnection
                menu.findItem(R.id.menu_manage).isEnabled = hasConnection
                menu.findItem(R.id.menu_disconnect).isEnabled = hasConnection
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_reconnect -> { reconnectFlow(); true }
                        R.id.menu_manage -> { showScreen(Screen.MANAGE); true }
                        R.id.menu_edit_setup -> { showScreen(Screen.SETUP); true }
                        R.id.menu_disconnect -> { disconnect(); true }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    private fun renderHome() {
        val hasConnection = store.hasConnection()
        val saved = if (hasConnection) store.load() else null
        val ui = HomeStatePolicy.homeUi(
            hasConnection = hasConnection,
            lastMode = store.lastMode(),
            sshConnected = ssh.isConnected(),
            tunnelPort = localPort,
            operationRunning = job?.isActive == true,
            reconnectPending = autoReconnectJob?.isActive == true,
            errorPresent = errorBannerText != null,
        )
        homeUi = ui
        findViewById<TextView>(R.id.home_server_title).text =
            saved?.host ?: getString(R.string.home_identity_unset)
        val subtitle = findViewById<TextView>(R.id.home_server_subtitle)
        if (saved != null) {
            subtitle.text = "${saved.username}@${saved.host}:${saved.sshPort}"
            subtitle.visibility = View.VISIBLE
        } else {
            subtitle.text = ""
            subtitle.visibility = View.GONE
        }
        val (statusText, statusDot) = when (ui.status) {
            ConnectionStatus.NO_SERVER -> R.string.home_status_no_server to R.drawable.dot_idle
            ConnectionStatus.IDLE -> R.string.home_status_idle to R.drawable.dot_idle
            ConnectionStatus.CONNECTING -> R.string.home_status_connecting to R.drawable.dot_warning
            ConnectionStatus.SSH_READY -> R.string.home_status_ssh to R.drawable.dot_success
            ConnectionStatus.TUNNEL_READY -> R.string.home_status_tunnel to R.drawable.dot_success
            ConnectionStatus.RECONNECTING -> R.string.home_status_reconnecting to R.drawable.dot_warning
            ConnectionStatus.ERROR -> R.string.home_status_error to R.drawable.dot_error
        }
        findViewById<TextView>(R.id.home_status_text).setText(statusText)
        findViewById<View>(R.id.home_status_dot).setBackgroundResource(statusDot)
        val cta = findViewById<MaterialButton>(R.id.home_cta)
        cta.setText(
            when (ui.cta) {
                HomeCta.SET_UP -> R.string.cta_setup
                HomeCta.INSTALL_AND_LAUNCH -> R.string.cta_install_launch
                HomeCta.OPEN_OPENCODE -> R.string.cta_open
            }
        )
        cta.setIconResource(if (ui.cta == HomeCta.SET_UP) R.drawable.ic_server else R.drawable.ic_launch)
        val secondary = findViewById<Button>(R.id.home_secondary)
        when (ui.secondary) {
            HomeSecondary.NONE -> secondary.visibility = View.GONE
            HomeSecondary.CONNECT_EXISTING -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.cta_secondary_existing)
            }
            HomeSecondary.INSTALL_MANAGED -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.cta_secondary_install)
            }
        }
        bindBanner(findViewById(R.id.home_error_banner), errorBannerText, detail = null, warning = false)
        findViewById<View>(R.id.home_details_section).visibility =
            if (homeDetailsExpanded) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.home_fingerprint_value).text =
            saved?.fingerprint?.takeIf { it.isNotBlank() } ?: getString(R.string.home_details_fingerprint_unset)
        findViewById<TextView>(R.id.home_tunnel_value).text =
            if (localPort > 0) {
                getString(R.string.home_details_tunnel_value, localPort, tunneledRemotePort)
            } else {
                getString(R.string.home_details_tunnel_none)
            }
        findViewById<TextView>(R.id.home_mode_value).text = when (store.lastMode()) {
            LaunchMode.MANAGED -> getString(R.string.home_mode_managed)
            LaunchMode.EXISTING -> getString(R.string.home_mode_existing)
            null -> getString(R.string.home_mode_none)
        }
        val portLabel = findViewById<View>(R.id.home_remote_port_label)
        val portValue = findViewById<TextView>(R.id.home_remote_port_value)
        if (saved != null) {
            portLabel.visibility = View.VISIBLE
            portValue.visibility = View.VISIBLE
            portValue.text = saved.remotePort.toString()
        } else {
            portLabel.visibility = View.GONE
            portValue.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------
    // Journeys (install / open / reconnect)
    // ---------------------------------------------------------------------

    private fun installFlow() {
        if (!store.hasConnection()) {
            showScreen(Screen.SETUP)
            return
        }
        launchOp(DeployOp.INSTALL, retry = { installFlow() }) {
            lastConnectError = null
            updateProgress { DeployProgress.activate(it, PhaseId.CONNECT_SSH) }
            if (!ensureSsh()) {
                failProgress(lastConnectError)
                return@launchOp
            }
            // WebUI password is created here, sent on SSH stdin, never stored.
            val password = awaitPrepareInstall() ?: return@launchOp
            showScreen(Screen.PROGRESS)
            installNow(password)
        }
    }

    private suspend fun installNow(password: CharArray) {
        var uploadDir: String? = null
        try {
            val script = assets.open("install-opencode.sh").use { it.readBytes() }
            updateProgress { DeployProgress.activate(it, PhaseId.UPLOAD_INSTALLER) }
            val made = withContext(Dispatchers.IO) { ssh.exec(MKTEMP, timeoutMs = 20_000) }
            val dir = made.stdout.trim()
            if (made.exitCode != 0 || !uploadDirAllowed(dir)) {
                failProgress(made.text.ifBlank { "Could not create a remote temporary directory" })
                return
            }
            uploadDir = dir
            val scriptPath = "$dir/install-opencode.sh"
            withContext(Dispatchers.IO) { ssh.upload(script, scriptPath) }
            updateProgress { DeployProgress.activate(it, PhaseId.PREFLIGHT) }
            val preflight = runScript(scriptPath, "preflight", timeoutMs = 60_000)
            if (preflight.exitCode != 0) {
                append("Provisioning failed")
                failProgress("Provisioning failed")
                return
            }
            val secret = password.concatToString()
            val stdin = "$secret\n".toByteArray(Charsets.UTF_8)
            val installed = try {
                runScript(scriptPath, "install", stdin, timeoutMs = 10 * 60_000, secret = secret)
            } finally {
                stdin.fill(0)
            }
            if (installed.exitCode != 0) {
                append("Provisioning failed")
                failProgress("Provisioning failed")
                return
            }
            append("Install finished. HTTP 401 means the service is up and still requires a browser login.")
            openTunnel(MANAGED_PORT, LaunchMode.MANAGED)
        } finally {
            password.fill('\u0000')
            cleanupUploadDir(uploadDir)
        }
    }

    private fun connectExistingFlow() {
        if (!store.hasConnection()) {
            showScreen(Screen.SETUP)
            return
        }
        launchOp(DeployOp.OPEN_EXISTING, retry = { connectExistingFlow() }) {
            lastConnectError = null
            updateProgress { DeployProgress.activate(it, PhaseId.CONNECT_SSH) }
            if (!ensureSsh()) {
                failProgress(lastConnectError)
                return@launchOp
            }
            val port = store.load().remotePort
            append("Connecting to the existing OpenCode service. This does not change it.")
            openTunnel(port, LaunchMode.EXISTING)
        }
    }

    private fun openFlow() {
        if (!store.hasConnection()) {
            showScreen(Screen.SETUP)
            return
        }
        if (localPort > 0 && ssh.isConnected()) {
            showWeb(localPort)
            return
        }
        when (store.lastMode() ?: inferMode()) {
            LaunchMode.MANAGED -> openManagedFlow()
            LaunchMode.EXISTING -> connectExistingFlow()
        }
    }

    private fun openManagedFlow() {
        launchOp(DeployOp.OPEN_MANAGED, retry = { openManagedFlow() }) {
            lastConnectError = null
            updateProgress { DeployProgress.activate(it, PhaseId.CONNECT_SSH) }
            if (!ensureSsh()) {
                failProgress(lastConnectError)
                return@launchOp
            }
            openTunnel(MANAGED_PORT, LaunchMode.MANAGED)
        }
    }

    private fun reconnectFlow() {
        if (!store.hasConnection()) {
            showScreen(Screen.SETUP)
            return
        }
        val mode = store.lastMode() ?: inferMode()
        val op = if (mode == LaunchMode.MANAGED) DeployOp.OPEN_MANAGED else DeployOp.OPEN_EXISTING
        launchOp(op, retry = { reconnectFlow() }) {
            reconnectGate.userConnected()
            wantConnected = true
            val token = reconnectGate.networkChanged() ?: return@launchOp
            autoReconnectJob?.cancel()
            lastConnectError = null
            updateProgress { DeployProgress.activate(it, PhaseId.CONNECT_SSH) }
            withContext(Dispatchers.IO) { ssh.disconnect() }
            destroyWebView()
            localPort = -1
            if (!reconnectGate.stillCurrent(token)) return@launchOp
            if (!ensureSsh()) {
                failProgress(lastConnectError)
                return@launchOp
            }
            if (!reconnectGate.stillCurrent(token)) {
                ssh.disconnect()
                return@launchOp
            }
            val remote = if (mode == LaunchMode.MANAGED) MANAGED_PORT else store.load().remotePort
            openTunnel(remote, mode)
        }
    }

    private fun inferMode(): LaunchMode =
        if (tunneledRemotePort == MANAGED_PORT) LaunchMode.MANAGED else LaunchMode.EXISTING

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
        if (currentScreen == Screen.HOME) renderHome()
    }

    private suspend fun reconnectSerialized(token: Int) {
        tunnelLock.withLock { reconnectNow(token) }
    }

    /** Called only while [tunnelLock] is held. */
    private suspend fun reconnectNow(token: Int) {
        if (!reconnectGate.stillCurrent(token) || !wantConnected) return
        val remote = tunneledRemotePort
        withContext(Dispatchers.IO) { ssh.disconnect() }
        destroyWebView()
        localPort = -1
        if (!reconnectGate.stillCurrent(token)) return
        lastConnectError = null
        if (!ensureSsh()) {
            errorBannerText = lastConnectError
            if (currentScreen == Screen.HOME) renderHome()
            return
        }
        if (!reconnectGate.stillCurrent(token)) {
            ssh.disconnect()
            return
        }
        openTunnel(remote, null)
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
        invalidateTunnelAuthentication()
        acceptedIdentity = null
        append("Disconnected")
        showScreen(Screen.HOME)
    }

    private fun forget() {
        disconnect()
        try {
            store.forget()
        } catch (error: Exception) {
            append("Could not erase saved SSH credentials: ${error.message ?: "storage error"}")
            snackbar(getString(R.string.error_forget_failed))
            return
        }
        // The intro was already seen; forgetting a server must not replay it.
        store.setIntroSeen(true)
        pendingKey?.fill(0)
        pendingKey = null
        tunneledRemotePort = MANAGED_PORT
        findViewById<EditText>(R.id.host_input).text.clear()
        findViewById<EditText>(R.id.username_input).text.clear()
        findViewById<EditText>(R.id.password_input).text.clear()
        findViewById<EditText>(R.id.passphrase_input).text.clear()
        val keyName = findViewById<TextView>(R.id.key_name)
        keyName.tag = null
        keyName.text = getString(R.string.no_key)
        findViewById<MaterialButtonToggleGroup>(R.id.auth_group).check(R.id.auth_password)
        updateAuthVisibility()
        errorBannerText = null
        log.clear()
        renderLogViews()
        showScreen(Screen.SETUP)
    }

    // ---------------------------------------------------------------------
    // SSH trust boundary (unchanged semantics, new surface)
    // ---------------------------------------------------------------------

    private suspend fun ensureSsh(): Boolean {
        // Persist and compare the current form before trusting an existing
        // session; otherwise an edited host could accidentally reuse the prior
        // server's tunnel and WebUI credentials.
        val target = buildTarget() ?: return false
        if (ssh.isConnected()) {
            target.privateKey?.fill(0)
            return true
        }
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
                lastConnectError = getString(R.string.error_auth_failed)
                append("SSH authentication failed. Check the username, password, or private key.")
                false
            }
            is ConnectOutcome.Failed -> {
                stopWantingConnection()
                lastConnectError = getString(R.string.error_unreachable)
                append("SSH failed: ${outcome.message}")
                false
            }
        }
    }

    private suspend fun approveHostKey(outcome: ConnectOutcome.UnknownHostKey): Boolean {
        val approved = awaitVerify(firstUse = true, outcome.keyType, outcome.fingerprint, savedFingerprint = null)
        if (!approved) {
            lastConnectError = getString(R.string.error_host_key_refused)
            append("Host key was not approved.")
            return false
        }
        // A first approval can also follow a profile switch. Never let an old
        // WebView authentication context cross that trust boundary.
        invalidateTunnelAuthentication()
        store.saveHostKey(outcome.key, outcome.keyType, outcome.fingerprint)
        return ensureSsh()
    }

    private suspend fun refuseChangedKey(outcome: ConnectOutcome.ChangedHostKey): Boolean {
        stopWantingConnection()
        val approved = awaitVerify(firstUse = false, outcome.keyType, outcome.fingerprint, outcome.savedFingerprint)
        if (!approved) {
            lastConnectError = getString(R.string.error_host_key_refused)
            append("Host key change refused.")
            return false
        }
        // Host-key rotation establishes a distinct server identity. Destroy the
        // previous WebView and every credential/cache associated with its tunnel
        // before accepting the replacement key and creating a new session.
        invalidateTunnelAuthentication()
        store.saveHostKey(outcome.key, outcome.keyType, outcome.fingerprint)
        return ensureSsh()
    }

    private fun stopWantingConnection() {
        wantConnected = false
        reconnectGate.userDisconnected()
        autoReconnectJob?.cancel()
        localPort = -1
        destroyWebView()
    }

    private suspend fun openTunnel(remotePort: Int, mode: LaunchMode?) {
        syncTunnelIdentity(remotePort)
        updateProgress { DeployProgress.activate(it, PhaseId.PROBE_SERVICE) }
        // Direct-tcpip has no device listener. Verify that the remote service
        // requires HTTP Basic *before* creating a local forward that another app
        // could otherwise reach.
        val probe = withContext(Dispatchers.IO) {
            runCatching { ssh.probeRemoteLoopbackHttp(remotePort) }.getOrDefault(RemoteHttpProbe(-1, emptyList()))
        }
        if (!authenticatedTunnelReady(probe.statusCode, probe.authenticateHeaders)) {
            stopWantingConnection()
            val message = "Tunnel refused: OpenCode must challenge an unauthenticated request with HTTP Basic " +
                "authentication (received HTTP ${probe.statusCode})."
            append(message)
            errorBannerText = message
            failProgress(message)
            if (currentScreen == Screen.WEB) showScreen(Screen.HOME) else renderHome()
            return
        }
        updateProgress { DeployProgress.activate(it, PhaseId.OPEN_TUNNEL) }
        val assigned = withContext(Dispatchers.IO) { ssh.forward(remotePort) }
        localPort = assigned
        tunneledRemotePort = remotePort
        yield()
        reconnectGate.userConnected()
        wantConnected = true
        if (mode != null) store.saveLastMode(mode)
        append("Tunnel 127.0.0.1:$assigned -> 127.0.0.1:$remotePort HTTP ${probe.statusCode}")
        append("Service is up and requires authentication. The browser session is not authenticated yet.")
        hideWebBanner()
        val op = progressOp
        val opensWeb = op != null && WEB_OPENING_OPS.contains(op)
        when {
            currentScreen == Screen.PROGRESS && opensWeb -> {
                updateProgress { DeployProgress.activate(it, PhaseId.LAUNCH_WEB) }
                showWeb(assigned)
                updateProgress { DeployProgress.succeed(it) }
            }
            // Automatic reconnect while the user is looking at the (destroyed)
            // Web surface: restore it exactly where they were.
            currentScreen == Screen.WEB -> showWeb(assigned)
            else -> renderHome()
        }
    }

    private suspend fun runScript(
        path: String,
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Long,
        secret: String? = null,
    ): ExecResult {
        require(command in SCRIPT_COMMANDS)
        val quoted = shellQuote(path) ?: error("unsafe remote path")
        val result = withContext(Dispatchers.IO) {
            ssh.exec("bash $quoted $command", stdin, timeoutMs) { bytes -> streamOutput(bytes, secret) }
        }
        flushStream(secret)
        return result
    }

    private fun streamOutput(bytes: ByteArray, secret: String?) {
        runOnUiThread {
            for (raw in lineStream.accept(bytes)) {
                deliverLine(raw, secret)
            }
        }
    }

    private fun flushStream(secret: String?) {
        for (raw in lineStream.flush()) {
            deliverLine(raw, secret)
        }
    }

    private fun deliverLine(raw: String, secret: String?) {
        val line = if (secret != null) redact(raw, secret) else raw
        append(line)
        updateProgress { DeployProgress.onLine(it, line) }
    }

    private suspend fun cleanupUploadDir(dir: String?) {
        val quoted = dir?.let { shellQuote(it) } ?: return
        withContext(Dispatchers.IO) { runCatching { ssh.exec("rm -rf -- $quoted", timeoutMs = 20_000) } }
    }

    // ---------------------------------------------------------------------
    // Jobs
    // ---------------------------------------------------------------------

    private fun launchJob(block: suspend () -> Unit) {
        if (job?.isActive == true) {
            snackbar(getString(R.string.wait_for_job))
            return
        }
        job = lifecycleScope.launch {
            try {
                // User actions and automatic reconnects must not interleave SSH
                // setup, forwarding, or teardown sequences.
                tunnelLock.withLock { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                append(error.message ?: "Failed")
                failProgress(error.message ?: "Failed")
            }
            onJobFinished()
        }
    }

    private fun launchOp(op: DeployOp, retry: (() -> Unit)?, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            snackbar(getString(R.string.wait_for_job))
            return
        }
        startProgressUi(op, retry)
        launchJob(block)
    }

    private fun onJobFinished() {
        when (currentScreen) {
            Screen.HOME -> renderHome()
            Screen.MANAGE -> renderManage()
            Screen.SETUP -> renderSetup()
            // Prepare cancelled: the journey ends back on Home, no failure.
            Screen.PREPARE_INSTALL -> showScreen(Screen.HOME)
            Screen.PROGRESS -> renderProgress()
            // A job that ended while its trust prompt was up (cancellation)
            // must not strand the user on Verify.
            Screen.VERIFY -> if (verifyContinuation == null) showScreen(screenBeforeVerify) else Unit
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // Prepare install (WebUI password, live requirement checks)
    // ---------------------------------------------------------------------

    private fun wirePrepare() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                findViewById<TextInputLayout>(R.id.web_password_confirm_field).error = null
                updatePrepareRequirements()
            }
        }
        findViewById<EditText>(R.id.web_password_input).addTextChangedListener(watcher)
        findViewById<EditText>(R.id.web_password_confirm_input).addTextChangedListener(watcher)
        findViewById<Button>(R.id.prepare_confirm).setOnClickListener {
            val password = findViewById<EditText>(R.id.web_password_input).text.toString()
            val confirm = findViewById<EditText>(R.id.web_password_confirm_input).text.toString()
            if (!webPasswordAccepted(password) || password != confirm) {
                findViewById<TextInputLayout>(R.id.web_password_confirm_field).error =
                    getString(R.string.prepare_error_mismatch)
                return@setOnClickListener
            }
            finishPrepare(password.toCharArray())
        }
        findViewById<Button>(R.id.prepare_cancel).setOnClickListener { finishPrepare(null) }
    }

    private suspend fun awaitPrepareInstall(): CharArray? = suspendCancellableCoroutine { cont ->
        prepareContinuation = cont
        resetPrepareScreen()
        showScreen(Screen.PREPARE_INSTALL)
        cont.invokeOnCancellation { prepareContinuation = null }
    }

    private fun finishPrepare(password: CharArray?) {
        val continuation = prepareContinuation ?: return
        prepareContinuation = null
        findViewById<EditText>(R.id.web_password_input).text.clear()
        findViewById<EditText>(R.id.web_password_confirm_input).text.clear()
        if (continuation.isActive) continuation.resume(password)
    }

    private fun resetPrepareScreen() {
        findViewById<EditText>(R.id.web_password_input).text.clear()
        findViewById<EditText>(R.id.web_password_confirm_input).text.clear()
        findViewById<TextInputLayout>(R.id.web_password_confirm_field).error = null
        updatePrepareRequirements()
    }

    private fun updatePrepareRequirements() {
        val password = findViewById<EditText>(R.id.web_password_input).text.toString()
        val confirm = findViewById<EditText>(R.id.web_password_confirm_input).text.toString()
        setRequirement(R.id.prepare_req_length_icon, password.length >= MIN_WEB_PASSWORD)
        setRequirement(R.id.prepare_req_letter_icon, password.any { it.isLetter() } && password.any { it.isDigit() })
        setRequirement(R.id.prepare_req_match_icon, confirm.isNotEmpty() && password == confirm)
    }

    private fun setRequirement(iconId: Int, met: Boolean) {
        val icon = findViewById<ImageView>(iconId)
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(color(if (met) R.color.status_success else R.color.on_surface_variant)),
        )
    }

    // ---------------------------------------------------------------------
    // Progress surface
    // ---------------------------------------------------------------------

    private fun wireProgress() {
        findViewById<Button>(R.id.progress_details_toggle).setOnClickListener {
            progressDetailsExpanded = !progressDetailsExpanded
            findViewById<View>(R.id.progress_log_scroll).visibility =
                if (progressDetailsExpanded) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.progress_retry).setOnClickListener {
            if (job?.isActive == true) {
                snackbar(getString(R.string.wait_for_job))
                return@setOnClickListener
            }
            progressRetryAction?.invoke()
        }
        findViewById<Button>(R.id.progress_back_home).setOnClickListener { showScreen(Screen.HOME) }
    }

    private fun startProgressUi(op: DeployOp, retry: (() -> Unit)?) {
        progressOp = op
        progress = DeployProgress.initial(op)
        progressRetryAction = retry
        lineStream = LineStream()
        findViewById<TextView>(R.id.progress_title).setText(titleForOp(op))
        val target = findViewById<TextView>(R.id.progress_target)
        if (store.hasConnection()) {
            val saved = store.load()
            target.text = "${saved.username}@${saved.host}:${saved.sshPort}"
            target.visibility = View.VISIBLE
        } else {
            target.visibility = View.GONE
        }
        val container = findViewById<LinearLayout>(R.id.phase_container)
        container.removeAllViews()
        phaseRows = DeployProgress.phasesFor(op).map { id ->
            val row = layoutInflater.inflate(R.layout.item_phase, container, false)
            row.findViewById<TextView>(R.id.phase_label).setText(labelForPhase(id))
            container.addView(row)
            PhaseRow(
                row.findViewById(R.id.phase_icon),
                row.findViewById(R.id.phase_label),
                row.findViewById(R.id.phase_state),
            )
        }
        findViewById<View>(R.id.progress_linger_card).visibility = View.GONE
        findViewById<View>(R.id.progress_state_card).visibility = View.GONE
        renderProgress()
        showScreen(Screen.PROGRESS)
    }

    private fun updateProgress(transform: (DeployProgressState) -> DeployProgressState) {
        val current = progress ?: return
        progress = transform(current)
        if (currentScreen == Screen.PROGRESS) renderProgress()
    }

    private fun failProgress(message: String?) {
        updateProgress { DeployProgress.fail(it, message) }
    }

    private fun renderProgress() {
        val state = progress ?: return
        stopPhaseAnimator()
        val animations = NavigationPolicy.animationsEnabled(animatorScale())
        state.phases.forEachIndexed { index, phase ->
            val row = phaseRows.getOrNull(index) ?: return@forEachIndexed
            when (phase.state) {
                PhaseState.PENDING -> {
                    row.icon.setImageResource(R.drawable.dot_idle)
                    row.label.alpha = 1f
                    row.state.setText(R.string.phase_state_pending)
                    row.state.setTextColor(color(R.color.on_surface_variant))
                }
                PhaseState.ACTIVE -> {
                    row.icon.setImageResource(R.drawable.ic_refresh)
                    ImageViewCompat.setImageTintList(row.icon, ColorStateList.valueOf(color(R.color.primary)))
                    row.label.alpha = 1f
                    row.state.text = phase.detail?.takeIf { it.isNotBlank() }?.take(60)
                        ?: getString(R.string.phase_state_active)
                    row.state.setTextColor(color(R.color.primary))
                    if (animations) startPhaseAnimator(row.icon)
                }
                PhaseState.DONE -> {
                    row.icon.setImageResource(R.drawable.ic_check_circle)
                    ImageViewCompat.setImageTintList(row.icon, ColorStateList.valueOf(color(R.color.status_success)))
                    row.label.alpha = 1f
                    row.state.text = phase.detail?.takeIf { it.isNotBlank() }?.take(60)
                        ?: getString(R.string.phase_state_done)
                    row.state.setTextColor(color(R.color.on_surface_variant))
                }
                PhaseState.FAILED -> {
                    row.icon.setImageResource(R.drawable.ic_error)
                    ImageViewCompat.setImageTintList(row.icon, ColorStateList.valueOf(color(R.color.status_error)))
                    row.label.alpha = 1f
                    row.state.setText(R.string.phase_state_failed)
                    row.state.setTextColor(color(R.color.status_error))
                }
                PhaseState.SKIPPED -> {
                    row.icon.setImageResource(R.drawable.dot_idle)
                    row.label.alpha = 0.55f
                    row.state.setText(R.string.phase_state_skipped)
                    row.state.setTextColor(color(R.color.on_surface_variant))
                }
            }
        }
        val card = findViewById<MaterialCardView>(R.id.progress_state_card)
        when {
            state.failed -> {
                card.visibility = View.VISIBLE
                val icon = findViewById<ImageView>(R.id.progress_state_icon)
                icon.setImageResource(R.drawable.ic_error)
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(R.color.error)))
                findViewById<TextView>(R.id.progress_state_title).setText(R.string.progress_failed_title)
                findViewById<TextView>(R.id.progress_state_message).text = state.failureMessage.orEmpty()
            }
            state.stopped -> {
                card.visibility = View.VISIBLE
                val icon = findViewById<ImageView>(R.id.progress_state_icon)
                icon.setImageResource(R.drawable.ic_warning)
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(R.color.error)))
                findViewById<TextView>(R.id.progress_state_title).setText(R.string.progress_stopped_title)
                findViewById<TextView>(R.id.progress_state_message).setText(R.string.error_interrupted)
            }
            else -> card.visibility = View.GONE
        }
        findViewById<View>(R.id.progress_linger_card).visibility =
            if (state.lingerNote) View.VISIBLE else View.GONE
        findViewById<View>(R.id.progress_retry).isEnabled = job?.isActive != true
        findViewById<View>(R.id.progress_log_scroll).visibility =
            if (progressDetailsExpanded) View.VISIBLE else View.GONE
        renderLogViews()
    }

    private fun startPhaseAnimator(view: View) {
        activePhaseAnimator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = PHASE_SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPhaseAnimator() {
        activePhaseAnimator?.cancel()
        activePhaseAnimator = null
    }

    private fun animatorScale(): Float = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun confirmStopOperation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_dialog_title)
            .setMessage(R.string.stop_dialog_body)
            .setPositiveButton(R.string.stop_dialog_confirm) { _, _ -> stopOperation() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun stopOperation() {
        job?.cancel()
        autoReconnectJob?.cancel()
        reconnectGate.userDisconnected()
        wantConnected = false
        ssh.disconnect()
        invalidateTunnelAuthentication()
        acceptedIdentity = null
        updateProgress { DeployProgress.stop(it) }
        append("Operation stopped")
        errorBannerText = getString(R.string.error_interrupted)
        showScreen(Screen.HOME)
    }

    private fun titleForOp(op: DeployOp): Int = when (op) {
        DeployOp.INSTALL -> R.string.progress_title_install
        DeployOp.OPEN_MANAGED -> R.string.progress_title_open_managed
        DeployOp.OPEN_EXISTING -> R.string.progress_title_open_existing
        DeployOp.RESTART -> R.string.progress_title_restart
        DeployOp.UPDATE -> R.string.progress_title_update
        DeployOp.UNINSTALL -> R.string.progress_title_uninstall
        DeployOp.STATUS -> R.string.progress_title_status
    }

    private fun labelForPhase(id: PhaseId): Int = when (id) {
        PhaseId.CONNECT_SSH -> R.string.phase_connect_ssh
        PhaseId.UPLOAD_INSTALLER -> R.string.phase_upload_installer
        PhaseId.PREFLIGHT -> R.string.phase_preflight
        PhaseId.DOWNLOAD -> R.string.phase_download
        PhaseId.VERIFY_CHECKSUM -> R.string.phase_verify_checksum
        PhaseId.UNPACK -> R.string.phase_unpack
        PhaseId.VERIFY_BINARY -> R.string.phase_verify_binary
        PhaseId.ACTIVATE -> R.string.phase_activate
        PhaseId.SERVICE_READY -> R.string.phase_service_ready
        PhaseId.RUN_COMMAND -> R.string.phase_run_command
        PhaseId.PROBE_SERVICE -> R.string.phase_probe
        PhaseId.OPEN_TUNNEL -> R.string.phase_tunnel
        PhaseId.REOPEN_TUNNEL -> R.string.phase_reopen_tunnel
        PhaseId.LAUNCH_WEB -> R.string.phase_launch
    }

    // ---------------------------------------------------------------------
    // Web container
    // ---------------------------------------------------------------------

    private fun wireWeb() {
        findViewById<Button>(R.id.web_back_button).setOnClickListener {
            if (webView?.canGoBack() == true) webView?.goBack() else showScreen(Screen.HOME)
        }
        findViewById<Button>(R.id.web_close_button).setOnClickListener { showScreen(Screen.HOME) }
        findViewById<Button>(R.id.web_overflow_button).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.menu_web_overflow, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_refresh -> { webView?.reload(); true }
                        R.id.menu_reconnect -> { reconnectFlow(); true }
                        R.id.menu_manage -> { showScreen(Screen.MANAGE); true }
                        R.id.menu_disconnect -> { disconnect(); true }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    private fun showWeb(port: Int) {
        showScreen(Screen.WEB)
        findViewById<View>(R.id.web_status_dot).setBackgroundResource(R.drawable.dot_success)
        hideWebBanner()
        attachWebView("http://$LOOPBACK:$port/")
    }

    private fun showWebBanner(message: String) {
        if (currentScreen != Screen.WEB) return
        bindBanner(findViewById(R.id.web_banner), message, detail = null, warning = true)
        findViewById<View>(R.id.web_status_dot).setBackgroundResource(R.drawable.dot_warning)
    }

    private fun hideWebBanner() {
        findViewById<View>(R.id.web_banner).visibility = View.GONE
        if (currentScreen == Screen.WEB) {
            findViewById<View>(R.id.web_status_dot).setBackgroundResource(R.drawable.dot_success)
        }
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
            // SAF grants are returned directly to the file-input callback; normal
            // content:// navigations and subresources remain blocked below.
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
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
                    errorBannerText = "WebView renderer crashed repeatedly."
                    showScreen(Screen.HOME)
                } else {
                    lastCrashAt = if (detail.didCrash()) now else 0L
                    if (localPort > 0) attachWebView("http://$LOOPBACK:$localPort/")
                }
                return true
            }
        }
        page.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                findViewById<LinearProgressIndicator>(R.id.web_progress).visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

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
            val identity = acceptedIdentity ?: return@setDownloadListener
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            pendingDownload = PendingDownload(url, mimeType ?: "application/octet-stream", name, identity)
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
        val form = layoutInflater.inflate(R.layout.dialog_basic_auth, null)
        val user = form.findViewById<TextInputEditText>(R.id.auth_username_input)
        val pass = form.findViewById<TextInputEditText>(R.id.auth_password_input)
        user.setText(webUser)
        user.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        pass.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
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
        authDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_title)
            .setView(form)
            .setPositiveButton(R.string.sign_in) { _, _ -> finish(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .show()
    }

    private fun saveDownload(request: PendingDownload, destination: Uri): Boolean =
        runCatching { saveDownloadSafely(request, destination) }.getOrDefault(false)

    private fun saveDownloadSafely(request: PendingDownload, destination: Uri): Boolean {
        if (request.identity != acceptedIdentity) return false
        var current = URL(request.url)
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) {
            val currentUrl = current.toExternalForm()
            if (!loopbackHttp(currentUrl, localPort) || request.identity != acceptedIdentity) return false
            val connection = current.openConnection() as HttpURLConnection
            try {
                // Do not let HttpURLConnection replay Cookie or Authorization to a
                // redirect target. Every hop is validated before it is opened.
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                applyDownloadCredentials(connection, currentUrl)
                when (connection.responseCode) {
                    in 200..299 -> {
                        connection.inputStream.use { input ->
                            contentResolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                                ?: error("no output")
                        }
                        return true
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: return false
                        val next = URL(current, location)
                        if (!downloadRedirectAllowed(currentUrl, next.toExternalForm(), localPort)) return false
                        current = next
                    }
                    else -> return false
                }
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private fun applyDownloadCredentials(connection: HttpURLConnection, url: String) {
        // This method is invoked only after loopbackHttp(url, localPort) succeeds.
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        webPassword?.let { secret ->
            val token = Base64.encodeToString(
                "${webUser}:${secret.concatToString()}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            connection.setRequestProperty("Authorization", "Basic $token")
        }
    }

    // ---------------------------------------------------------------------
    // Management
    // ---------------------------------------------------------------------

    private fun wireManage() {
        findViewById<Button>(R.id.manage_status_button).setOnClickListener { managedFlow("status") }
        findViewById<Button>(R.id.manage_restart_button).setOnClickListener { managedFlow("restart") }
        findViewById<Button>(R.id.manage_update_button).setOnClickListener { managedFlow("update") }
        findViewById<Button>(R.id.manage_reconnect_button).setOnClickListener { reconnectFlow() }
        findViewById<Button>(R.id.manage_edit_button).setOnClickListener { showScreen(Screen.SETUP) }
        findViewById<Button>(R.id.manage_disconnect_button).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.manage_uninstall_button).setOnClickListener {
            confirm(R.string.uninstall_title, R.string.uninstall_body) { managedFlow("uninstall") }
        }
        findViewById<Button>(R.id.manage_forget_button).setOnClickListener {
            confirm(R.string.forget_title, R.string.forget_body) { forget() }
        }
    }

    private fun renderManage() {
        val target = findViewById<TextView>(R.id.manage_target)
        if (store.hasConnection()) {
            val saved = store.load()
            target.text = "${saved.username}@${saved.host}:${saved.sshPort}"
        } else {
            target.text = getString(R.string.home_identity_unset)
        }
        renderLogViews()
    }

    private fun managedFlow(command: String) {
        if (!store.hasConnection()) {
            showScreen(Screen.SETUP)
            return
        }
        val op = when (command) {
            "restart" -> DeployOp.RESTART
            "update" -> DeployOp.UPDATE
            "uninstall" -> DeployOp.UNINSTALL
            else -> DeployOp.STATUS
        }
        launchOp(op, retry = { managedFlow(command) }) {
            lastConnectError = null
            updateProgress { DeployProgress.activate(it, PhaseId.CONNECT_SSH) }
            if (!ensureSsh()) {
                failProgress(lastConnectError)
                return@launchOp
            }
            managedNow(command)
        }
    }

    private suspend fun managedNow(command: String) {
        var uploadDir: String? = null
        try {
            val script = assets.open("install-opencode.sh").use { it.readBytes() }
            updateProgress { DeployProgress.activate(it, PhaseId.UPLOAD_INSTALLER) }
            val made = withContext(Dispatchers.IO) { ssh.exec(MKTEMP, timeoutMs = 20_000) }
            val dir = made.stdout.trim()
            if (made.exitCode != 0 || !uploadDirAllowed(dir)) {
                failProgress(made.text.ifBlank { "Could not create a remote temporary directory" })
                return
            }
            uploadDir = dir
            val scriptPath = "$dir/install-opencode.sh"
            withContext(Dispatchers.IO) { ssh.upload(script, scriptPath) }
            updateProgress { DeployProgress.activate(it, PhaseId.RUN_COMMAND) }
            val result = runScript(scriptPath, command, timeoutMs = 10 * 60_000)
            if (result.exitCode != 0) {
                append("Command failed")
                failProgress("Command failed")
                return
            }
            if (command == "restart" && localPort > 0) {
                updateProgress { DeployProgress.activate(it, PhaseId.REOPEN_TUNNEL) }
                openTunnel(MANAGED_PORT, LaunchMode.MANAGED)
                if (progress?.failed == true) return
            }
            if (command == "uninstall") {
                append("Android connection settings were kept. Forget connection is separate.")
                // The managed service is gone: its tunnel is stale and no
                // journey remains "last opened".
                val hadTunnel = localPort > 0
                stopWantingConnection()
                if (hadTunnel) append("Tunnel closed: the managed service was removed.")
                store.clearLastMode()
            }
            updateProgress { DeployProgress.succeed(it) }
            snackbar(
                getString(
                    when (command) {
                        "restart" -> R.string.managed_done_restart
                        "update" -> R.string.managed_done_update
                        "uninstall" -> R.string.managed_done_uninstall
                        else -> R.string.managed_done_status
                    }
                )
            )
            showScreen(Screen.HOME)
        } finally {
            cleanupUploadDir(uploadDir)
        }
    }

    private fun confirm(title: Int, message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.continue_action) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Shared surfaces: banners, log, snackbar
    // ---------------------------------------------------------------------

    private fun wireBanners() {
        findViewById<View>(R.id.home_error_banner).findViewById<Button>(R.id.banner_dismiss).setOnClickListener {
            errorBannerText = null
            renderHome()
        }
        findViewById<View>(R.id.setup_error_banner).findViewById<Button>(R.id.banner_dismiss).setOnClickListener {
            errorBannerText = null
            renderSetup()
        }
        findViewById<View>(R.id.web_banner).findViewById<Button>(R.id.banner_dismiss).setOnClickListener {
            hideWebBanner()
        }
    }

    private fun bindBanner(root: View, title: String?, detail: String?, warning: Boolean) {
        val card = root as MaterialCardView
        if (title.isNullOrBlank()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        card.setCardBackgroundColor(
            color(if (warning) R.color.banner_warning_background else R.color.error_container)
        )
        val foreground = color(if (warning) R.color.banner_warning_foreground else R.color.on_error_container)
        val icon = root.findViewById<ImageView>(R.id.banner_icon)
        icon.setImageResource(if (warning) R.drawable.ic_warning else R.drawable.ic_error)
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(color(if (warning) R.color.banner_warning_foreground else R.color.error)),
        )
        val titleView = root.findViewById<TextView>(R.id.banner_title)
        titleView.text = title
        titleView.setTextColor(foreground)
        val detailView = root.findViewById<TextView>(R.id.banner_detail)
        if (detail.isNullOrBlank()) {
            detailView.visibility = View.GONE
        } else {
            detailView.visibility = View.VISIBLE
            detailView.text = detail
            detailView.setTextColor(foreground)
        }
        val dismiss = root.findViewById<MaterialButton>(R.id.banner_dismiss)
        dismiss.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun append(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        if (log.isNotEmpty()) log.append('\n')
        log.append(clean)
        if (log.length > 60_000) log.delete(0, log.length - 40_000)
        renderLogViews()
    }

    private fun renderLogViews() {
        val text = log.toString()
        val manageLog = findViewById<TextView>(R.id.manage_log)
        manageLog.text = text.ifEmpty { getString(R.string.manage_log_empty) }
        val progressLog = findViewById<TextView>(R.id.progress_log)
        progressLog.text = text
        if (progressDetailsExpanded && currentScreen == Screen.PROGRESS) {
            findViewById<View>(R.id.progress_log_scroll).post {
                findViewById<android.widget.ScrollView>(R.id.progress_log_scroll).fullScroll(View.FOCUS_DOWN)
            }
        }
        if (currentScreen == Screen.MANAGE) {
            findViewById<View>(R.id.manage_log_scroll).post {
                findViewById<android.widget.ScrollView>(R.id.manage_log_scroll).fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun refreshSurfaces() {
        renderLogViews()
        when (currentScreen) {
            Screen.HOME -> renderHome()
            Screen.MANAGE -> renderManage()
            Screen.SETUP -> renderSetup()
            else -> Unit
        }
    }

    private fun snackbar(message: String) {
        Snackbar.make(findViewById(R.id.root_container), message, Snackbar.LENGTH_LONG).show()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    // ---------------------------------------------------------------------
    // Trust-boundary helpers (unchanged)
    // ---------------------------------------------------------------------

    private fun syncTunnelIdentity(remotePort: Int) {
        val saved = store.load()
        val next = tunnelIdentity(saved.host, saved.sshPort, saved.username, remotePort, saved.hostKey)
        if (acceptedIdentity != null && acceptedIdentity != next) invalidateTunnelAuthentication()
        acceptedIdentity = next
    }

    private fun profileRequiresNewSession(
        previous: SavedConnection,
        host: String,
        sshPort: Int,
        username: String,
        auth: AuthMethod,
        remotePort: Int,
    ): Boolean =
        previous.host != host.trim() ||
            previous.sshPort != sshPort ||
            previous.username != username.trim() ||
            previous.auth != auth ||
            previous.remotePort != remotePort

    /** Clears the WebUI authority state but leaves the SSH decision to the caller. */
    private fun invalidateTunnelAuthentication() {
        pendingDownload = null
        destroyWebView()
        localPort = -1
        clearWebCredentials()
    }

    /** Used when form edits select a different endpoint or SSH principal. */
    private fun invalidateConnectionContext() {
        reconnectGate.userDisconnected()
        wantConnected = false
        autoReconnectJob?.cancel()
        ssh.disconnect()
        invalidateTunnelAuthentication()
        acceptedIdentity = null
    }

    private fun clearWebCredentials() {
        webPassword?.fill('\u0000')
        webPassword = null
        webUser = "opencode"
        usedSavedWebPassword = false
        // removeAllCookies is asynchronous. The deprecated sibling is used at a
        // trust-boundary transition so a new WebView cannot race an old cookie.
        @Suppress("DEPRECATION")
        runCatching { CookieManager.getInstance().removeAllCookie() }
        runCatching { CookieManager.getInstance().flush() }
        @Suppress("DEPRECATION")
        runCatching { WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword() }
    }

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
        findViewById<LinearProgressIndicator>(R.id.web_progress).visibility = View.GONE
    }

    private data class PendingDownload(val url: String, val mimeType: String, val name: String, val identity: String)

    private companion object {
        const val MANAGED_PORT = 4096
        const val RECONNECT_SETTLE_MS = 400L
        const val MAX_KEY = 256 * 1024
        const val MAX_DOWNLOAD_REDIRECTS = 5
        const val MIN_WEB_PASSWORD = 12
        const val PHASE_SPIN_MS = 1200L
        val SCRIPT_COMMANDS = setOf("preflight", "install", "status", "restart", "update", "uninstall")
        val WEB_OPENING_OPS = setOf(DeployOp.INSTALL, DeployOp.OPEN_MANAGED, DeployOp.OPEN_EXISTING)
        val SCREEN_IDS = mapOf(
            Screen.WELCOME to R.id.screen_welcome,
            Screen.SETUP to R.id.screen_setup,
            Screen.VERIFY to R.id.screen_verify,
            Screen.HOME to R.id.screen_home,
            Screen.PREPARE_INSTALL to R.id.screen_prepare,
            Screen.PROGRESS to R.id.screen_progress,
            Screen.WEB to R.id.screen_web,
            Screen.MANAGE to R.id.screen_manage,
        )
        const val MKTEMP =
            "mkdir -p \"\\$HOME/.local/share/anthracite\" && mktemp -d \"\\$HOME/.local/share/anthracite/upload.XXXXXX\""
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
