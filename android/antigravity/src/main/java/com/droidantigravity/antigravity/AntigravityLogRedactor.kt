package com.droidantigravity.antigravity

import com.droidantigravity.core.diagnostics.DiagnosticSanitizer

/**
 * Sanitizes Antigravity CLI output for logging by redacting sensitive tokens, credentials,
 * cookies, and authorization headers while safely preserving hostnames and URLs.
 * Delegates to centralized [DiagnosticSanitizer].
 */
object AntigravityLogRedactor {

    /**
     * Redacts sensitive credentials from [text].
     */
    fun redact(text: String): String {
        return DiagnosticSanitizer.redact(text)
    }

    /**
     * Redacts a Remote Control URL for safe display.
     */
    fun redactUrl(url: String): String {
        return DiagnosticSanitizer.redactRemoteControlUrl(url)
    }
}
