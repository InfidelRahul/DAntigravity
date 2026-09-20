package com.droidantigravity.core.diagnostics

/**
 * Centralized diagnostic sanitizer for redacting sensitive credentials,
 * tokens, cookies, passwords, private keys, and authorization headers
 * across all DroidAntigravity components.
 */
object DiagnosticSanitizer {

    private val AUTH_HEADER_PATTERN = Regex(
        """(?i)\b(authorization:\s*(?:bearer|basic)\s+)[^\s\r\n]+"""
    )
    private val BEARER_TOKEN_PATTERN = Regex(
        """(?i)\b(bearer\s+)[A-Za-z0-9\-_.]+"""
    )
    private val COOKIE_HEADER_PATTERN = Regex(
        """(?i)\b(cookie:\s*)([^\s\r\n;]+(?:;\s*[^\s\r\n;]+)*)"""
    )
    private val KEY_VALUE_SECRET_PATTERN = Regex(
        """(?i)\b(bearer|access_token|refresh_token|id_token|api[_-]?key|apikey|client_secret|client[_-]?id|password|passwd|pwd|secret|session(?:[_-]?token)?|auth(?:[_-]?token)?)\s*([:=])\s*([^\s,;&"']+)"""
    )
    private val GOOGLE_OAUTH_PATTERN = Regex(
        """ya29\.[A-Za-z0-9\-_]+"""
    )
    private val GOOGLE_API_KEY_PATTERN = Regex(
        """AIza[0-9A-Za-z\-_]{35}"""
    )
    private val PRIVATE_KEY_PATTERN = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""
    )
    private val SENSITIVE_URL_PARAM_PATTERN = Regex(
        """(?i)([?&](?:access_token|token|api[_-]?key|secret|password|client_secret)=)[^&\s]+"""
    )
    private val REMOTE_CONTROL_URL_PATTERN = Regex(
        """https://antigravity\.google\.com/r/[a-zA-Z0-9_\-\.\~%!$&'()*+,;=:@/?]*"""
    )

    /**
     * Redacts all sensitive information from [text].
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var sanitized = text

        // 1. Private keys
        sanitized = PRIVATE_KEY_PATTERN.replace(sanitized, "<private-key-redacted>")

        // 2. Authorization headers
        sanitized = AUTH_HEADER_PATTERN.replace(sanitized, "$1<redacted>")

        // 3. Bearer tokens
        sanitized = BEARER_TOKEN_PATTERN.replace(sanitized, "$1<redacted>")

        // 4. Cookie headers
        sanitized = COOKIE_HEADER_PATTERN.replace(sanitized, "$1<redacted>")

        // 5. Key-value secrets (e.g. password=..., api_key=...)
        sanitized = KEY_VALUE_SECRET_PATTERN.replace(sanitized) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }

        // 6. Google OAuth & API keys
        sanitized = GOOGLE_OAUTH_PATTERN.replace(sanitized, "<oauth-token-redacted>")
        sanitized = GOOGLE_API_KEY_PATTERN.replace(sanitized, "<api-key-redacted>")

        // 7. Sensitive query parameters in URLs
        sanitized = SENSITIVE_URL_PARAM_PATTERN.replace(sanitized, "$1<redacted>")

        return sanitized
    }

    /**
     * Redacts a Remote Control URL for safe logging while preserving domain context.
     * Example: "https://antigravity.google.com/r/c/123-456?p=..." -> "https://antigravity.google.com/r/<redacted>"
     */
    fun redactRemoteControlUrl(url: String): String {
        return REMOTE_CONTROL_URL_PATTERN.replace(url, "https://antigravity.google.com/r/<redacted>")
    }

    /**
     * Redacts environment variable map using a strict safe key whitelist.
     * Non-whitelisted or sensitive variables have their values masked.
     */
    fun sanitizeEnvironment(env: Map<String, String>): Map<String, String> {
        val safeKeys = setOf(
            "PATH", "TERM", "COLORTERM", "HOME", "SHELL", "LANG", "LC_ALL",
            "USER", "LOGNAME", "HOSTNAME", "PWD", "SHLVL", "_",
            "AGY_CLI_HIDE_LOGO", "ANDROID_DATA", "ANDROID_ROOT"
        )
        return env.mapValues { (key, value) ->
            if (safeKeys.contains(key.uppercase())) {
                redact(value)
            } else {
                "<redacted>"
            }
        }
    }
}
