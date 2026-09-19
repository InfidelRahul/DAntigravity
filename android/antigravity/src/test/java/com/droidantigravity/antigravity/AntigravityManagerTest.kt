package com.droidantigravity.antigravity

import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityManagerTest {
    @Test
    fun remoteControlUrlPatternMatchesOfficialShape() {
        val url = "https://antigravity.google.com/r/instance-123?p=c%2Fconversation-456"
        val pattern = Regex("""https://antigravity\.google\.com/r/[^\s<>"']+""", RegexOption.IGNORE_CASE)
        assertTrue(pattern.matches(url))
    }
}
