package com.droidantigravity.antigravity

/**
 * Actionable error classifications for Antigravity Remote Control startup.
 */
enum class AntigravityStartupError(val description: String) {
    CLI_NOT_INSTALLED("Antigravity CLI is not installed or not found on PATH"),
    CLI_START_FAILED("Failed to spawn Antigravity CLI process"),
    CLI_EXITED_BEFORE_REMOTE_CONTROL("Antigravity CLI exited before publishing Remote Control URL"),
    AUTHENTICATION_REQUIRED("Antigravity CLI requires user authentication"),
    REMOTE_CONTROL_UNAVAILABLE("Remote Control feature is not available or disabled"),
    REMOTE_CONTROL_START_FAILED("Antigravity CLI failed to establish Remote Control session"),
    REMOTE_CONTROL_URL_NOT_DETECTED("No valid Remote Control URL detected in CLI output"),
    STARTUP_TIMEOUT("Timed out waiting for Antigravity Remote Control URL");
}

/**
 * Exception thrown when Antigravity Remote Control startup encounters a classified failure.
 */
class AntigravityStartupException(
    val error: AntigravityStartupError,
    message: String = error.description,
    val details: String? = null,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    override fun toString(): String {
        return "AntigravityStartupException(error=$error, message=$message, details=${details?.take(200)})"
    }
}
