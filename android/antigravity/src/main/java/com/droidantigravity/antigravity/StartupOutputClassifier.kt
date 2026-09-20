package com.droidantigravity.antigravity

/**
 * Classifies CLI stdout/stderr output into specific startup states or actionable error conditions.
 */
object StartupOutputClassifier {

    /**
     * Checks if the output contains a workspace trust prompt waiting for user confirmation.
     */
    fun isTrustPrompt(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("trust the contents of this project", ignoreCase = true) ||
               clean.contains("trust this folder", ignoreCase = true) ||
               clean.contains("Do you trust", ignoreCase = true) ||
               clean.contains("permission to read, edit, and execute files here", ignoreCase = true)
    }

    /**
     * Checks if the output indicates that authentication is required to proceed.
     */
    fun isAuthenticationRequired(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        // Only classify explicit authentication failures as fatal. Informational
        // login/browser prompts must be allowed to remain interactive so the
        // official CLI can complete its own authentication flow.
        return clean.contains("authentication required", ignoreCase = true) ||
               clean.contains("authentication failed", ignoreCase = true) ||
               clean.contains("not authenticated", ignoreCase = true) ||
               clean.contains("unauthenticated", ignoreCase = true) ||
               clean.contains("no authentication methods available", ignoreCase = true) ||
               clean.contains("error getting token source", ignoreCase = true)
    }

    /**
     * Checks if the output indicates that Remote Control is unavailable or unsupported.
     */
    fun isRemoteControlUnavailable(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("remote control is not supported", ignoreCase = true) ||
               clean.contains("remote control is disabled", ignoreCase = true) ||
               clean.contains("remote control unavailable", ignoreCase = true) ||
               clean.contains("unknown flag: --remote-control", ignoreCase = true) ||
               clean.contains("flag provided but not defined: -remote-control", ignoreCase = true) ||
               clean.contains("flag provided but not defined: --remote-control", ignoreCase = true) ||
               clean.contains("feature not enabled", ignoreCase = true)
    }

    /**
     * Checks if the output indicates a network error during startup.
     */
    fun isNetworkError(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("no route to host", ignoreCase = true) ||
               clean.contains("network is unreachable", ignoreCase = true) ||
               clean.contains("could not resolve host", ignoreCase = true) ||
               clean.contains("dial tcp", ignoreCase = true) ||
               clean.contains("connection refused", ignoreCase = true) ||
               clean.contains("failed to connect to jetski", ignoreCase = true) ||
               clean.contains("failed to connect to", ignoreCase = true) ||
               clean.contains("certificate verify failed", ignoreCase = true) ||
               clean.contains("TLS handshake error", ignoreCase = true)
    }

    /**
     * Checks if the output indicates a failure while establishing the remote control session.
     */
    fun isRemoteControlStartFailed(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("failed to start remote control", ignoreCase = true) ||
               clean.contains("could not establish reverse tunnel", ignoreCase = true) ||
               clean.contains("error opening TTY", ignoreCase = true) ||
               clean.contains("could not open TTY", ignoreCase = true)
    }

    /**
     * Classifies a fatal startup state into an actionable error.
     */
    fun classifyError(text: String, exitCode: Int? = null): AntigravityStartupError {
        return when {
            isAuthenticationRequired(text) -> AntigravityStartupError.AUTH_REQUIRED
            isRemoteControlUnavailable(text) -> AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE
            isNetworkError(text) -> AntigravityStartupError.NETWORK_ERROR
            isRemoteControlStartFailed(text) -> AntigravityStartupError.FAILED
            exitCode != null && exitCode != -2 -> AntigravityStartupError.PROCESS_EXITED
            else -> AntigravityStartupError.URL_NOT_DETECTED
        }
    }
}
