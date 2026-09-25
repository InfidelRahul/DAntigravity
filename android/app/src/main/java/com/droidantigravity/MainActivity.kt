package com.droidantigravity

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.droidantigravity.antigravity.AntigravityStartupException
import com.droidantigravity.core.AppState
import com.droidantigravity.core.Result
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.diagnostics.ExportLogManager
import com.droidantigravity.web.AntigravityWebView
import com.droidantigravity.terminal.PtyTerminalSession
import com.droidantigravity.terminal.TerminalScreen
import kotlinx.coroutines.launch

/**
 * Thin Android shell. The actual Antigravity UI is supplied by Remote Control.
 * Incorporates production diagnostic error display, log viewer, and export actions.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UI.MainActivity"
    }

    private lateinit var runtimeController: RuntimeController
    private lateinit var browser: AntigravityWebView
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var diagnosticIdText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttonRow: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var viewLogsButton: Button
    private lateinit var exportLogsButton: Button
    private lateinit var terminalButton: Button
    private lateinit var terminalContainer: FrameLayout
    private lateinit var floatingTerminalButton: Button
    private var terminalSession: PtyTerminalSession? = null
    @Volatile private var currentRemoteControlUrl: String? = null

    @Volatile private var activeDiagnosticId: String? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = result.data?.let { data ->
                if (data.clipData != null) {
                    Array(data.clipData!!.itemCount) { index ->
                        data.clipData!!.getItemAt(index).uri
                    }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
            browser.onFileChooserResult(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        DiagnosticLogger.i(TAG, "activity_created", "MainActivity onCreate")

        runtimeController = RuntimeController.getInstance(this)
        browser = AntigravityWebView(this)

        createUi()
        observeRuntime()
        setupBackHandling()

        if (savedInstanceState == null) {
            startRuntime()
        }
    }

    private fun createUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        webContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        val webView = browser.createWebView()
        webView.setOnLongClickListener {
            showRemoteControlActions()
            true
        }
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }

        progress = ProgressBar(this)

        statusTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
            visibility = View.GONE
        }

        statusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
            text = "Starting Linux environment…"
        }

        diagnosticIdText = TextView(this).apply {
            setTextColor(Color.parseColor("#80DEEA"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            visibility = View.GONE
        }

        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
            visibility = View.GONE
        }

        retryButton = Button(this).apply {
            text = "Retry"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1976D2"))
            setOnClickListener {
                if (runtimeController.appState.value is AppState.AuthenticationRequired) {
                    continueAuthentication()
                } else {
                    startRuntime()
                }
            }
        }

        viewLogsButton = Button(this).apply {
            text = "View Logs"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setOnClickListener {
                LogViewerDialog(this@MainActivity).show()
            }
        }

        exportLogsButton = Button(this).apply {
            text = "Export Logs"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#26A69A"))
            setOnClickListener { exportDiagnosticBundle() }
        }

        terminalButton = Button(this).apply {
            text = "Terminal"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#242428"))
            visibility = View.GONE
            setOnClickListener { showTerminal() }
        }

        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(8, 0, 8, 0) }

        buttonRow.addView(retryButton, btnLp)
        buttonRow.addView(viewLogsButton, btnLp)
        buttonRow.addView(exportLogsButton, btnLp)
        buttonRow.addView(terminalButton, btnLp)

        statusContainer.addView(progress)
        statusContainer.addView(statusTitle)
        statusContainer.addView(statusText)
        statusContainer.addView(diagnosticIdText)
        statusContainer.addView(buttonRow)

        terminalContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        root.addView(webContainer)
        root.addView(
            statusContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            terminalContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // A small native control keeps the official Antigravity WebView untouched
        // while making the independent Linux terminal reachable at any time.
        floatingTerminalButton = Button(this).apply {
            text = "⌘"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#242428"))
            visibility = View.GONE
            setOnClickListener { showTerminal() }
        }
        root.addView(
            floatingTerminalButton,
            FrameLayout.LayoutParams(52, 52, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, 18, 24)
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }

        browser.onLoadingStateChanged = { loading ->
            runOnUiThread {
                if (loading) {
                    statusText.text = "Loading Antigravity…"
                }
            }
        }

        browser.onConnectionError = { message ->
            runOnUiThread {
                showError("Antigravity could not be loaded", message, activeDiagnosticId)
            }
        }

        browser.onExternalUrlRequested = { false }

        browser.onFileChooserRequested = { intent ->
            runOnUiThread {
                fileChooserLauncher.launch(intent)
            }
        }

        browser.onDownloadRequested = { url, _, _, _, _ ->
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open download", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContentView(root)
    }

    private fun observeRuntime() {
        lifecycleScope.launch {
            runtimeController.appState.collect { state ->
                when (state) {
                    is AppState.Ready -> {
                        showWebView(state.url)
                    }

                    is AppState.AuthenticationRequired -> {
                        activeDiagnosticId = state.operationId
                        showAuthenticationRequired(state.message, state.operationId)
                    }

                    is AppState.AntigravityFailed -> {
                        activeDiagnosticId = state.operationId
                        showError("Antigravity failed to start", state.message, state.operationId)
                    }

                    is AppState.LinuxFailed -> {
                        activeDiagnosticId = state.operationId
                        showError("Linux userspace error", state.message, state.operationId)
                    }

                    is AppState.RootfsFailed -> {
                        activeDiagnosticId = state.operationId
                        showError("Rootfs error", state.message, state.operationId)
                    }

                    is AppState.PackageInstallFailed -> {
                        activeDiagnosticId = state.operationId
                        showError("Package installation error", state.message, state.operationId)
                    }

                    is AppState.Failed -> {
                        activeDiagnosticId = state.operationId
                        showError("DroidAntigravity error", state.message, state.operationId)
                    }

                    else -> {
                        showStatus(stateMessage(state), showProgress = true)
                    }
                }
            }
        }
    }

    private fun startRuntime() {
        lifecycleScope.launch {
            showStatus("Starting Antigravity…", showProgress = true)
            when (val result = runtimeController.startAll()) {
                is Result.Success -> showWebView(result.data)
                is Result.Failure -> {
                    val ex = result.error
                    val opId = (ex as? AntigravityStartupException)?.operationId
                    activeDiagnosticId = opId
                    if ((ex as? AntigravityStartupException)?.error == com.droidantigravity.antigravity.AntigravityStartupError.AUTH_REQUIRED) {
                        showAuthenticationRequired(
                            message = ex.message ?: "Authentication required to use Antigravity",
                            diagnosticId = opId
                        )
                    } else {
                        showError(
                            title = "Antigravity failed to start",
                            message = ex.message ?: "Unable to start DroidAntigravity",
                            diagnosticId = opId
                        )
                    }
                }
            }
        }
    }

    private fun continueAuthentication() {
        lifecycleScope.launch {
            showStatus("Waiting for Antigravity authentication…", showProgress = true)
            when (val result = runtimeController.continueAntigravityAuthentication()) {
                is Result.Success -> showWebView(result.data)
                is Result.Failure -> {
                    val ex = result.error
                    val opId = (ex as? AntigravityStartupException)?.operationId
                    activeDiagnosticId = opId
                    if ((ex as? AntigravityStartupException)?.error ==
                        com.droidantigravity.antigravity.AntigravityStartupError.AUTH_REQUIRED
                    ) {
                        showAuthenticationRequired(
                            ex.message ?: "Authentication is still required.",
                            opId
                        )
                    } else {
                        showError(
                            "Antigravity failed to start",
                            ex.message ?: "Unable to continue Antigravity authentication.",
                            opId
                        )
                    }
                }
            }
        }
    }

    private fun showWebView(url: String) {
        runOnUiThread {
            if (!AntigravityWebView.isAntigravityRemoteControlUrl(url)) {
                showError("Invalid URL", "Received an invalid Remote Control URL.", activeDiagnosticId)
                return@runOnUiThread
            }

            currentRemoteControlUrl = url
            statusContainer.visibility = View.GONE
            buttonRow.visibility = View.GONE
            terminalContainer.visibility = View.GONE
            floatingTerminalButton.visibility = View.VISIBLE
            webContainer.visibility = View.VISIBLE

            try {
                browser.loadRemoteControlUrl(url)
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "webview_load_error", "Failed to load Remote Control URL: ${e.message}", e)
                showError("Load Error", "Unable to open Antigravity Remote Control.", activeDiagnosticId)
            }
        }
    }

    private fun showTerminal() {
        if (!runtimeController.isLinuxRunning()) {
            Toast.makeText(this, "Linux userspace is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        webContainer.visibility = View.GONE
        statusContainer.visibility = View.GONE
        terminalContainer.visibility = View.VISIBLE
        floatingTerminalButton.visibility = View.GONE

        if (terminalSession == null || !terminalSession!!.isRunning()) {
            terminalSession?.close()

            val authenticationPty =
                if (runtimeController.appState.value is AppState.AuthenticationRequired) {
                    runtimeController.takeAntigravityAuthenticationPty()
                } else {
                    null
                }

            terminalSession = if (authenticationPty != null) {
                PtyTerminalSession(runtimeController.linuxRuntime, authenticationPty)
            } else {
                PtyTerminalSession(runtimeController.linuxRuntime)
            }

            val composeView = ComposeView(this).apply {
                setContent {
                    TerminalScreen(
                        session = terminalSession!!,
                        onClose = { hideTerminal() }
                    )
                }
            }
            terminalContainer.removeAllViews()
            terminalContainer.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun hideTerminal() {
        terminalContainer.visibility = View.GONE
        floatingTerminalButton.visibility = if (!currentRemoteControlUrl.isNullOrBlank()) View.VISIBLE else View.GONE
        if (!currentRemoteControlUrl.isNullOrBlank()) {
            webContainer.visibility = View.VISIBLE
        } else {
            statusContainer.visibility = View.VISIBLE
        }
    }

    private fun showRemoteControlActions() {
        val url = currentRemoteControlUrl ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remote Control")
            .setItems(arrayOf("Open in browser", "Copy URL")) { _, which ->
                when (which) {
                    0 -> openRemoteControlInBrowser(url)
                    1 -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Antigravity Remote Control URL", url))
                        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openRemoteControlInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStatus(message: String, showProgress: Boolean) {
        runOnUiThread {
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            progress.visibility = if (showProgress) View.VISIBLE else View.GONE
            statusTitle.visibility = View.GONE
            diagnosticIdText.visibility = View.GONE
            val linuxReady = runtimeController.isLinuxRunning()
            retryButton.visibility = View.GONE
            viewLogsButton.visibility = View.GONE
            exportLogsButton.visibility = View.GONE
            terminalButton.visibility = if (linuxReady) View.VISIBLE else View.GONE
            buttonRow.visibility = if (linuxReady) View.VISIBLE else View.GONE
            statusText.text = message
        }
    }

    private fun showAuthenticationRequired(message: String, diagnosticId: String?) {
        runOnUiThread {
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            progress.visibility = View.GONE
            statusTitle.text = "Google Sign In Required"
            statusTitle.visibility = View.VISIBLE
            statusText.text = "$message\n\nOpen Terminal to interact with the official Antigravity CLI login prompt. Complete Google authentication there, then tap Retry to continue to Remote Control."
            if (!diagnosticId.isNullOrBlank()) {
                diagnosticIdText.text = "Diagnostic ID: $diagnosticId"
                diagnosticIdText.visibility = View.VISIBLE
            } else {
                diagnosticIdText.visibility = View.GONE
            }
            buttonRow.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            viewLogsButton.visibility = View.VISIBLE
            exportLogsButton.visibility = View.VISIBLE
            terminalButton.visibility = if (runtimeController.isLinuxRunning()) View.VISIBLE else View.GONE
        }
    }

    private fun showError(title: String, message: String, diagnosticId: String?) {
        runOnUiThread {
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            progress.visibility = View.GONE
            statusTitle.text = title
            statusTitle.visibility = View.VISIBLE
            statusText.text = message
            if (!diagnosticId.isNullOrBlank()) {
                diagnosticIdText.text = "Diagnostic ID: $diagnosticId"
                diagnosticIdText.visibility = View.VISIBLE
            } else {
                diagnosticIdText.visibility = View.GONE
            }
            buttonRow.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            viewLogsButton.visibility = View.VISIBLE
            exportLogsButton.visibility = View.VISIBLE
            terminalButton.visibility = if (runtimeController.isLinuxRunning()) View.VISIBLE else View.GONE
        }
    }

    private fun exportDiagnosticBundle() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Preparing diagnostic bundle…", Toast.LENGTH_SHORT).show()
                val zipFile = ExportLogManager.exportDiagnosticsZip(this@MainActivity, activeDiagnosticId)

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    zipFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "DroidAntigravity Diagnostics - ${zipFile.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, "Export Diagnostic Bundle"))
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "export_error", "Failed to export diagnostic bundle: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stateMessage(state: AppState): String = when (state) {
        is AppState.NotInstalled -> "Preparing Linux environment…"
        is AppState.DownloadingRootfs -> "Downloading Linux rootfs… ${(state.progress * 100).toInt()}%"
        is AppState.ExtractingRootfs -> "Extracting Linux rootfs… ${(state.progress * 100).toInt()}%"
        is AppState.RootfsReady -> "Linux rootfs ready…"
        is AppState.StartingLinux -> "Starting Linux userspace…"
        is AppState.VerifyingLinux -> "Verifying Linux userspace…"
        is AppState.LinuxReady -> "Linux ready…"
        is AppState.InstallingPackages -> state.status
        is AppState.InstallingAntigravity -> state.status
        is AppState.AntigravityReady -> "Starting Antigravity…"
        is AppState.StartingAntigravityServer -> state.status
        is AppState.Stopping -> "Stopping…"
        else -> "Starting DroidAntigravity…"
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browser.goBack()) return
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onPause() {
        browser.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        terminalSession?.close()
        terminalSession = null
        browser.destroy()
        super.onDestroy()
    }
}
