package com.avscode.codespaces

import com.avscode.core.CodespaceState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodespaceManagerTest {

    @Test
    fun testMapApiState() {
        assertEquals(CodespaceState.AVAILABLE, CodespaceManager.mapApiState("Available"))
        assertEquals(CodespaceState.AVAILABLE, CodespaceManager.mapApiState("available"))
        assertEquals(CodespaceState.RUNNING, CodespaceManager.mapApiState("Running"))
        assertEquals(CodespaceState.STARTING, CodespaceManager.mapApiState("Starting"))
        assertEquals(CodespaceState.STOPPED, CodespaceManager.mapApiState("Stopped"))
        assertEquals(CodespaceState.STOPPING, CodespaceManager.mapApiState("Stopping"))
        assertEquals(CodespaceState.SHUTDOWN, CodespaceManager.mapApiState("Shutdown"))
        assertEquals(CodespaceState.FAILED, CodespaceManager.mapApiState("Failed"))
        assertEquals(CodespaceState.UNKNOWN, CodespaceManager.mapApiState("something_random"))
        assertEquals(CodespaceState.UNKNOWN, CodespaceManager.mapApiState(null))
    }

    @Test
    fun testParseCodespaceJson() {
        val jsonStr = """
            {
                "id": 12345,
                "name": "glowing-winner-4j5g",
                "state": "Available",
                "web_url": "https://github.com/codespaces/glowing-winner-4j5g",
                "last_used_at": "2026-09-13T02:00:00Z",
                "repository": {
                    "name": "DroidAntigravity",
                    "full_name": "user/DroidAntigravity"
                },
                "git_status": {
                    "ref": "main"
                }
            }
        """.trimIndent()

        val item = CodespaceManager.parseCodespaceJson(JSONObject(jsonStr))
        assertEquals("glowing-winner-4j5g", item.name)
        assertEquals(CodespaceState.AVAILABLE, item.state)
        assertTrue(item.isRunning)
        assertFalse(item.isStopped)
        assertEquals("DroidAntigravity", item.repositoryName)
        assertEquals("user/DroidAntigravity", item.repositoryFullName)
        assertEquals("main", item.branch)
        assertEquals("https://github.com/codespaces/glowing-winner-4j5g", item.webUrl)
    }

    @Test
    fun testParseCodespacesList() {
        val jsonStr = """
            {
                "total_count": 2,
                "codespaces": [
                    {
                        "name": "running-codespace",
                        "state": "Running",
                        "repository": { "name": "repo1", "full_name": "org/repo1" }
                    },
                    {
                        "name": "stopped-codespace",
                        "state": "Stopped",
                        "repository": { "name": "repo2", "full_name": "org/repo2" }
                    }
                ]
            }
        """.trimIndent()

        val list = CodespaceManager.parseCodespacesList(jsonStr)
        assertEquals(2, list.size)
        assertEquals("running-codespace", list[0].name)
        assertEquals(CodespaceState.RUNNING, list[0].state)
        assertEquals("stopped-codespace", list[1].name)
        assertEquals(CodespaceState.STOPPED, list[1].state)
    }
}

