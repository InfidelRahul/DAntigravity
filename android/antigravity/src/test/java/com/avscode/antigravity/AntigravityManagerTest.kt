package com.avscode.antigravity

import org.junit.Assert.*
import org.junit.Test

class AntigravityManagerTest {

    @Test
    fun testBuildServerCommand() {
        val cmd = AntigravityManager.buildServerCommand(33000)
        assertTrue("Must specify port", cmd.contains("--port 33000"))
        assertTrue("Must specify host", cmd.contains("--host 127.0.0.1"))
        assertTrue("Must invoke remote-control start", cmd.contains("remote-control start"))
    }

    @Test
    fun testBuildSetupScript() {
        val script = AntigravityManager.buildSetupScript()
        assertTrue("Must contain shebang", script.startsWith("#!/bin/bash"))
        assertTrue("Must reference agy binary", script.contains("/usr/local/bin/agy"))
        assertTrue("Must make executable", script.contains("chmod +x /usr/local/bin/agy"))
    }
}

