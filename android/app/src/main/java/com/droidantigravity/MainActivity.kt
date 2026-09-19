package com.droidantigravity

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.droidantigravity.core.AppState
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.Result
import com.droidantigravity.web.AntigravityWebView
import kotlinx.coroutines.launch

/**
 * Thin Android shell. The actual Antigravity UI is supplied by Remote Control.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var runtimeController: RuntimeController
    private lateinit var browser: AntigravityWebView
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button

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
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }

        progress = ProgressBar(this)
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
            text = "Starting Linux environment…"
        }

        retryButton = Button(this).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { startRuntime() }
        }

        statusContainer.addView(progress)
        statusContainer.addView(statusText)
        statusContainer.addView(retryButton)

        root.addView(webContainer)
        root.addView(
            statusContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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
                showStatus("Antigravity could not be loaded.\n$message", showProgress = false)
            }
        }

        browser.onExternalUrlRequested = { url ->
            // HTTPS navigation remains in the WebView. Non-web schemes are delegated.
            false
        }

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

                    is AppState.RootfsFailed,
                    is AppState.LinuxFailed,
                    is AppState.PackageInstallFailed,
                    is AppState.AntigravityFailed,
                    is AppState.Failed -> {
                        showStatus(
                            stateFailureMessage(state),
                            showProgress = false
                        )
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
            when (val result = runtimeController.startAll()) {
                is Result.Success -> showWebView(result.data)
                is Result.Failure -> {
                    showStatus(
                        result.error.message ?: "Unable to start DroidAntigravity",
                        showProgress = false
                    )
                }
            }
        }
    }

    private fun showWebView(url: String) {
        runOnUiThread {
            if (!AntigravityWebView.isAntigravityRemoteControlUrl(url)) {
                showStatus("Received an invalid Remote Control URL.", false)
                return@runOnUiThread
            }

            statusContainer.visibility = View.GONE
            retryButton.visibility = View.GONE
            webContainer.visibility = View.VISIBLE

            try {
                browser.loadRemoteControlUrl(url)
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to load Remote Control URL", e)
                showStatus("Unable to open Antigravity Remote Control.", false)
            }
        }
    }

    private fun showStatus(message: String, showProgress: Boolean) {
        runOnUiThread {
            webContainer.visibility = View.GONE
            statusContainer.visibility = View.VISIBLE
            progress.visibility = if (showProgress) View.VISIBLE else View.GONE
            retryButton.visibility = if (showProgress) View.GONE else View.VISIBLE
            statusText.text = message
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

    private fun stateFailureMessage(state: AppState): String = when (state) {
        is AppState.RootfsFailed -> state.message
        is AppState.LinuxFailed -> state.message
        is AppState.PackageInstallFailed -> state.message
        is AppState.AntigravityFailed -> state.message
        is AppState.Failed -> state.message
        else -> "DroidAntigravity could not start."
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browser.goBack()) return
                // Keep the Linux/agy process alive; leaving the Activity does not
                // implicitly destroy the persistent development environment.
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
        browser.destroy()
        super.onDestroy()
    }
}
