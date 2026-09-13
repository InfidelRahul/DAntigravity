package com.avscode.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.*
import com.avscode.core.AvsLogger
import java.util.Locale

/**
 * ACode-aligned WebView manager for displaying Antigravity Web UI.
 *
 * Capabilities:
 * - Hardware acceleration, DOM storage, database storage, WebSockets, and mixed content handling.
 * - Dynamic pinch-to-zoom and edge-to-edge root scaling.
 * - External link routing: launches system browser for docs/repos; reserves in-app modal for OAuth.
 * - Deterministic error callbacks to allow immediate recovery / terminal access.
 */
class AntigravityWebView(private val context: Context) {

    companion object {
        private const val TAG = "AntigravityWebView"

        const val DEFAULT_ZOOM_LEVEL = 75
        const val MIN_ZOOM_LEVEL = 40
        const val MAX_ZOOM_LEVEL = 300
        const val ZOOM_STEP = 10

        fun resolveLinuxArchitecture(customAbi: String? = null): String {
            val abi = customAbi ?: Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch").orEmpty()
            return when {
                abi.contains("arm64", ignoreCase = true) || abi.contains("aarch64", ignoreCase = true) -> "aarch64"
                abi.contains("arm", ignoreCase = true) -> "armv7l"
                abi.contains("x86_64", ignoreCase = true) -> "x86_64"
                abi.contains("x86", ignoreCase = true) -> "i686"
                else -> "aarch64"
            }
        }

        fun buildDesktopUserAgent(context: Context? = null, customAbi: String? = null): String {
            val arch = resolveLinuxArchitecture(customAbi)
            var chromeToken = "Chrome/130.0.0.0"
            if (context != null) {
                try {
                    val defaultUa = WebSettings.getDefaultUserAgent(context)
                    val match = Regex("Chrome/([0-9.]+)").find(defaultUa)
                    if (match != null) {
                        chromeToken = match.value
                    }
                } catch (e: Exception) {
                    AvsLogger.d(TAG, "Could not extract device Chrome version: ${e.message}")
                }
            }
            return "Mozilla/5.0 (X11; Linux $arch) AppleWebKit/537.36 (KHTML, like Gecko) $chromeToken Safari/537.36"
        }

        fun isAuthUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("/oauth") ||
                    lower.contains("/login") ||
                    lower.contains("/signin") ||
                    lower.contains("accounts.google.com") ||
                    lower.contains("login.microsoftonline.com") ||
                    lower.contains("github.com/login") ||
                    lower.contains("/auth/") ||
                    lower.contains("did-authenticate")
        }

        fun isAuthCallbackUrl(url: String, authBridgePort: Int? = null): Boolean {
            val lower = url.lowercase()
            if (lower.startsWith("droidantigravity://") || lower.startsWith("antigravity://") || lower.startsWith("avscode://")) {
                return true
            }
            if (lower.contains("callback") || lower.contains("did-authenticate")) {
                return true
            }
            if (authBridgePort != null && lower.contains(":$authBridgePort")) {
                return true
            }
            return false
        }

        fun isAuthCallback(uri: Uri, authBridgePort: Int? = null): Boolean {
            return isAuthCallbackUrl(uri.toString(), authBridgePort)
        }

