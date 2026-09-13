package com.avscode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.avscode.about.AboutInfoProvider
import com.avscode.codespaces.CodespaceItem
import com.avscode.core.CodespaceState
import com.avscode.core.AppState
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.StoragePermissionHelper
import com.avscode.ports.PortScanner
import com.avscode.terminal.TerminalSession
import com.avscode.web.AntigravityWebView
import com.avscode.workspace.ArchiveFormat
import com.avscode.workspace.WorkspaceArchiveManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main activity for DroidAntigravity — Antigravity Client for Android.
 *
 * Implements:
 * - Unified Home Dashboard with LOCAL (LinuxDroid) and CODESPACES (GitHub) backends.
 * - Fullscreen Antigravity WebView workspace with responsive zoom and reload controls.
 * - Interactive Linux Terminal PTY shell for recovery and diagnostics.
 * - Dedicated In-App Authentication Dialog for OAuth flows.
 * - Workspace project import and export.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Top-level View Containers
    private lateinit var mainContainer: FrameLayout
    private lateinit var dashboardContainer: ScrollView
    private lateinit var installationContainer: LinearLayout
    private lateinit var editorContainer: LinearLayout
    private lateinit var terminalContainer: LinearLayout

    // Dashboard UI components
    private lateinit var btnSettings: MaterialButton
    private lateinit var storageCard: MaterialCardView
    private lateinit var btnGrantStorage: MaterialButton

    // Local LinuxDroid Card
    private lateinit var cardLocal: MaterialCardView
    private lateinit var tvLocalStatusDesc: TextView
    private lateinit var ivLocalStatusDot: ImageView
    private lateinit var pbLocalStarting: ProgressBar
    private lateinit var btnLocalAction: MaterialButton
    private lateinit var btnLocalStop: MaterialButton

    // GitHub Codespaces Card
    private lateinit var cardCodespaces: MaterialCardView
    private lateinit var tvCodespacesStatus: TextView
    private lateinit var btnAuthGithub: MaterialButton
    private lateinit var btnRefreshCodespaces: MaterialButton
    private lateinit var pbCodespacesLoading: ProgressBar
    private lateinit var layoutCodespacesItems: LinearLayout
    private lateinit var layoutCodespacesEmpty: LinearLayout
    private lateinit var tvCodespacesEmptyMsg: TextView
    private lateinit var btnCodespacesLogin: MaterialButton
    private lateinit var btnNewCodespace: MaterialButton

    // Terminal Card
    private lateinit var cardTerminal: MaterialCardView
    private lateinit var btnOpenTerminal: MaterialButton

    // Dedicated In-App Auth Dialog UI components
    private lateinit var authContainer: LinearLayout
    private lateinit var btnCloseAuth: MaterialButton
    private lateinit var btnCloseAuthAction: MaterialButton
    private lateinit var btnEditorCloseAuth: MaterialButton
    private lateinit var tvAuthTitle: TextView
    private lateinit var authSuccessBanner: LinearLayout
    private lateinit var authWebviewFrame: FrameLayout
    private var authWebView: WebView? = null

    // Open Ports Card
    private lateinit var cardPorts: MaterialCardView
    private lateinit var btnRefreshPorts: MaterialButton
    private lateinit var tvNoOpenPorts: TextView
    private lateinit var layoutPortsList: LinearLayout

    // Workspace Card
    private lateinit var cardWorkspace: MaterialCardView
    private lateinit var tvWorkspacePath: TextView
    private lateinit var tvProjectsSummary: TextView
    private lateinit var btnImportArchive: MaterialButton
    private lateinit var btnExportProject: MaterialButton

    // About Section
    private lateinit var tvAboutAppVer: TextView
    private lateinit var tvAboutDistro: TextView
    private lateinit var tvAboutAntigravity: TextView
    private lateinit var tvAboutAndroid: TextView
    private lateinit var tvAboutDevice: TextView
    private lateinit var tvAboutCpu: TextView

    // Installation Progress UI
    private lateinit var tvInstallTitle: TextView
    private lateinit var tvInstallStep: TextView
    private lateinit var pbInstallProgress: ProgressBar
    private lateinit var tvInstallPercent: TextView
    private lateinit var btnInstallRetry: MaterialButton

    // Editor View (Antigravity Web UI Workspace)
    private lateinit var btnEditorBackDashboard: MaterialButton
    private lateinit var tvEditorTitle: TextView
    private lateinit var btnZoomOut: MaterialButton
    private lateinit var tvZoomLevel: TextView
    private lateinit var btnZoomIn: MaterialButton
    private lateinit var btnToggleDesktop: MaterialButton
    private lateinit var btnEditorRefresh: MaterialButton
    private lateinit var btnEditorToTerminal: MaterialButton
    private lateinit var webviewContainer: FrameLayout

    // Terminal View
    private lateinit var btnTerminalBackDashboard: MaterialButton
    private lateinit var statusBadge: TextView
    private lateinit var btnTerminalToEditor: MaterialButton
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalOutput: TextView
    private lateinit var btnScrollToBottom: MaterialButton
    private lateinit var tvCliPrompt: TextView
    private lateinit var keyEsc: MaterialButton
    private lateinit var keyTab: MaterialButton
    private lateinit var keyCtrl: MaterialButton
    private lateinit var keyAlt: MaterialButton
    private lateinit var keyUp: MaterialButton
    private lateinit var keyDown: MaterialButton
    private lateinit var keyLeft: MaterialButton
    private lateinit var keyRight: MaterialButton
    private lateinit var keyCtrlC: MaterialButton
    private lateinit var keyCtrlX: MaterialButton
    private lateinit var keyCtrlO: MaterialButton
    private lateinit var keyClear: MaterialButton
    private lateinit var cliBar: LinearLayout
    private lateinit var commandInput: EditText
    private lateinit var btnRunCommand: MaterialButton

    // Managers & Controllers
    private lateinit var runtimeController: RuntimeController
    private lateinit var webViewManager: AntigravityWebView
    private lateinit var portScanner: PortScanner
    private lateinit var workspaceArchiveManager: WorkspaceArchiveManager
    private lateinit var aboutInfoProvider: AboutInfoProvider
    private lateinit var terminalSession: TerminalSession
    private var webViewAttached = false
    private var isCtrlActive = false
    private var isAltActive = false
    private var portsAutoRefreshJob: Job? = null

    // Pending export state
    private var pendingExportProject: File? = null
    private var pendingExportFormat: ArchiveFormat? = null

    private val importArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleImportArchive(it) }
    }

    private val exportArchiveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        uri?.let { destUri ->
            val project = pendingExportProject
            val format = pendingExportFormat
            if (project != null && format != null) {
                handleExportProject(project, format, destUri)
            }
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                AvsLogger.i(TAG, "Notification permission granted")
            } else {
                AvsLogger.d(TAG, "Notification permission denied or dismissed")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        AvsLogger.i(TAG, "MainActivity created")

        initViews()
        setupWindowInsets()
        checkNotificationPermission()

        runtimeController = RuntimeController.getInstance(this)
        webViewManager = AntigravityWebView(this)
        portScanner = PortScanner()
        workspaceArchiveManager = WorkspaceArchiveManager(runtimeController.paths.hostProjectsDir)
        aboutInfoProvider = AboutInfoProvider(this)
        terminalSession = TerminalSession(runtimeController.linuxRuntime)

        updateAboutSection()
        updateWorkspaceSummary()

        terminalOutput.text = terminalSession.buffer.render()
        tvCliPrompt.text = terminalSession.getPrompt()

        tvZoomLevel.text = "${webViewManager.currentZoomLevel}%"
        webViewManager.onZoomChanged = { level ->
            runOnUiThread { tvZoomLevel.text = "$level%" }
        }
        webViewManager.onDesktopModeChanged = { isDesktop ->
            runOnUiThread { updateDesktopButtonState(isDesktop) }
        }
        updateDesktopButtonState(webViewManager.isDesktopMode)

        startPortsAutoRefresh()

        runtimeController.onAuthRequestTriggered = { requestId, authUrl, title ->
            runOnUiThread {
                try {
                    AvsLogger.i(TAG, "Opening dedicated in-app Auth Dialog for request $requestId: $authUrl")
                    val msg = title ?: "Authentication requested..."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    showAuthContainer(authUrl, title)
                } catch (e: Exception) {
                    AvsLogger.w(TAG, "Failed to load auth URL: ${e.message}")
                }
            }
        }

        webViewManager.onAuthCallbackReceived = { uri ->
            handleAuthCallbackUri(uri)
        }

        webViewManager.onExternalUrlRequested = { url ->
            runOnUiThread {
                if (AntigravityWebView.isAuthUrl(url)) {
                    showAuthContainer(url, getString(R.string.auth_title))
                } else {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        AvsLogger.w(TAG, "No external browser available: ${e.message}")
                        showAuthContainer(url, getString(R.string.auth_title))
                    }
                }
            }
            true
        }

        webViewManager.onConnectionError = { err ->
            AvsLogger.w(TAG, "[WebView] Connection error: $err")
        }

        observeRuntimeState()
        observeTerminalLogs()
        loadCodespaces()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authContainer.visibility == View.VISIBLE) {
                    if (authWebView?.canGoBack() == true) {
                        authWebView?.goBack()
                    } else {
                        hideAuthContainer()
                        if (runtimeController.appState.value is AppState.Ready) {
                            showEditorView()
                        }
                    }
                    return
                }

                if (editorContainer.visibility == View.VISIBLE) {
                    showDashboardView()
                    return
                }

                if (terminalContainer.visibility == View.VISIBLE) {
                    showDashboardView()
                    return
                }

                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        handleIncomingAuthIntent(intent)

        lifecycleScope.launch {
            if (StoragePermissionHelper.isStorageConfigured(this@MainActivity)) {
                if (runtimeController.appState.value !is AppState.Ready) {
                    runtimeController.startAll()
                }
            } else {
                showStoragePermissionCard()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingAuthIntent(intent)
    }

    private fun handleAuthCallbackUri(data: Uri): Boolean {
        AvsLogger.i(TAG, "Processing auth callback: $data")
        val code = data.getQueryParameter("code")
        val token = data.getQueryParameter("token")
        val requestId = data.getQueryParameter("requestId") ?: data.getQueryParameter("state")
        if (requestId != null) {
            runtimeController.authBridgeServer.completeSession(requestId, code, token)
        }

        CookieManager.getInstance().flush()

        runOnUiThread {
            hideAuthContainer()
            showEditorView()
            Toast.makeText(this, R.string.auth_complete_msg, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun handleIncomingAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (AntigravityWebView.isAuthCallback(data)) {
            handleAuthCallbackUri(data)
            showEditorView()
        }
    }

    private fun initViews() {
        mainContainer = findViewById(R.id.main_container)
        dashboardContainer = findViewById(R.id.dashboard_container)
        installationContainer = findViewById(R.id.installation_container)
        editorContainer = findViewById(R.id.editor_container)
        terminalContainer = findViewById(R.id.terminal_container)

        // Top App Bar & Storage
        btnSettings = findViewById(R.id.btn_settings)
        storageCard = findViewById(R.id.storage_card)
        btnGrantStorage = findViewById(R.id.btn_grant_storage)

        // Local LinuxDroid Card
        cardLocal = findViewById(R.id.card_local)
        tvLocalStatusDesc = findViewById(R.id.tv_local_status_desc)
        ivLocalStatusDot = findViewById(R.id.iv_local_status_dot)
        pbLocalStarting = findViewById(R.id.pb_local_starting)
        btnLocalAction = findViewById(R.id.btn_local_action)
        btnLocalStop = findViewById(R.id.btn_local_stop)

        // GitHub Codespaces Card
        cardCodespaces = findViewById(R.id.card_codespaces)
        tvCodespacesStatus = findViewById(R.id.tv_codespaces_status)
        btnAuthGithub = findViewById(R.id.btn_auth_github)
        btnRefreshCodespaces = findViewById(R.id.btn_refresh_codespaces)
        pbCodespacesLoading = findViewById(R.id.pb_codespaces_loading)
        layoutCodespacesItems = findViewById(R.id.layout_codespaces_items)
        layoutCodespacesEmpty = findViewById(R.id.layout_codespaces_empty)
        tvCodespacesEmptyMsg = findViewById(R.id.tv_codespaces_empty_msg)
        btnCodespacesLogin = findViewById(R.id.btn_codespaces_login)
        btnNewCodespace = findViewById(R.id.btn_new_codespace)

        // Terminal Card
        cardTerminal = findViewById(R.id.card_terminal)
        btnOpenTerminal = findViewById(R.id.btn_open_terminal)

        // Dedicated In-App Auth Dialog UI components
        authContainer = findViewById(R.id.auth_container)
        btnCloseAuth = findViewById(R.id.btn_close_auth)
        btnCloseAuthAction = findViewById(R.id.btn_close_auth_action)
        btnEditorCloseAuth = findViewById(R.id.btn_editor_close_auth)
        tvAuthTitle = findViewById(R.id.tv_auth_title)
        authSuccessBanner = findViewById(R.id.auth_success_banner)
        authWebviewFrame = findViewById(R.id.auth_webview_frame)

        // Open Ports Card
        cardPorts = findViewById(R.id.card_ports)
        btnRefreshPorts = findViewById(R.id.btn_refresh_ports)
        tvNoOpenPorts = findViewById(R.id.tv_no_open_ports)
        layoutPortsList = findViewById(R.id.layout_ports_list)

        // Workspace Card
        cardWorkspace = findViewById(R.id.card_workspace)
        tvWorkspacePath = findViewById(R.id.tv_workspace_path)
        tvProjectsSummary = findViewById(R.id.tv_projects_summary)
        btnImportArchive = findViewById(R.id.btn_import_archive)
        btnExportProject = findViewById(R.id.btn_export_project)

        // About Section
        tvAboutAppVer = findViewById(R.id.tv_about_app_ver)
        tvAboutDistro = findViewById(R.id.tv_about_distro)
        tvAboutAntigravity = findViewById(R.id.tv_about_antigravity)
        tvAboutAndroid = findViewById(R.id.tv_about_android)
        tvAboutDevice = findViewById(R.id.tv_about_device)
        tvAboutCpu = findViewById(R.id.tv_about_cpu)

        // Installation UI
        tvInstallTitle = findViewById(R.id.tv_install_title)
        tvInstallStep = findViewById(R.id.tv_install_step)
        pbInstallProgress = findViewById(R.id.pb_install_progress)
        tvInstallPercent = findViewById(R.id.tv_install_percent)
        btnInstallRetry = findViewById(R.id.btn_install_retry)

        // Editor View
        btnEditorBackDashboard = findViewById(R.id.btn_editor_back_dashboard)
        tvEditorTitle = findViewById(R.id.tv_editor_title)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        tvZoomLevel = findViewById(R.id.tv_zoom_level)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnToggleDesktop = findViewById(R.id.btn_toggle_desktop)
        btnEditorRefresh = findViewById(R.id.btn_editor_refresh)
        btnEditorToTerminal = findViewById(R.id.btn_editor_to_terminal)
        webviewContainer = findViewById(R.id.webview_container)

        // Terminal View
        btnTerminalBackDashboard = findViewById(R.id.btn_terminal_back_dashboard)
        statusBadge = findViewById(R.id.status_badge)
        btnTerminalToEditor = findViewById(R.id.btn_terminal_to_editor)
        terminalScroll = findViewById(R.id.terminal_scroll)
        terminalOutput = findViewById(R.id.terminal_output)
        btnScrollToBottom = findViewById(R.id.btn_scroll_to_bottom)
        tvCliPrompt = findViewById(R.id.tv_cli_prompt)
        keyEsc = findViewById(R.id.key_esc)
        keyTab = findViewById(R.id.key_tab)
        keyCtrl = findViewById(R.id.key_ctrl)
        keyAlt = findViewById(R.id.key_alt)
        keyUp = findViewById(R.id.key_up)
        keyDown = findViewById(R.id.key_down)
        keyLeft = findViewById(R.id.key_left)
        keyRight = findViewById(R.id.key_right)
        keyCtrlC = findViewById(R.id.key_ctrl_c)
        keyCtrlX = findViewById(R.id.key_ctrl_x)
        keyCtrlO = findViewById(R.id.key_ctrl_o)
        keyClear = findViewById(R.id.key_clear)
        cliBar = findViewById(R.id.cli_bar)
        commandInput = findViewById(R.id.command_input)
        btnRunCommand = findViewById(R.id.btn_run_command)

        // Wire Click Listeners
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnGrantStorage.setOnClickListener { handleGrantStorageAccess() }

        btnLocalAction.setOnClickListener { handleLocalActionClick() }
        btnLocalStop.setOnClickListener { handleLocalStopClick() }

        btnAuthGithub.setOnClickListener { showGitHubTokenDialog() }
        btnRefreshCodespaces.setOnClickListener { loadCodespaces() }
        btnCodespacesLogin.setOnClickListener { showGitHubTokenDialog() }
        btnNewCodespace.setOnClickListener { showNewCodespaceDialog() }

        btnCloseAuth.setOnClickListener { hideAuthContainer(); if (runtimeController.appState.value is AppState.Ready) showEditorView() }
        btnCloseAuthAction.setOnClickListener { hideAuthContainer(); if (runtimeController.appState.value is AppState.Ready) showEditorView() }

        btnOpenTerminal.setOnClickListener {
            showTerminalView()
            lifecycleScope.launch { runtimeController.ensureLinuxStarted() }
        }

        btnRefreshPorts.setOnClickListener { updatePortsList() }
        btnImportArchive.setOnClickListener { importArchiveLauncher.launch(arrayOf("*/*")) }
        btnExportProject.setOnClickListener { showExportProjectDialog() }
        btnInstallRetry.setOnClickListener { lifecycleScope.launch { runtimeController.startAll(forceRestart = true) } }

        btnEditorBackDashboard.setOnClickListener { showDashboardView() }
        btnEditorRefresh.setOnClickListener { webViewManager.reload() }
        btnEditorToTerminal.setOnClickListener { showTerminalView() }
        btnTerminalBackDashboard.setOnClickListener { showDashboardView() }
        btnTerminalToEditor.setOnClickListener { showEditorView() }
        btnScrollToBottom.setOnClickListener { scrollTerminalToBottom(); btnScrollToBottom.visibility = View.GONE }

        btnZoomIn.setOnClickListener { webViewManager.zoomIn() }
        btnZoomOut.setOnClickListener { webViewManager.zoomOut() }
        tvZoomLevel.setOnClickListener { webViewManager.resetZoom() }
        btnToggleDesktop.setOnClickListener { webViewManager.toggleDesktopMode() }

        keyEsc.setOnClickListener { commandInput.append("\u001B") }
        keyTab.setOnClickListener {
            val text = commandInput.text.toString()
            if (text.isNotEmpty() && !text.endsWith(" ")) {
                commandInput.append(" ")
            } else {
                commandInput.append("\t")
            }
        }

        keyCtrl.setOnClickListener {
            isCtrlActive = !isCtrlActive
            keyCtrl.isSelected = isCtrlActive
            keyCtrl.setTextColor(if (isCtrlActive) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        }

        keyAlt.setOnClickListener {
            isAltActive = !isAltActive
            keyAlt.isSelected = isAltActive
            keyAlt.setTextColor(if (isAltActive) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        }

        keyUp.setOnClickListener {
            terminalSession.navigateHistory(up = true)?.let { prev ->
                commandInput.setText(prev)
                commandInput.setSelection(prev.length)
            }
        }

        keyDown.setOnClickListener {
            terminalSession.navigateHistory(up = false)?.let { next ->
                commandInput.setText(next)
                commandInput.setSelection(next.length)
            }
        }

        keyLeft.setOnClickListener {
            val pos = (commandInput.selectionStart - 1).coerceAtLeast(0)
            commandInput.setSelection(pos)
        }

        keyRight.setOnClickListener {
            val pos = (commandInput.selectionStart + 1).coerceAtMost(commandInput.text.length)
            commandInput.setSelection(pos)
        }

        keyCtrlC.setOnClickListener {
            if (commandInput.text.isNotEmpty()) {
                commandInput.setText("")
            } else {
                lifecycleScope.launch {
                    terminalSession.executeCommand("^C") { rendered ->
                        runOnUiThread { updateTerminalDisplay(rendered) }
                    }
                }
            }
        }

        keyCtrlX.setOnClickListener { commandInput.append("\u0018") }
        keyCtrlO.setOnClickListener { commandInput.append("\u000F") }
        keyClear.setOnClickListener {
            lifecycleScope.launch {
                terminalSession.executeCommand("clear") { rendered ->
                    runOnUiThread { updateTerminalDisplay(rendered) }
                }
            }
        }

        btnRunCommand.setOnClickListener { submitCommand() }

        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val basePad = (12 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            dashboardContainer.setPadding(insets.left, insets.top, insets.right, maxOf(insets.bottom, ime.bottom))
            installationContainer.setPadding(insets.left, insets.top, insets.right, maxOf(insets.bottom, ime.bottom))
            terminalContainer.setPadding(basePad + insets.left, basePad + insets.top, basePad + insets.right, basePad + maxOf(insets.bottom, ime.bottom))
            editorContainer.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            authContainer.setPadding(insets.left, insets.top, insets.right, maxOf(insets.bottom, ime.bottom))
            windowInsets
        }
    }

    private fun showDashboardView() {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.VISIBLE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.GONE
        updatePortsList()
        updateWorkspaceSummary()
    }

    private fun showEditorView() {
        authContainer.visibility = View.GONE
        val state = runtimeController.appState.value
        val url = if (state is AppState.Ready) state.url else runtimeController.activeServerUrl
        if (url != null) {
            attachAndLoadWebView(url)
            val title = if (state is AppState.Ready) state.title else "DroidAntigravity"
            tvEditorTitle.text = title
        }
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.VISIBLE
        terminalContainer.visibility = View.GONE
    }

    private fun showTerminalView() {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.VISIBLE
        tvCliPrompt.text = terminalSession.getPrompt()
        scrollTerminalToBottom()
    }

    private fun showInstallationView(title: String, step: String, progress: Int, isIndeterminate: Boolean = false) {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.VISIBLE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.GONE

        tvInstallTitle.text = title
        tvInstallStep.text = step
        pbInstallProgress.isIndeterminate = isIndeterminate
        if (!isIndeterminate) {
            pbInstallProgress.progress = progress
            tvInstallPercent.text = "$progress%"
        } else {
            tvInstallPercent.text = ""
        }
        btnInstallRetry.visibility = View.GONE
    }

    private fun showAuthContainer(url: String, title: String?) {
        tvAuthTitle.text = title ?: getString(R.string.auth_title)
        authSuccessBanner.visibility = View.GONE
        authContainer.visibility = View.VISIBLE

        if (authWebView == null) {
            val webView = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = AntigravityWebView.buildDesktopUserAgent(this@MainActivity)
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        if (AntigravityWebView.isAuthCallback(uri)) {
                            handleAuthCallbackUri(uri)
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val uri = url?.let { Uri.parse(it) }
                        if (uri != null && AntigravityWebView.isAuthCallback(uri)) {
                            handleAuthCallbackUri(uri)
                        }
                    }
                }
            }
            authWebView = webView
            authWebviewFrame.removeAllViews()
            authWebviewFrame.addView(webView)
        }
        authWebView?.loadUrl(url)
    }

    private fun hideAuthContainer() {
        authContainer.visibility = View.GONE
        authSuccessBanner.visibility = View.GONE
        authWebView?.stopLoading()
        authWebView?.loadUrl("about:blank")
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleGrantStorageAccess() {
        val rootfsDir = runtimeController.paths.rootfsDir
        StoragePermissionHelper.verifyStorageAccessible(rootfsDir)
        StoragePermissionHelper.markStorageConfigured(this, rootfsDir.absolutePath)
        storageCard.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!StoragePermissionHelper.hasManageExternalStoragePermission()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    AvsLogger.d(TAG, "External storage settings intent not available: ${e.message}")
                }
            }
        }

        appendTerminalLine("[Android] Storage access configured for: ${rootfsDir.absolutePath}")
        lifecycleScope.launch {
            runtimeController.startAll()
        }
    }

    private fun showStoragePermissionCard() {
        storageCard.visibility = View.VISIBLE
    }

    private fun handleLocalActionClick() {
        val state = runtimeController.appState.value
        when {
            state is AppState.Ready -> {
                showEditorView()
            }
            state.isFailed -> {
                lifecycleScope.launch {
                    runtimeController.startAll(forceRestart = true)
                }
            }
            else -> {
                lifecycleScope.launch {
                    runtimeController.startAntigravityServer()
                }
            }
        }
    }

    private fun handleLocalStopClick() {
        lifecycleScope.launch {
            btnLocalStop.isEnabled = false
            btnLocalAction.isEnabled = false
            runtimeController.stopAntigravityServer()
        }
    }

    // =========================================================================
    // GitHub Codespaces Integration
    // =========================================================================

    private fun loadCodespaces() {
        val mgr = runtimeController.codespaceManager
        if (!mgr.isAuthenticated()) {
            layoutCodespacesEmpty.visibility = View.VISIBLE
            layoutCodespacesItems.visibility = View.GONE
            tvCodespacesStatus.text = getString(R.string.github_auth_required)
            tvCodespacesEmptyMsg.text = getString(R.string.github_auth_required)
            return
        }

        layoutCodespacesEmpty.visibility = View.GONE
        pbCodespacesLoading.visibility = View.VISIBLE
        tvCodespacesStatus.text = "Fetching Codespaces..."

        lifecycleScope.launch {
            val result = mgr.listCodespaces()
            pbCodespacesLoading.visibility = View.GONE

            if (result is Result.Success) {
                val list = result.data
                tvCodespacesStatus.text = "${list.size} Codespace${if (list.size == 1) "" else "s"}"
                layoutCodespacesItems.removeAllViews()

                if (list.isEmpty()) {
                    layoutCodespacesEmpty.visibility = View.VISIBLE
                    tvCodespacesEmptyMsg.text = getString(R.string.no_codespaces_found)
                    btnCodespacesLogin.visibility = View.GONE
                    layoutCodespacesItems.visibility = View.GONE
                } else {
                    layoutCodespacesEmpty.visibility = View.GONE
                    layoutCodespacesItems.visibility = View.VISIBLE

                    for (item in list) {
                        val itemView = layoutInflater.inflate(R.layout.item_codespace, layoutCodespacesItems, false)
                        val tvName = itemView.findViewById<TextView>(R.id.codespace_name)
                        val tvRepo = itemView.findViewById<TextView>(R.id.codespace_repo)
                        val tvState = itemView.findViewById<TextView>(R.id.codespace_state_label)
                        val dot = itemView.findViewById<View>(R.id.codespace_status_dot)
                        val btnAction = itemView.findViewById<MaterialButton>(R.id.btn_codespace_action)
                        val btnStop = itemView.findViewById<MaterialButton>(R.id.btn_codespace_stop)

                        tvName.text = item.name
                        tvRepo.text = item.repositoryFullName.ifEmpty { item.repositoryName }
                        tvState.text = item.state.name

                        // Status dot color
                        when {
                            item.isRunning -> {
                                dot.setBackgroundColor(0xFF4CAF50.toInt()) // Green
                                btnAction.text = getString(R.string.codespace_open)
                                btnStop.visibility = View.VISIBLE
                            }
                            item.isBusy -> {
                                dot.setBackgroundColor(0xFFFF9800.toInt()) // Orange
                                btnAction.text = "Waiting..."
                                btnAction.isEnabled = false
                                btnStop.visibility = View.GONE
                            }
                            else -> {
                                dot.setBackgroundColor(0xFF888888.toInt()) // Gray
                                btnAction.text = getString(R.string.codespace_start)
                                btnStop.visibility = View.GONE
                            }
                        }

                        btnAction.setOnClickListener {
                            openOrStartCodespace(item)
                        }

                        btnStop.setOnClickListener {
                            lifecycleScope.launch {
                                btnStop.isEnabled = false
                                mgr.stopCodespace(item.name)
                                loadCodespaces()
                            }
                        }

                        layoutCodespacesItems.addView(itemView)
                    }
                }
            } else {
                layoutCodespacesEmpty.visibility = View.VISIBLE
                val err = (result as Result.Failure).message ?: "Failed to load Codespaces"
                tvCodespacesEmptyMsg.text = err
                tvCodespacesStatus.text = "Connection error"
            }
        }
    }

    private fun openOrStartCodespace(item: CodespaceItem) {
        pbCodespacesLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val connectResult = runtimeController.connectCodespace(item)
            pbCodespacesLoading.visibility = View.GONE
            if (connectResult is Result.Success) {
                val url = connectResult.data
                attachAndLoadWebView(url)
                tvEditorTitle.text = item.repositoryName.ifEmpty { item.name }
                showEditorView()
            } else {
                val err = (connectResult as Result.Failure).message ?: "Connection failed"
                Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                loadCodespaces()
            }
        }
    }

    private fun showGitHubTokenDialog() {
        val currentToken = runtimeController.codespaceManager.getGitHubToken().orEmpty()
        val input = EditText(this).apply {
            hint = getString(R.string.github_token_hint)
            setText(currentToken)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.github_login)
            .setMessage("Enter a GitHub Personal Access Token (PAT) with 'codespace' and 'repo' scopes:")
            .setView(input)
            .setPositiveButton(R.string.save_token) { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    runtimeController.codespaceManager.saveGitHubToken(token)
                    loadCodespaces()
                } else {
                    runtimeController.codespaceManager.clearGitHubToken()
                    loadCodespaces()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewCodespaceDialog() {
        val input = EditText(this).apply {
            hint = "owner/repository (e.g. user/my-project)"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_codespace)
            .setMessage("Enter the GitHub repository to create a new Codespace for:")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val repoFull = input.text.toString().trim()
                if (repoFull.contains("/")) {
                    val parts = repoFull.split("/")
                    val owner = parts[0]
                    val repo = parts[1]
                    pbCodespacesLoading.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val result = runtimeController.codespaceManager.createCodespace(owner, repo)
                        pbCodespacesLoading.visibility = View.GONE
                        if (result is Result.Success) {
                            Toast.makeText(this@MainActivity, "Codespace created: ${result.data.name}", Toast.LENGTH_SHORT).show()
                            loadCodespaces()
                        } else {
                            val err = (result as Result.Failure).message ?: "Failed to create Codespace"
                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Please enter in format owner/repo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // =========================================================================
    // State Observation
    // =========================================================================

    private fun observeRuntimeState() {
        lifecycleScope.launch {
            runtimeController.appState.collectLatest { state ->
                AvsLogger.d(TAG, "Observed AppState: $state")

                when (state) {
                    is AppState.NeedsStorageAccess -> {
                        showStoragePermissionCard()
                        showDashboardView()
                    }
                    is AppState.NotInstalled -> {
                        showDashboardView()
                        tvLocalStatusDesc.text = getString(R.string.antigravity_status_stopped)
                        btnLocalAction.text = getString(R.string.open_local)
                        btnLocalAction.setIconResource(R.drawable.ic_play_arrow)
                        btnLocalAction.isEnabled = true
                        btnLocalStop.visibility = View.GONE
                        pbLocalStarting.visibility = View.GONE
                        ivLocalStatusDot.visibility = View.GONE
                    }
                    is AppState.DownloadingRootfs -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = getString(R.string.downloading_rootfs),
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.ExtractingRootfs -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = getString(R.string.extracting_rootfs),
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.RootfsReady -> {
                        showInstallationView(
                            title = getString(R.string.installing_linux),
                            step = "Ubuntu rootfs ready.",
                            progress = 100
                        )
                    }
                    is AppState.StartingLinux -> {
                        btnLocalStop.visibility = View.GONE
                        if (installationContainer.visibility == View.VISIBLE) {
                            showInstallationView(
                                title = getString(R.string.starting_linux),
                                step = "Starting Linux PRoot runtime...",
                                progress = 100,
                                isIndeterminate = true
                            )
                        } else {
                            tvLocalStatusDesc.text = getString(R.string.starting_linux)
                            pbLocalStarting.visibility = View.VISIBLE
                            btnLocalAction.text = "Starting..."
                            btnLocalAction.isEnabled = false
                        }
                    }
                    is AppState.VerifyingLinux -> {
                        if (installationContainer.visibility == View.VISIBLE) {
                            showInstallationView(
                                title = getString(R.string.installing_linux),
                                step = "Verifying Linux environment...",
                                progress = 100,
                                isIndeterminate = true
                            )
                        }
                    }
                    is AppState.LinuxReady -> {
                        if (installationContainer.visibility == View.VISIBLE) {
                            showDashboardView()
                        }
                        statusBadge.text = "ONLINE"
                        tvLocalStatusDesc.text = getString(R.string.antigravity_status_stopped)
                        btnLocalAction.text = getString(R.string.open_local)
                        btnLocalAction.setIconResource(R.drawable.ic_play_arrow)
                        btnLocalAction.isEnabled = true
                        btnLocalStop.visibility = View.GONE
                        pbLocalStarting.visibility = View.GONE
                        ivLocalStatusDot.visibility = View.GONE
                    }
                    is AppState.InstallingPackages -> {
                        showInstallationView(
                            title = getString(R.string.configuring_environment),
                            step = state.status,
                            progress = 50,
                            isIndeterminate = true
                        )
                    }
                    is AppState.InstallingAntigravity -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = "Setting up Antigravity...",
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.AntigravityReady -> {
                        if (installationContainer.visibility == View.VISIBLE) {
                            showDashboardView()
                        }
                        tvLocalStatusDesc.text = getString(R.string.antigravity_status_stopped)
                        btnLocalAction.text = getString(R.string.open_local)
                        btnLocalAction.setIconResource(R.drawable.ic_play_arrow)
                        btnLocalAction.isEnabled = true
                        btnLocalStop.visibility = View.GONE
                        pbLocalStarting.visibility = View.GONE
                        ivLocalStatusDot.visibility = View.GONE
                    }
                    is AppState.StartingAuthBridge -> {
                        tvLocalStatusDesc.text = "Starting Auth Bridge..."
                        pbLocalStarting.visibility = View.VISIBLE
                        btnLocalAction.text = "Starting..."
                        btnLocalAction.isEnabled = false
                    }
                    is AppState.StartingAntigravityServer -> {
                        tvLocalStatusDesc.text = getString(R.string.starting_antigravity)
                        pbLocalStarting.visibility = View.VISIBLE
                        btnLocalAction.text = "Starting..."
                        btnLocalAction.isEnabled = false
                    }
                    is AppState.Ready -> {
                        val sPort = runtimeController.serverPort
                        webViewManager.loadEndpoint(state.url, sPort)
                        attachAndLoadWebView(state.url)

                        tvLocalStatusDesc.text = getString(R.string.antigravity_status_running, state.url)
                        ivLocalStatusDot.visibility = View.VISIBLE
                        pbLocalStarting.visibility = View.GONE
                        btnLocalAction.text = getString(R.string.open_local)
                        btnLocalAction.setIconResource(R.drawable.ic_launch)
                        btnLocalAction.isEnabled = true
                        btnLocalStop.visibility = View.VISIBLE
                        btnLocalStop.isEnabled = true
                        statusBadge.text = "READY"

                        updatePortsList()

                        if (installationContainer.visibility == View.VISIBLE) {
                            showEditorView()
                        }
                    }
                    is AppState.Stopping -> {
                        tvLocalStatusDesc.text = getString(R.string.stopping_antigravity)
                        ivLocalStatusDot.visibility = View.GONE
                        pbLocalStarting.visibility = View.VISIBLE
                        btnLocalAction.isEnabled = false
                        btnLocalStop.visibility = View.VISIBLE
                        btnLocalStop.isEnabled = false
                    }
                    is AppState.RootfsFailed -> {
                        showInstallationView(
                            title = "Installation Failed",
                            step = state.message,
                            progress = 0
                        )
                        btnInstallRetry.visibility = View.VISIBLE
                        btnLocalStop.visibility = View.GONE
                    }
                    is AppState.PackageInstallFailed -> {
                        showInstallationView(
                            title = "Package Setup Failed",
                            step = state.message,
                            progress = 0
                        )
                        btnInstallRetry.visibility = View.VISIBLE
                        btnLocalStop.visibility = View.GONE
                    }
                    is AppState.AntigravityFailed, is AppState.LinuxFailed, is AppState.Failed -> {
                        showDashboardView()
                        val msg = when (state) {
                            is AppState.AntigravityFailed -> state.message
                            is AppState.LinuxFailed -> state.message
                            is AppState.Failed -> state.message
                        }
                        tvLocalStatusDesc.text = msg
                        ivLocalStatusDot.visibility = View.GONE
                        pbLocalStarting.visibility = View.GONE
                        btnLocalAction.text = getString(R.string.retry)
                        btnLocalAction.setIconResource(R.drawable.ic_refresh)
                        btnLocalAction.isEnabled = true
                        btnLocalStop.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeTerminalLogs() {
        lifecycleScope.launch {
            runtimeController.terminalLogs.collect { line ->
                AvsLogger.d(TAG, "[SupervisorLog] $line")
            }
        }
    }

    private fun appendTerminalLine(line: String) {
        terminalSession.buffer.appendLine(line)
        updateTerminalDisplay(terminalSession.buffer.render())
    }

    private fun isTerminalAtBottom(): Boolean {
        val diff = (terminalOutput.bottom - (terminalScroll.height + terminalScroll.scrollY))
        return diff <= 120
    }

    private fun updateTerminalDisplay(rendered: String) {
        val isAtBottom = isTerminalAtBottom()
        terminalOutput.text = rendered
        tvCliPrompt.text = terminalSession.getPrompt()
        if (isAtBottom) {
            scrollTerminalToBottom()
        } else {
            btnScrollToBottom.visibility = View.VISIBLE
        }
    }

    private fun scrollTerminalToBottom() {
        terminalScroll.post {
            terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateDesktopButtonState(isDesktop: Boolean) {
        btnToggleDesktop.setIconTintResource(if (isDesktop) R.color.accent else R.color.text_secondary)
    }

    private fun attachAndLoadWebView(url: String) {
        if (!webViewAttached) {
            val webView = webViewManager.createWebView()
            webviewContainer.removeAllViews()
            webviewContainer.addView(webView)
            webViewAttached = true
        }
        val sPort = runtimeController.serverPort
        webViewManager.loadEndpoint(url, sPort)
    }

    private fun submitCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.trim().isEmpty()) return

        commandInput.setText("")
        lifecycleScope.launch {
            terminalSession.executeCommand(cmd) { rendered ->
                runOnUiThread {
                    updateTerminalDisplay(rendered)
                }
            }
        }
    }

    private fun startPortsAutoRefresh() {
        portsAutoRefreshJob?.cancel()
        portsAutoRefreshJob = lifecycleScope.launch {
            while (true) {
                if (dashboardContainer.visibility == View.VISIBLE) {
                    updatePortsList()
                }
                delay(4000)
            }
        }
    }

    private fun updatePortsList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val primaryPort = runtimeController.serverPort
            val bridgePort = runtimeController.authBridgePort

            val guestProcessMap = try {
                if (runtimeController.linuxRuntime.state.value.isRunning) {
                    val ssOutput = runtimeController.linuxRuntime.execute("ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null").getOrDefault("")
                    portScanner.parseGuestSockets(ssOutput)
                } else emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }

            val ports = portScanner.scanPorts(
                primaryServerPort = primaryPort,
                authBridgePort = bridgePort,
                guestProcessMap = guestProcessMap
            )

            withContext(Dispatchers.Main) {
                layoutPortsList.removeAllViews()
                if (ports.isEmpty()) {
                    tvNoOpenPorts.visibility = View.VISIBLE
                    layoutPortsList.visibility = View.GONE
                } else {
                    tvNoOpenPorts.visibility = View.GONE
                    layoutPortsList.visibility = View.VISIBLE

                    for (port in ports) {
                        val itemView = layoutInflater.inflate(R.layout.item_open_port, layoutPortsList, false)
                        val badge = itemView.findViewById<TextView>(R.id.port_badge)
                        val serviceName = itemView.findViewById<TextView>(R.id.port_service_name)
                        val urlView = itemView.findViewById<TextView>(R.id.port_url)
                        val btnOpen = itemView.findViewById<MaterialButton>(R.id.btn_open_port)
                        val btnCopy = itemView.findViewById<MaterialButton>(R.id.btn_copy_port_url)

                        badge.text = port.port.toString()
                        serviceName.text = port.serviceName
                        urlView.text = port.url

                        btnOpen.setOnClickListener {
                            if (port.isPrimaryServer || port.isPrimaryVsCode) {
                                showEditorView()
                            } else {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(port.url))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    attachAndLoadWebView(port.url)
                                    showEditorView()
                                }
                            }
                        }

                        btnCopy.setOnClickListener {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("URL", port.url)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        }

                        layoutPortsList.addView(itemView)
                    }
                }
            }
        }
    }

    private fun updateWorkspaceSummary() {
        val projects = workspaceArchiveManager.listProjects()
        val count = projects.size
        tvProjectsSummary.text = if (count == 1) "1 project in workspace" else "$count projects in workspace"
    }

    private fun updateAboutSection() {
        val info = aboutInfoProvider.getAboutInfo()
        tvAboutAppVer.text = info.appVersion
        tvAboutDistro.text = info.linuxDistro
        tvAboutAntigravity.text = info.antigravityVersion
        tvAboutAndroid.text = "${info.androidVersion} (API ${info.sdkInt})"
        tvAboutDevice.text = info.deviceModel
        tvAboutCpu.text = "${info.cpuArch} • ${info.kernelVersion}"

        lifecycleScope.launch {
            val dynamicVer = withContext(Dispatchers.IO) {
                aboutInfoProvider.resolveDynamicAntigravityVersion(runtimeController.antigravityManager)
            }
            tvAboutAntigravity.text = dynamicVer
        }
    }

    private fun showSettingsDialog() {
        val paths = runtimeController.paths
        val msg = StringBuilder()
            .append("Projects Directory:\n${paths.guestProjectsPath}\n\n")
            .append("Rootfs Location:\n${paths.rootfsDir.absolutePath}\n\n")
            .append("Antigravity Data:\n${paths.guestAntigravityDataDir}\n\n")
            .append("Local Server Port:\n${runtimeController.serverPort ?: "Inactive"}")
            .toString()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.clear_logs) { _, _ ->
                paths.serverLogFile.delete()
                paths.runtimeLogFile.delete()
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showExportProjectDialog() {
        val projects = workspaceArchiveManager.listProjects()
        if (projects.isEmpty()) {
            Toast.makeText(this, R.string.no_projects_found, Toast.LENGTH_SHORT).show()
            return
        }

        val projectNames = projects.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_project_to_export)
            .setItems(projectNames) { _, which ->
                val selectedProject = projects[which]
                showExportFormatDialog(selectedProject)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportFormatDialog(project: File) {
        val formats = ArchiveFormat.values()
        val formatNames = formats.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_export_format)
            .setItems(formatNames) { _, which ->
                val chosenFormat = formats[which]
                pendingExportProject = project
                pendingExportFormat = chosenFormat
                val suggestedFileName = "${project.name}${chosenFormat.extension}"
                exportArchiveLauncher.launch(suggestedFileName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleImportArchive(uri: Uri) {
        val fileName = queryFileName(uri) ?: "imported_archive.zip"
        val format = ArchiveFormat.fromFileName(fileName)
        val baseName = fileName.substringBeforeLast(".").removeSuffix(".tar")
        val targetDir = File(runtimeController.paths.hostProjectsDir, baseName)

        Toast.makeText(this, "Importing $fileName...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open input stream for $uri")
                val count = stream.use { inStream ->
                    workspaceArchiveManager.importArchive(inStream, targetDir, format)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.import_success, count),
                        Toast.LENGTH_LONG
                    ).show()
                    updateWorkspaceSummary()
                }
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to import archive: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.operation_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleExportProject(project: File, format: ArchiveFormat, destUri: Uri) {
        Toast.makeText(this, "Exporting ${project.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openOutputStream(destUri)
                    ?: throw IllegalStateException("Cannot open output stream for $destUri")
                stream.use { outStream ->
                    workspaceArchiveManager.exportProject(project, outStream, format)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to export project: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.operation_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                pendingExportProject = null
                pendingExportFormat = null
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    override fun onResume() {
        super.onResume()
        webViewManager.resume()
        updatePortsList()
        updateWorkspaceSummary()
    }

    override fun onPause() {
        super.onPause()
        webViewManager.pause()
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "MainActivity destroyed")
        portsAutoRefreshJob?.cancel()
        authWebView?.stopLoading()
        authWebView?.destroy()
        authWebView = null
        webViewManager.destroy()
        webViewAttached = false
        super.onDestroy()
    }
}
