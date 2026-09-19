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

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    onLoadingStateChanged?.invoke(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    onLoadingStateChanged?.invoke(false)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val message = error?.description?.toString() ?: "Web page failed to load"
                        AvsLogger.w(TAG, "Main-frame WebView error: $message")
                        onConnectionError?.invoke(message)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
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

    fun loadRemoteControlUrl(url: String) {
        require(isAntigravityRemoteControlUrl(url)) {
            "Only an official Antigravity Remote Control URL may be loaded"
        }
        val view = webView ?: createWebView()
        view.loadUrl(url)
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
