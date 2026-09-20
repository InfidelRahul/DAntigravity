package com.droidantigravity.antigravity

/**
 * Actionable error classifications for Antigravity Remote Control startup.
 */
enum class AntigravityStartupError(val description: String) {
    NOT_INSTALLED("Antigravity CLI is not installed or not found on PATH"),
    STARTING("Antigravity CLI is starting"),
    START_FAILED("Failed to spawn Antigravity CLI process"),
    PROCESS_EXITED("Antigravity CLI process exited before Remote Control URL was published"),
    AUTH_REQUIRED("Antigravity CLI requires user authentication"),
    REMOTE_CONTROL_UNAVAILABLE("Remote Control feature is not available or disabled"),
    NETWORK_ERROR("Network connection error encountered during Remote Control startup"),
    STARTUP_TIMEOUT("Timed out waiting for Antigravity Remote Control URL"),
    URL_NOT_DETECTED("No valid Remote Control URL detected in CLI output"),
    FAILED("Antigravity Remote Control failed to start"),
    RUNNING("Antigravity Remote Control is running"),
    STOPPED("Antigravity Remote Control is stopped");

    companion object {
        // Backwards-compatible aliases
        val CLI_NOT_INSTALLED = NOT_INSTALLED
        val CLI_START_FAILED = START_FAILED
        val CLI_EXITED_BEFORE_REMOTE_CONTROL = PROCESS_EXITED
        val AUTHENTICATION_REQUIRED = AUTH_REQUIRED
        val REMOTE_CONTROL_START_FAILED = FAILED
        val REMOTE_CONTROL_URL_NOT_DETECTED = URL_NOT_DETECTED
    }
}

/**
 * Exception thrown when Antigravity Remote Control startup encounters a classified failure.
 */
class AntigravityStartupException(
    val error: AntigravityStartupError,
    message: String = error.description,
    val details: String? = null,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val reason: String? = null,
    val operationId: String? = null,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    override fun toString(): String {
        return "AntigravityStartupException(error=$error, operationId=$operationId, exitCode=$exitCode, message=$message, reason=$reason, details=${details?.take(200)})"
    }
}
