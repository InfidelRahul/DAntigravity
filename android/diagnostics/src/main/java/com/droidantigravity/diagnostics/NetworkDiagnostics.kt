package com.droidantigravity.diagnostics

import com.droidantigravity.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Diagnostic utility for evaluating network reachability, DNS resolution,
 * and HTTPS connectivity for Antigravity Remote Control endpoints.
 */
object NetworkDiagnostics {

    private const val TAG = "Network"

    data class NetworkCheckResult(
        val success: Boolean,
        val target: String,
        val durationMs: Long,
        val details: String,
        val errorCategory: String? = null
    )

    /**
     * Checks DNS resolution for [hostname].
     */
    suspend fun checkDns(
        hostname: String = "antigravity.google.com",
        operationId: String? = null
    ): NetworkCheckResult = withContext(Dispatchers.IO) {
        DiagnosticLogger.d(TAG, "DNS_CHECK_STARTED", "Resolving DNS for $hostname", operationId = operationId)
        val start = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(hostname)
            val duration = System.currentTimeMillis() - start
            val ips = addresses.joinToString(", ") { it.hostAddress ?: "" }

            DiagnosticLogger.i(
                TAG,
                "DNS_CHECK_SUCCESS",
                "DNS resolved for $hostname in ${duration}ms: [$ips]",
                operationId = operationId
            )
            NetworkCheckResult(true, hostname, duration, "Resolved: $ips")
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val errorCategory = e.javaClass.simpleName
            DiagnosticLogger.e(
                TAG,
                "DNS_CHECK_FAILED",
                "DNS resolution failed for $hostname in ${duration}ms: ${e.message}",
                e,
                operationId = operationId
            )
            NetworkCheckResult(false, hostname, duration, e.message ?: "Unknown DNS error", errorCategory)
        }
    }

    /**
     * Checks HTTPS handshake and connectivity to [urlStr].
     */
    suspend fun checkHttps(
        urlStr: String = "https://antigravity.google.com",
        operationId: String? = null
    ): NetworkCheckResult = withContext(Dispatchers.IO) {
        DiagnosticLogger.d(TAG, "HTTPS_CHECK_STARTED", "Testing HTTPS connection to $urlStr", operationId = operationId)
        val start = System.currentTimeMillis()
        var conn: HttpsURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "HEAD"
            conn.connect()

            val responseCode = conn.responseCode
            val duration = System.currentTimeMillis() - start

            DiagnosticLogger.i(
                TAG,
                "HTTPS_CHECK_SUCCESS",
                "HTTPS connection to ${url.host} succeeded with HTTP $responseCode in ${duration}ms",
                operationId = operationId
            )
            NetworkCheckResult(true, urlStr, duration, "HTTP $responseCode")
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val errorCategory = e.javaClass.simpleName
            DiagnosticLogger.e(
                TAG,
                "HTTPS_CHECK_FAILED",
                "HTTPS connection failed to $urlStr in ${duration}ms: ${e.message}",
                e,
                operationId = operationId
            )
            NetworkCheckResult(false, urlStr, duration, e.message ?: "HTTPS error", errorCategory)
        } finally {
            conn?.disconnect()
        }
    }
}
