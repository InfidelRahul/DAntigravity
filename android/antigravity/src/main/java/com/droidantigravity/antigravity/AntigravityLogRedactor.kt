package com.droidantigravity.antigravity

/**
 * Sanitizes Antigravity CLI output for logging by redacting sensitive tokens, credentials,
 * cookies, and authorization headers while safely preserving hostnames and URLs.
 */
object AntigravityLogRedactor {
    private val AUTH_HEADER_PATTERN = Regex("""(?i)\b(authorization:\s*(?:bearer|basic)\s+)[^\s\r\n]+""")
    private val COOKIE_HEADER_PATTERN = Regex("""(?i)\b(cookie:\s*)[^\s\r\n;]+""")
    private val KEY_VALUE_SECRET_PATTERN = Regex(
        """(?i)\b(bearer|access_token|refresh_token|id_token|api[_-]?key|client_secret|password|passwd|secret|session(?:[_-]?token)?)\s*([:=])\s*([^\s,;&"']+)"""
    )
    private val GOOGLE_OAUTH_PATTERN = Regex("""ya29\.[A-Za-z0-9\-_]+""")

    /**
     * Redacts sensitive credentials from [text].
     */
    fun redact(text: String): String {
        var sanitized = text
        sanitized = AUTH_HEADER_PATTERN.replace(sanitized, "$1<redacted>")
        sanitized = COOKIE_HEADER_PATTERN.replace(sanitized, "$1<redacted>")
        sanitized = KEY_VALUE_SECRET_PATTERN.replace(sanitized) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
        sanitized = GOOGLE_OAUTH_PATTERN.replace(sanitized, "<oauth-token-redacted>")
        return sanitized
    }
}
