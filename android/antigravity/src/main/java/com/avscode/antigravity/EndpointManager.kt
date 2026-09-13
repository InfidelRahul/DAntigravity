package com.avscode.antigravity

import com.avscode.core.AvsLogger
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Minimal, robust Web Endpoint and Port Manager for DroidAntigravity.
 *
 * Responsibilities:
 * - Discover available application loopback ports.
 * - Build local and Codespace web URLs.
 * - Actively verify HTTP connectivity before loading endpoints in WebView.
 */
class EndpointManager {

    companion object {
        private const val TAG = "EndpointManager"
        const val DEFAULT_LOCAL_HOST = "127.0.0.1"
        const val DEFAULT_LOCAL_PORT = 33000
        const val DEFAULT_CODESPACE_PORT = 8080

        /**
         * Finds an available TCP port on local loopback.
         * Tries [preferredPort] first to preserve origin localStorage/cookies across restarts.
         */
        fun findAvailablePort(preferredPort: Int = DEFAULT_LOCAL_PORT): Int {
            return try {
                ServerSocket(preferredPort).use { it.localPort }
            } catch (e: Exception) {
                try {
                    ServerSocket(0).use { it.localPort }
                } catch (se: Exception) {
                    preferredPort
                }
            }
        }

        /**
         * Verifies if a given HTTP or HTTPS endpoint URL responds with a successful HTTP status.
         */
        fun verifyEndpoint(endpointUrl: String, timeoutMs: Int = 1000): Boolean {
            return try {
                val url = URL(endpointUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                conn.disconnect()
                code in 200..399
            } catch (e: Exception) {
                AvsLogger.d(TAG, "Endpoint probe failed for $endpointUrl: ${e.message}")
                false
            }
        }

        /**
         * Checks if the local loopback server responds to HTTP requests.
         */
        fun checkHttpReachable(port: Int, host: String = DEFAULT_LOCAL_HOST, timeoutMs: Int = 800): Boolean {
            val url = buildLocalEndpointUrl(port, host)
            return verifyEndpoint(url, timeoutMs)
        }

        /**
         * Builds the local endpoint URL for a given loopback port.
         */
        fun buildLocalEndpointUrl(port: Int, host: String = DEFAULT_LOCAL_HOST): String {
            return "http://$host:$port"
        }

        /**
         * Builds the remote GitHub Codespace forwarded web endpoint URL.
         */
        fun buildCodespaceEndpointUrl(codespaceName: String, port: Int = DEFAULT_CODESPACE_PORT): String {
            return "https://$codespaceName-$port.app.github.dev"
        }
    }
}

