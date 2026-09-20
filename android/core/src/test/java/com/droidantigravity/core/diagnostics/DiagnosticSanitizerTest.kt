package com.droidantigravity.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {

    @Test
    fun testRedactBearerToken() {
        val input = "Received Bearer ya29.a0AfH6SMD... token for session"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("Bearer token must be redacted", result.contains("ya29.a0AfH6SMD"))
        assertTrue("Redaction placeholder must be present", result.contains("<redacted>") || result.contains("<oauth-token-redacted>"))
    }

    @Test
    fun testRedactApiKey() {
        val input = "Connecting with api_key=secret_123456789 and AIzaSyD98765432101234567890123456789012"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("Raw api_key must not be present", result.contains("secret_123456789"))
        assertFalse("Raw Google API key must not be present", result.contains("AIzaSyD98765432101234567890123456789012"))
        assertTrue("Placeholder should be inserted", result.contains("<redacted>") || result.contains("<api-key-redacted>"))
    }

    @Test
    fun testRedactCookie() {
        val input = "HTTP Request header: Cookie: session_id=abc123xyz; user=john"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("Cookie value must not be present", result.contains("session_id=abc123xyz"))
        assertTrue("Cookie header must show redacted", result.contains("Cookie: <redacted>"))
    }

    @Test
    fun testRedactPassword() {
        val input = "Authentication failed for user=admin with password=SuperSecretPassword123!"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("Password must not be present", result.contains("SuperSecretPassword123!"))
        assertTrue("Password key must show redacted", result.contains("password=<redacted>"))
    }

    @Test
    fun testRedactAuthorizationHeader() {
        val input = "Headers: Authorization: Basic dXNlcjpwYXNz"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("Auth credentials must not be present", result.contains("dXNlcjpwYXNz"))
        assertTrue("Authorization header must show redacted", result.contains("Authorization: Basic <redacted>"))
    }

    @Test
    fun testRedactSensitiveUrl() {
        val input = "Redirecting to https://accounts.google.com/oauth?client_secret=secret123&access_token=token456&state=xyz"
        val result = DiagnosticSanitizer.redact(input)
        assertFalse("client_secret in URL must be redacted", result.contains("secret123"))
        assertFalse("access_token in URL must be redacted", result.contains("token456"))
        assertTrue("URL must retain state param", result.contains("state=xyz"))
    }

    @Test
    fun testRedactRemoteControlUrl() {
        val url = "https://antigravity.google.com/r/c/8f21b4-7d9a-4e21?p=c%2Fsession123"
        val redacted = DiagnosticSanitizer.redactRemoteControlUrl(url)
        assertEquals("https://antigravity.google.com/r/<redacted>", redacted)
    }

    @Test
    fun testSanitizeEnvironmentWhitelist() {
        val env = mapOf(
            "PATH" to "/usr/bin:/bin",
            "TERM" to "xterm-256color",
            "SECRET_TOKEN" to "super_secret_token",
            "PASSWORD" to "my_db_password"
        )
        val sanitized = DiagnosticSanitizer.sanitizeEnvironment(env)
        assertEquals("/usr/bin:/bin", sanitized["PATH"])
        assertEquals("xterm-256color", sanitized["TERM"])
        assertEquals("<redacted>", sanitized["SECRET_TOKEN"])
        assertEquals("<redacted>", sanitized["PASSWORD"])
    }
}
