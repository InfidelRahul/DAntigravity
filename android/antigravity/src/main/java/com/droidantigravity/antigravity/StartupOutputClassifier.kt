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
               (clean.contains("Do you trust", ignoreCase = true) && clean.contains("permission", ignoreCase = true))
    }

    /**
     * Checks if the output indicates that authentication is required to proceed.
     */
    fun isAuthenticationRequired(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("auth login", ignoreCase = true) ||
               clean.contains("authentication required", ignoreCase = true) ||
               clean.contains("not authenticated", ignoreCase = true) ||
               clean.contains("please log in", ignoreCase = true) ||
               clean.contains("you must be logged in", ignoreCase = true) ||
               clean.contains("unauthenticated", ignoreCase = true) ||
               clean.contains("login to continue", ignoreCase = true)
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
     * Checks if the output indicates a failure while establishing the remote control session.
     */
    fun isRemoteControlStartFailed(text: String): Boolean {
        val clean = RemoteControlUrlParser.stripAnsi(text)
        return clean.contains("failed to start remote control", ignoreCase = true) ||
               clean.contains("could not establish reverse tunnel", ignoreCase = true) ||
               clean.contains("failed to connect to jetski", ignoreCase = true) ||
               clean.contains("error opening TTY", ignoreCase = true) ||
               clean.contains("could not open TTY", ignoreCase = true)
    }

    /**
     * Classifies a fatal startup state into an actionable error.
     */
    fun classifyError(text: String, exitCode: Int? = null): AntigravityStartupError {
        return when {
            isAuthenticationRequired(text) -> AntigravityStartupError.AUTHENTICATION_REQUIRED
            isRemoteControlUnavailable(text) -> AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE
            isRemoteControlStartFailed(text) -> AntigravityStartupError.REMOTE_CONTROL_START_FAILED
            exitCode != null && exitCode != -2 -> AntigravityStartupError.CLI_EXITED_BEFORE_REMOTE_CONTROL
            else -> AntigravityStartupError.REMOTE_CONTROL_URL_NOT_DETECTED
        }
    }
}