        fun buildZoomJavaScript(factor: Double): String {
            val fStr = String.format(Locale.US, "%.4f", factor)
            return """
                (function() {
                    try {
                        var factor = $fStr;
                        var docEl = document.documentElement;
                        var body = document.body;
                        if (!docEl || !body) return;

                        docEl.style.zoom = factor;
                        docEl.style.width = '100%';
                        docEl.style.height = '100%';
                        docEl.style.maxWidth = '100%';
                        docEl.style.maxHeight = '100%';
                        docEl.style.minWidth = '100%';
                        docEl.style.minHeight = '100%';
                        docEl.style.margin = '0px';
                        docEl.style.padding = '0px';
                        docEl.style.backgroundColor = '#181818';

                        body.style.zoom = '1';
                        body.style.width = '100%';
                        body.style.height = '100%';
                        body.style.maxWidth = '100%';
                        body.style.maxHeight = '100%';
                        body.style.minWidth = '100%';
                        body.style.minHeight = '100%';
                        body.style.margin = '0px';
                        body.style.padding = '0px';
                        body.style.backgroundColor = '#181818';

                        window.dispatchEvent(new Event('resize'));
                    } catch(e) {}
                })();
            """.trimIndent()
        }
    }

    private var webView: WebView? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var isReady = false
    private var lastLoadedUrl: String? = null

    var isDesktopMode: Boolean = true
        private set
    var currentZoomLevel: Int = DEFAULT_ZOOM_LEVEL
        private set

    var serverPort: Int? = null
        private set
    var endpointUrl: String? = null
        private set

    var onLoadingStateChanged: ((Boolean) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onAuthCallbackReceived: ((Uri) -> Boolean)? = null
    var onExternalUrlRequested: ((String) -> Boolean)? = null
    var onZoomChanged: ((Int) -> Unit)? = null
    var onDesktopModeChanged: ((Boolean) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        if (webView != null) {
            return webView!!
        }

        AvsLogger.d(TAG, "Creating AntigravityWebView (DesktopMode=$isDesktopMode)")

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastScaleTime = 0L

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastScaleTime < 100) return false
                val factor = detector.scaleFactor
                if (factor > 1.05f) {
                    zoomIn()
                    lastScaleTime = now
                    return true
                } else if (factor < 0.95f) {
                    zoomOut()
                    lastScaleTime = now
                    return true
                }
                return false
            }
        })

        val view = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            setBackgroundColor(Color.parseColor("#181818"))

            setOnTouchListener { _, event ->
                if (event.pointerCount > 1) {
                    scaleGestureDetector?.onTouchEvent(event) ?: false
                } else {
                    false
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                if (isDesktopMode) {
                    userAgentString = buildDesktopUserAgent(context)
                }
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AvsLogger.d(TAG, "Antigravity page started loading: $url")
                    injectViewportOverride(view)
                    onLoadingStateChanged?.invoke(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AvsLogger.i(TAG, "Antigravity page finished loading: $url")
                    isReady = true
                    injectViewportOverride(view)
                    applyZoom()
                    view?.postDelayed({ applyZoom() }, 500)
                    CookieManager.getInstance().flush()
                    onLoadingStateChanged?.invoke(false)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val reqUrl = request.url
                        if (reqUrl != null && isAuthCallback(reqUrl)) {
                            onAuthCallbackReceived?.invoke(reqUrl)
                            return
                        }
                        val description = error?.description?.toString() ?: "Connection error"
                        AvsLogger.w(TAG, "Main frame error ($description): $reqUrl")
                        onConnectionError?.invoke(description)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val uriStr = uri.toString()

                    // 1. Intercept OAuth callbacks
                    if (isAuthCallback(uri)) {
                        val handled = onAuthCallbackReceived?.invoke(uri) ?: true
                        if (handled) return true
                    }

                    // 2. Pass-through active Antigravity endpoint
                    if (isAntigravityEndpointUrl(uri)) {
                        return false
                    }

                    // 3. External URLs: launch in system browser or in-app auth modal
                    if (onExternalUrlRequested?.invoke(uriStr) == true) {
                        return true
                    }

                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val tempWebView = WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val popupUri = request?.url ?: return false
                                val popupUrlStr = popupUri.toString()

                                if (isAntigravityEndpointUrl(popupUri)) {
                                    this@AntigravityWebView.loadUrl(popupUrlStr)
                                    return true
                                }
                                if (isAuthCallback(popupUri)) {
                                    onAuthCallbackReceived?.invoke(popupUri)
                                    return true
                                }
                                onExternalUrlRequested?.invoke(popupUrlStr)
                                return true
                            }
                        }
                    }

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = tempWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                    return false
                }
            }
        }

        webView = view
        return view
    }

    fun isAntigravityEndpointUrl(uri: Uri): Boolean {
        val activeEndpoint = endpointUrl
        if (activeEndpoint != null && uri.toString().startsWith(activeEndpoint)) return true
        val host = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else null
        val isLoopback = host.equals("127.0.0.1", ignoreCase = true) || host.equals("localhost", ignoreCase = true)
        if (serverPort != null && isLoopback && port == serverPort) return true
        if (host.endsWith(".app.github.dev", ignoreCase = true)) return true
        return false
    }

    fun loadEndpoint(url: String, port: Int? = null) {
        endpointUrl = url
        serverPort = port
        loadUrl(url)
    }

    fun loadUrl(url: String) {
        lastLoadedUrl = url
        val view = webView ?: createWebView()
        view.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    fun applyZoom() {
        val factor = currentZoomLevel / 100.0
        val js = buildZoomJavaScript(factor)
        webView?.evaluateJavascript(js, null)
    }

    fun zoomIn() {
        if (currentZoomLevel + ZOOM_STEP <= MAX_ZOOM_LEVEL) {
            currentZoomLevel += ZOOM_STEP
            applyZoom()
            onZoomChanged?.invoke(currentZoomLevel)
        }
    }

    fun zoomOut() {
        if (currentZoomLevel - ZOOM_STEP >= MIN_ZOOM_LEVEL) {
            currentZoomLevel -= ZOOM_STEP
            applyZoom()
            onZoomChanged?.invoke(currentZoomLevel)
        }
    }

    fun resetZoom() {
        currentZoomLevel = DEFAULT_ZOOM_LEVEL
        applyZoom()
        onZoomChanged?.invoke(currentZoomLevel)
    }

    fun setZoom(level: Int) {
        currentZoomLevel = level.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
        applyZoom()
        onZoomChanged?.invoke(currentZoomLevel)
    }

    fun toggleDesktopMode(): Boolean {
        isDesktopMode = !isDesktopMode
        val view = webView ?: return isDesktopMode
        view.settings.userAgentString = if (isDesktopMode) {
            buildDesktopUserAgent(context)
        } else {
            WebSettings.getDefaultUserAgent(context)
        }
        view.reload()
        onDesktopModeChanged?.invoke(isDesktopMode)
        return isDesktopMode
    }

    private fun injectViewportOverride(view: WebView?) {
        val js = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.head.appendChild(meta);
                    }
                    meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    fun pause() {
        webView?.onPause()
        webView?.pauseTimers()
    }

    fun resume() {
        webView?.onResume()
        webView?.resumeTimers()
    }

    fun destroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        isReady = false
    }
}
