package com.droidantigravity.antigravity

import android.net.Uri

/**
 * Parser for detecting and validating candidate authentication URLs emitted by the
 * Antigravity CLI during OAuth login flows.
 */
object AuthenticationUrlParser {

    private val URL_REGEX = Regex(
        """https?://[^\s<>"'\u0000-\u001F]+""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'')

    /**
     * Extracts the first valid authentication URL from raw terminal or CLI output text.
     */
    fun extractAuthUrl(rawOutput: String): String? {
        val clean = RemoteControlUrlParser.stripAnsi(rawOutput)
        val matches = URL_REGEX.findAll(clean)
        for (match in matches) {
            val candidate = match.value.trimEnd(*TRAILING_PUNCTUATION)
            if (isAuthenticationUrl(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Validates whether a candidate URL is a legitimate OAuth / authentication URL.
     * Prevents false positives against Remote Control session URLs or docs.
     */
    fun isAuthenticationUrl(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https" && scheme != "http") return false

            val host = uri.host?.lowercase() ?: return false
            val path = uri.path?.lowercase().orEmpty()
            val query = uri.query?.lowercase().orEmpty()

            // Exclude Remote Control session URLs
            if ((host == "antigravity.google.com" || host == "antigravity.google" || host.endsWith(".antigravity.google")) &&
                path.startsWith("/r/")
            ) {
                return false
            }

            // Exclude documentation or marketing pages
            if (path.startsWith("/docs") || path.startsWith("/changelog") || path.startsWith("/support")) {
                return false
            }

            // 1. Google OAuth endpoints
            if (host == "accounts.google.com" || host.endsWith(".google.com")) {
                if (path.contains("oauth") || path.contains("auth") || path.contains("signin") ||
                    query.contains("client_id") || query.contains("response_type") || query.contains("redirect_uri")
                ) {
                    return true
                }
            }

            // 2. Official Antigravity auth endpoints
            if (host == "antigravity.google.com" || host == "antigravity.google" || host.endsWith(".antigravity.google")) {
                if (path.contains("auth") || path.contains("login") || path.contains("oauth") ||
                    query.contains("client_id") || query.contains("code") || query.contains("signin")
                ) {
                    return true
                }
            }

            // 3. Generic OAuth authorization indicators
            if (query.contains("client_id=") && (query.contains("response_type=") || query.contains("redirect_uri="))) {
                return true
            }

            false
        }.getOrDefault(false)
    }
}
