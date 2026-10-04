package com.droidantigravity

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.delay

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

        val density = resources.displayMetrics.density

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        progress = ProgressBar(this)

        statusTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
            visibility = View.GONE
        }

        statusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            text = "Starting Linux environment…"
        }

        diagnosticIdText = TextView(this).apply {
            setTextColor(Color.parseColor("#80DEEA"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
            visibility = View.GONE
        }

        val buttonScrollView = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padV = (16 * density).toInt()
            setPadding(0, padV, 0, 0)
            visibility = View.GONE
        }

        fun createStyledButton(label: String, bgColor: Int, textColor: Int = Color.WHITE, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                stateListAnimator = null
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(bgColor)
                }
                background = bg
                val padH = (16 * density).toInt()
                val padBtnV = (10 * density).toInt()
                setPadding(padH, padBtnV, padH, padBtnV)
                minHeight = (42 * density).toInt()
                setOnClickListener { onClick() }
            }
        }

        retryButton = createStyledButton("Retry", Color.parseColor("#1976D2")) {
            if (runtimeController.appState.value is AppState.AuthenticationRequired) {
                continueAuthentication()
            } else {
                startRuntime()
            }
        }

        viewLogsButton = createStyledButton("View Logs", Color.parseColor("#37474F")) {
            LogViewerDialog(this@MainActivity).show()
        }

        exportLogsButton = createStyledButton("Export Logs", Color.parseColor("#00897B")) {
            exportDiagnosticBundle()
        }

        terminalButton = createStyledButton("Terminal", Color.parseColor("#25252D")) {
            showTerminal()
        }.apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#25252D"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#4B4B58"))
            }
            background = bg
        }

        val btnMargin = (6 * density).toInt()
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(btnMargin, 0, btnMargin, 0) }

        buttonRow.addView(retryButton, btnLp)
        buttonRow.addView(viewLogsButton, btnLp)
        buttonRow.addView(exportLogsButton, btnLp)
        buttonRow.addView(terminalButton, btnLp)

        buttonScrollView.addView(
            buttonRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        statusContainer.addView(progress)
        statusContainer.addView(statusTitle)
        statusContainer.addView(statusText)
        statusContainer.addView(diagnosticIdText)
        statusContainer.addView(buttonScrollView)

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

        // Native floating control to open the Linux terminal anytime
        val fabSize = (56 * density).toInt()
        val marginEnd = (20 * density).toInt()
        val marginBottom = (24 * density).toInt()

        floatingTerminalButton = Button(this).apply {
            text = ">_"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            isAllCaps = false
            stateListAnimator = null
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#1E1E24"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#4B4B58"))
            }
            background = bg
            elevation = 10f * density
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            contentDescription = "Open Linux Terminal"
            setOnClickListener { showTerminal() }
        }
        root.addView(
            floatingTerminalButton,
            FrameLayout.LayoutParams(fabSize, fabSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, marginEnd, marginBottom)
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
        lifecycleScope.launch {
            if (!runtimeController.isLinuxRunning()) {
                showStatus("Starting Linux userspace for Terminal…", showProgress = true)
                val startRes = runtimeController.ensureLinuxStarted()
                if (startRes is Result.Failure) {
                    showError("Linux failed to start", startRes.error.message ?: "Unable to start Linux runtime", null)
                    return@launch
                }
            }

            webContainer.visibility = View.GONE
            statusContainer.visibility = View.GONE
            terminalContainer.visibility = View.VISIBLE
            floatingTerminalButton.visibility = View.GONE

            if (terminalSession == null || !terminalSession!!.isRunning()) {
                terminalSession?.close()
                terminalSession = PtyTerminalSession(runtimeController.linuxRuntime)

                val composeView = ComposeView(this@MainActivity).apply {
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
            retryButton.visibility = View.GONE
            viewLogsButton.visibility = View.VISIBLE
            exportLogsButton.visibility = View.GONE
            terminalButton.visibility = View.VISIBLE
            buttonRow.visibility = View.VISIBLE
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
            statusText.text = "$message\n\nThe official Google OAuth flow is being opened in your Android browser. Complete sign-in there; DroidAntigravity will continue to Remote Control automatically."
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
            terminalButton.visibility = View.VISIBLE
            openAntigravityAuthBrowserWhenReady()
        }
    }

    private fun openAntigravityAuthBrowserWhenReady() {
        lifecycleScope.launch {
            val paths = com.droidantigravity.core.AppPaths.getInstance(this@MainActivity)
            val deadline = SystemClock.uptimeMillis() + 30_000L
            while (SystemClock.uptimeMillis() < deadline && !isFinishing) {
                val file = paths.antigravityBrowserUrlFile
                if (file.exists()) {
                    val url = runCatching { file.readText().trim() }.getOrDefault("")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        DiagnosticLogger.i(TAG, "AUTH_BROWSER_OPEN", "Opening official CLI OAuth URL in Android browser")
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            DiagnosticLogger.e(TAG, "AUTH_BROWSER_OPEN_FAILED", "Unable to open OAuth URL: ${it.message}", it)
                        }
                        file.delete()
                        // Keep the CLI watcher alive while the user completes OAuth.
                        continueAuthentication()
                        return@launch
                    }
                }
                delay(100)
            }
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
            terminalButton.visibility = View.VISIBLE
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
                if (terminalContainer.visibility == View.VISIBLE) {
                    hideTerminal()
                    return
                }
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
