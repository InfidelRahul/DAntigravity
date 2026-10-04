package com.droidantigravity

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.EditText
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
import com.droidantigravity.antigravity.AntigravityStartupError
import com.droidantigravity.antigravity.AntigravityStartupException
import com.droidantigravity.core.AppState
import com.droidantigravity.core.Result
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.DiagnosticSanitizer
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

    // Touch-optimized authorization code UI
    private lateinit var authContainer: LinearLayout
    private lateinit var authInstructions: TextView
    private lateinit var openBrowserButton: Button
    private lateinit var authCodeInput: EditText
    private lateinit var authActionsRow: LinearLayout
    private lateinit var pasteButton: Button
    private lateinit var clearButton: Button
    private lateinit var submitCodeButton: Button
    @Volatile private var activeAuthUrl: String? = null
    @Volatile private var hasAutoOpenedBrowser = false

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

        // ----------------------------------------------------
        // Authorization Code Entry UI (Real CLI Remote Control)
        // ----------------------------------------------------
        authContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
        }

        authInstructions = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
            text = "Complete authentication in your browser, then copy the authorization code and paste it below:"
        }

        openBrowserButton = createStyledButton("Open Authentication in Browser", Color.parseColor("#1A73E8")) {
            openAuthBrowser(activeAuthUrl)
        }.apply {
            val padH = (20 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        authCodeInput = EditText(this).apply {
            hint = "Paste authorization code here"
            setHintTextColor(Color.parseColor("#757580"))
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#18181E"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#3E3E4C"))
            }
            background = bg
            val padH = (14 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitAuthCode(text.toString())
                    true
                } else false
            }
        }

        authActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        }

        pasteButton = createStyledButton("Paste", Color.parseColor("#37474F")) {
            pasteAuthCode()
        }

        clearButton = createStyledButton("Clear", Color.parseColor("#2E2E36")) {
            authCodeInput.setText("")
        }

        submitCodeButton = createStyledButton("Submit Code", Color.parseColor("#00C853")) {
            submitAuthCode(authCodeInput.text.toString())
        }

        val authBtnMargin = (6 * density).toInt()
        val authBtnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(authBtnMargin, 0, authBtnMargin, 0) }

        authActionsRow.addView(pasteButton, authBtnLp)
        authActionsRow.addView(clearButton, authBtnLp)
        authActionsRow.addView(submitCodeButton, authBtnLp)

        val inputLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }

        authContainer.addView(authInstructions)
        authContainer.addView(openBrowserButton)
        authContainer.addView(authCodeInput, inputLp)
        authContainer.addView(authActionsRow)

        statusContainer.addView(progress)
        statusContainer.addView(statusTitle)
        statusContainer.addView(statusText)
        statusContainer.addView(diagnosticIdText)
        statusContainer.addView(authContainer)
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
                        showAuthenticationRequired(state.authUrl, state.message, state.operationId)
                    }

                    is AppState.Authenticating -> {
                        activeDiagnosticId = state.operationId
                        showStatus(state.message, showProgress = true)
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
        hasAutoOpenedBrowser = false
        activeAuthUrl = null
        lifecycleScope.launch {
            showStatus("Starting Antigravity…", showProgress = true)
            when (val result = runtimeController.startAll()) {
                is Result.Success -> showWebView(result.data)
                is Result.Failure -> {
                    val ex = result.error
                    val startupEx = ex as? AntigravityStartupException
                    val opId = startupEx?.operationId
                    activeDiagnosticId = opId
                    if (startupEx?.error == AntigravityStartupError.AUTH_REQUIRED) {
                        showAuthenticationRequired(
                            authUrl = startupEx.authUrl ?: runtimeController.getAuthUrl(),
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
                    val startupEx = ex as? AntigravityStartupException
                    val opId = startupEx?.operationId
                    activeDiagnosticId = opId
                    if (startupEx?.error == AntigravityStartupError.AUTH_REQUIRED) {
                        showAuthenticationRequired(
                            authUrl = startupEx.authUrl ?: runtimeController.getAuthUrl(),
                            message = ex.message ?: "Authentication is still required.",
                            diagnosticId = opId
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
            authContainer.visibility = View.GONE
            hasAutoOpenedBrowser = false
            authCodeInput.setText("")
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
            authContainer.visibility = View.GONE
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

    private fun showAuthenticationRequired(authUrl: String?, message: String, diagnosticId: String?) {
        runOnUiThread {
            activeAuthUrl = authUrl ?: runtimeController.getAuthUrl()
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            progress.visibility = View.GONE
            statusTitle.text = "Authorization Required"
            statusTitle.visibility = View.VISIBLE
            statusText.text = "$message\n\nComplete authentication in your browser. Then paste the authorization code below to establish your Remote Control session."
            if (!diagnosticId.isNullOrBlank()) {
                diagnosticIdText.text = "Diagnostic ID: $diagnosticId"
                diagnosticIdText.visibility = View.VISIBLE
            } else {
                diagnosticIdText.visibility = View.GONE
            }

            // Expose the clean authorization code entry UI
            authContainer.visibility = View.VISIBLE
            authCodeInput.isEnabled = true
            submitCodeButton.isEnabled = true

            buttonRow.visibility = View.VISIBLE
            retryButton.visibility = View.GONE
            viewLogsButton.visibility = View.VISIBLE
            exportLogsButton.visibility = View.VISIBLE
            terminalButton.visibility = View.VISIBLE

            val targetUrl = activeAuthUrl
            if (!targetUrl.isNullOrBlank() && !hasAutoOpenedBrowser) {
                hasAutoOpenedBrowser = true
                openAuthBrowser(targetUrl)
            } else if (targetUrl.isNullOrBlank()) {
                watchForAuthUrl()
            }
        }
    }

    private fun openAuthBrowser(url: String?) {
        val targetUrl = url ?: activeAuthUrl ?: runtimeController.getAuthUrl()
        if (!targetUrl.isNullOrBlank() && (targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
            DiagnosticLogger.i(TAG, "AUTH_BROWSER_OPEN", "Opening authorization URL in Android browser: ${DiagnosticSanitizer.redact(targetUrl)}")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "AUTH_BROWSER_FAILED", "Failed to launch browser: ${e.message}", e)
                Toast.makeText(this, "Unable to open browser", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Waiting for authentication URL from Antigravity…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun watchForAuthUrl() {
        lifecycleScope.launch {
            val paths = com.droidantigravity.core.AppPaths.getInstance(this@MainActivity)
            val deadline = SystemClock.uptimeMillis() + 20_000L
            while (SystemClock.uptimeMillis() < deadline && !isFinishing) {
                val url = runtimeController.getAuthUrl()
                    ?: if (paths.antigravityBrowserUrlFile.exists()) {
                        runCatching { paths.antigravityBrowserUrlFile.readText().trim() }.getOrNull()
                    } else null

                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    activeAuthUrl = url
                    if (!hasAutoOpenedBrowser) {
                        hasAutoOpenedBrowser = true
                        openAuthBrowser(url)
                    }
                    return@launch
                }
                delay(200)
            }
        }
    }

    private fun pasteAuthCode() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                authCodeInput.setText(text)
                authCodeInput.setSelection(text.length)
                Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    }

    private fun submitAuthCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, "Please enter or paste the authorization code", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            authContainer.visibility = View.VISIBLE
            submitCodeButton.isEnabled = false
            authCodeInput.isEnabled = false
            progress.visibility = View.VISIBLE
            statusText.text = "Submitting authorization code to Antigravity…"

            // Hide software keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(authCodeInput.windowToken, 0)

            when (val result = runtimeController.submitAuthorizationCode(trimmed)) {
                is Result.Success -> {
                    // Ready state triggers showWebView(result.data) automatically via observeRuntime
                }
                is Result.Failure -> {
                    submitCodeButton.isEnabled = true
                    authCodeInput.isEnabled = true
                    progress.visibility = View.GONE
                    val ex = result.error
                    val opId = (ex as? AntigravityStartupException)?.operationId
                    activeDiagnosticId = opId
                    if ((ex as? AntigravityStartupException)?.error == AntigravityStartupError.AUTH_REQUIRED) {
                        statusText.text = "The authorization code was not accepted. Please open the browser to re-authenticate and try again."
                    } else {
                        showError("Authentication Failed", ex.message ?: "Failed to authenticate with Antigravity", opId)
                    }
                }
            }
        }
    }

    private fun showError(title: String, message: String, diagnosticId: String?) {
        runOnUiThread {
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            authContainer.visibility = View.GONE
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
