package com.droidantigravity.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.DiagnosticSanitizer

/**
 * Minimal browser surface for the official Antigravity Remote Control web app.
 *
 * This class intentionally does not recreate or modify the Antigravity UI.
 * It owns only WebView configuration, navigation, lifecycle and browser events.
 */
class AntigravityWebView(private val context: Context) {

    companion object {
        private const val TAG = "AntigravityWebView"

        fun createUserAgent(context: Context): String =
            WebSettings.getDefaultUserAgent(context)

        fun isAntigravityRemoteControlUrl(url: String): Boolean =
            runCatching {
                val uri = url.toUri()
                uri.scheme.equals("https", true) &&
                    uri.host.equals("antigravity.google.com", true) &&
                    uri.path.orEmpty().startsWith("/r/")
            }.getOrDefault(false)

        fun isSupportedEndpointUrl(url: String): Boolean =
            runCatching {
                val uri = url.toUri()
                uri.scheme.equals("https", true) ||
                    (uri.scheme.equals("http", true) &&
                        (uri.host.equals("127.0.0.1", true) ||
                            uri.host.equals("localhost", true)))
            }.getOrDefault(false)

        private fun isHttpUrl(uri: Uri): Boolean =
            uri.scheme.equals("https", true) ||
                (uri.scheme.equals("http", true) &&
                    (uri.host.equals("127.0.0.1", true) ||
                        uri.host.equals("localhost", true)))
    }

    private var webView: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    var onLoadingStateChanged: ((Boolean) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onExternalUrlRequested: ((String) -> Boolean)? = null
    var onFileChooserRequested: ((Intent) -> Unit)? = null
    var onDownloadRequested: ((String, String?, String?, String?, Long) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        webView?.let { return it }

        val view = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = true
                userAgentString = createUserAgent(context)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            DiagnosticLogger.i(TAG, "WEBVIEW_CREATED", "Antigravity WebView instance created with secure settings")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    DiagnosticLogger.i(TAG, "WEBVIEW_LOAD_STARTED", "Loading Remote Control URL: ${DiagnosticSanitizer.redactRemoteControlUrl(url.orEmpty())}")
                    onLoadingStateChanged?.invoke(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    DiagnosticLogger.i(TAG, "WEBVIEW_LOAD_FINISHED", "Loaded Remote Control URL: ${DiagnosticSanitizer.redactRemoteControlUrl(url.orEmpty())}")
                    onLoadingStateChanged?.invoke(false)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val url = request?.url?.toString().orEmpty()
                    val description = error?.description?.toString() ?: "Unknown web error"
                    val isMainFrame = request?.isForMainFrame == true

                    if (isMainFrame) {
                        DiagnosticLogger.e(TAG, "WEBVIEW_LOAD_FAILED", "Main-frame error: $description on url: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}")
                        onConnectionError?.invoke(description)
                    } else {
                        DiagnosticLogger.w(TAG, "webview_subresource_error", "Subresource error: $description on url: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    val status = errorResponse?.statusCode ?: -1
                    val reason = errorResponse?.reasonPhrase ?: "HTTP Error"
                    val url = request?.url?.toString().orEmpty()
                    DiagnosticLogger.w(TAG, "webview_http_error", "HTTP $status ($reason) on url: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    val primaryError = error?.primaryError ?: -1
                    val url = error?.url.orEmpty()
                    DiagnosticLogger.e(TAG, "webview_ssl_error", "SSL error $primaryError on url: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}")
                    super.onReceivedSslError(view, handler, error)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    DiagnosticLogger.d(TAG, "webview_navigation", "Navigation requested to: ${DiagnosticSanitizer.redact(uri.toString())}")
                    return handleNavigation(uri)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    // Keep popup navigation inside the same full-screen browser surface.
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = this@apply
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback

                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }

                    onFileChooserRequested?.invoke(intent)
                    return onFileChooserRequested != null
                }
            }

            setDownloadListener(
                DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    onDownloadRequested?.invoke(
                        url,
                        userAgent,
                        contentDisposition,
                        mimeType,
                        contentLength
                    )
                }
            )
        }

        webView = view
        return view
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (isHttpUrl(uri)) return false

        val url = uri.toString()
        if (onExternalUrlRequested?.invoke(url) == true) return true

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            AvsLogger.w(TAG, "No Android handler for $uri: ${e.message}")
            true
        }
    }

    fun loadEndpoint(endpoint: AntigravityEndpoint) {
        require(isSupportedEndpointUrl(endpoint.url)) {
            "Unsupported Antigravity endpoint URL"
        }
        if (endpoint.source == EndpointSource.REMOTE_CONTROL) {
            require(isAntigravityRemoteControlUrl(endpoint.url)) {
                "Invalid official Antigravity Remote Control URL"
            }
        }
        val view = webView ?: createWebView()
        view.loadUrl(endpoint.url)
    }

    fun loadRemoteControlUrl(url: String) {
        loadEndpoint(AntigravityEndpoint(url, EndpointSource.REMOTE_CONTROL))
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack(): Boolean =
        webView?.takeIf { it.canGoBack() }?.let {
            it.goBack()
            true
        } ?: false

    fun onResume() {
        webView?.onResume()
    }

    fun onPause() {
        webView?.onPause()
    }

    fun onFileChooserResult(result: Array<Uri>?) {
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    fun destroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        webView?.apply {
            stopLoading()
            onPause()
            clearFocus()
            removeAllViews()
            destroy()
        }
        webView = null
    }
}
