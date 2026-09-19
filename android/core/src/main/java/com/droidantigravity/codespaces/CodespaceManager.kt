package com.droidantigravity.codespaces

import android.content.Context
import android.content.SharedPreferences
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.CodespaceState
import com.droidantigravity.core.Result
import com.droidantigravity.core.runCatchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Codespaces Manager for DroidAntigravity.
 *
 * Implements first-class remote environment lifecycle:
 * - Secure GitHub authentication token storage in Android native storage (outside guest Linux).
 * - Queries live Codespaces state via official GitHub REST API v2022-11-28.
 * - Supports listing, starting, stopping, and creating Codespaces.
 * - Discovers and builds web endpoints for Antigravity running in Codespaces.
 */
class CodespaceManager(private val context: Context) {

    companion object {
        private const val TAG = "CodespaceManager"
        private const val PREFS_NAME = "droid_antigravity_github"
        private const val KEY_GITHUB_TOKEN = "github_auth_token"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val GITHUB_API_VERSION = "2022-11-28"

        /**
         * Maps raw GitHub API state string to strongly typed CodespaceState enum.
         */
        fun mapApiState(rawState: String?): CodespaceState {
            return when (rawState?.lowercase()?.trim()) {
                "available" -> CodespaceState.AVAILABLE
                "starting" -> CodespaceState.STARTING
                "running" -> CodespaceState.RUNNING
                "stopping" -> CodespaceState.STOPPING
                "stopped" -> CodespaceState.STOPPED
                "shutdown" -> CodespaceState.SHUTDOWN
                "failed" -> CodespaceState.FAILED
                "exporting" -> CodespaceState.EXPORTING
                "updating" -> CodespaceState.UPDATING
                "rebuilding" -> CodespaceState.REBUILDING
                else -> CodespaceState.UNKNOWN
            }
        }

        /**
         * Parses a single Codespace JSON object from GitHub API response.
         */
        fun parseCodespaceJson(obj: JSONObject): CodespaceItem {
            val name = obj.optString("name", "")
            val rawState = obj.optString("state", "Unknown")
            val webUrl = obj.optString("web_url", "")
            val lastUsedAt = if (obj.has("last_used_at") && !obj.isNull("last_used_at")) obj.optString("last_used_at") else null

            val repoObj = obj.optJSONObject("repository")
            val repoName = repoObj?.optString("name", "") ?: ""
            val repoFullName = repoObj?.optString("full_name", "") ?: ""

            val gitStatusObj = obj.optJSONObject("git_status")
            val branch = if (gitStatusObj != null && gitStatusObj.has("ref") && !gitStatusObj.isNull("ref")) {
                gitStatusObj.optString("ref")
            } else null

            return CodespaceItem(
                name = name,
                repositoryName = repoName,
                repositoryFullName = repoFullName,
                state = mapApiState(rawState),
                webUrl = webUrl,
                branch = branch,
                lastUsedAt = lastUsedAt
            )
        }

        /**
         * Parses a full list of Codespaces from GitHub API JSON response.
         */
        fun parseCodespacesList(jsonStr: String): List<CodespaceItem> {
            val result = mutableListOf<CodespaceItem>()
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("codespaces") ?: return result

            for (i in 0 until array.length()) {
                val itemObj = array.optJSONObject(i) ?: continue
                result.add(parseCodespaceJson(itemObj))
            }
            return result
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveGitHubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
        AvsLogger.i(TAG, "GitHub token securely updated")
    }

    fun getGitHubToken(): String? {
        val token = prefs.getString(KEY_GITHUB_TOKEN, null)?.trim()
        return if (!token.isNullOrEmpty()) token else null
    }

    fun clearGitHubToken() {
        prefs.edit().remove(KEY_GITHUB_TOKEN).apply()
        AvsLogger.i(TAG, "GitHub token cleared")
    }

    fun isAuthenticated(): Boolean = getGitHubToken() != null

    private fun openApiConnection(urlStr: String, method: String, token: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        conn.setRequestProperty("User-Agent", "DroidAntigravity/1.0.0")
        return conn
    }

    /**
     * Lists existing Codespaces for the authenticated user.
     */
    suspend fun listCodespaces(): Result<List<CodespaceItem>> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val token = getGitHubToken() ?: throw IllegalStateException("GitHub authentication token not configured")
            val endpoint = "$GITHUB_API_BASE/user/codespaces"
            val conn = openApiConnection(endpoint, "GET", token)

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("GitHub API error ($code): $errorBody")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseCodespacesList(body)
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Starts a stopped Codespace.
     */
    suspend fun startCodespace(codespaceName: String): Result<CodespaceItem> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val token = getGitHubToken() ?: throw IllegalStateException("GitHub authentication token not configured")
            val endpoint = "$GITHUB_API_BASE/user/codespaces/$codespaceName/start"
            val conn = openApiConnection(endpoint, "POST", token)

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Failed to start Codespace ($code): $errorBody")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseCodespaceJson(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Stops a running Codespace.
     */
    suspend fun stopCodespace(codespaceName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val token = getGitHubToken() ?: throw IllegalStateException("GitHub authentication token not configured")
            val endpoint = "$GITHUB_API_BASE/user/codespaces/$codespaceName/stop"
            val conn = openApiConnection(endpoint, "POST", token)

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Failed to stop Codespace ($code): $errorBody")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Gets live details and state of a single Codespace.
     */
    suspend fun getCodespace(codespaceName: String): Result<CodespaceItem> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val token = getGitHubToken() ?: throw IllegalStateException("GitHub authentication token not configured")
            val endpoint = "$GITHUB_API_BASE/user/codespaces/$codespaceName"
            val conn = openApiConnection(endpoint, "GET", token)

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Failed to get Codespace ($code): $errorBody")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseCodespaceJson(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Creates a new Codespace on a given repository.
     */
    suspend fun createCodespace(owner: String, repo: String, branch: String? = null): Result<CodespaceItem> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val token = getGitHubToken() ?: throw IllegalStateException("GitHub authentication token not configured")
            val endpoint = "$GITHUB_API_BASE/user/repos/$owner/$repo/codespaces"
            val conn = openApiConnection(endpoint, "POST", token)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject().apply {
                if (!branch.isNullOrBlank()) {
                    put("ref", branch)
                }
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Failed to create Codespace ($code): $errorBody")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseCodespaceJson(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Polls until a Codespace enters AVAILABLE or RUNNING state.
     */
    suspend fun waitForCodespaceReady(codespaceName: String, timeoutMs: Long = 120_000L): Result<CodespaceItem> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val res = getCodespace(codespaceName)
            if (res is Result.Success) {
                val item = res.data
                if (item.state == CodespaceState.AVAILABLE || item.state == CodespaceState.RUNNING) {
                    return res
                }
                if (item.state == CodespaceState.FAILED) {
                    return Result.Failure(IllegalStateException("Codespace entered FAILED state"))
                }
            }
            delay(3000)
        }
        return Result.Failure(IllegalStateException("Codespace did not become ready within timeout"))
    }
}
