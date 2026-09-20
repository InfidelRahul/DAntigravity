package com.droidantigravity.antigravity

/**
 * Parser for extracting and normalizing session-scoped Remote Control URLs from terminal output.
 */
object RemoteControlUrlParser {
    const val REMOTE_CONTROL_HOST = "antigravity.google.com"

    // Matches ANSI escape sequences (CSI sequences, OSC sequences, cursor movement, color codes)
    private val ANSI_PATTERN = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\].*?(\u0007|\u001B\\)""")

    // Matches official Remote Control URL shape: https://antigravity.google.com/r/...
    // Stops before whitespace, control chars, or common outer delimiters like < > " '
    private val REMOTE_URL_PATTERN = Regex(
        """https://antigravity\.google\.com/r/[^\s<>"'\u0000-\u001F]+""",
        RegexOption.IGNORE_CASE
    )

    // Trailing punctuation characters that may trail a URL printed at sentence end or formatted in markdown/text
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'')

    /**
     * Strips ANSI escape sequences from terminal output.
     */
    fun stripAnsi(text: String): String {
        return ANSI_PATTERN.replace(text, "")
    }

    /**
     * Extracts and normalizes the Remote Control URL from raw or ANSI-encoded terminal output.
     * Trims any trailing punctuation.
     *
     * @return Valid Remote Control URL string or null if none found.
     */
    fun parseUrl(rawOutput: String): String? {
        val clean = stripAnsi(rawOutput)
        val match = REMOTE_URL_PATTERN.find(clean) ?: return null
        val trimmed = match.value.trimEnd(*TRAILING_PUNCTUATION)
        return if (trimmed.contains(REMOTE_CONTROL_HOST, ignoreCase = true)) trimmed else null
    }
}
