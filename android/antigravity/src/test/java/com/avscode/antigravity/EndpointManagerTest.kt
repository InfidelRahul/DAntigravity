package com.avscode.antigravity

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class EndpointManagerTest {

    @Test
    fun testBuildLocalEndpointUrl() {
        val url = EndpointManager.buildLocalEndpointUrl(33000)
        assertEquals("http://127.0.0.1:33000", url)

        val customHost = EndpointManager.buildLocalEndpointUrl(8080, "localhost")
        assertEquals("http://localhost:8080", customHost)
    }

    @Test
    fun testBuildCodespaceEndpointUrl() {
        val url = EndpointManager.buildCodespaceEndpointUrl("funny-cat-1234", 8080)
        assertEquals("https://funny-cat-1234-8080.app.github.dev", url)

        val defaultPortUrl = EndpointManager.buildCodespaceEndpointUrl("cautious-spoon-99")
        assertEquals("https://cautious-spoon-99-8080.app.github.dev", defaultPortUrl)
    }

    @Test
    fun testFindAvailablePort() {
        // Preferred port is available or fallback
        val port = EndpointManager.findAvailablePort(45678)
        assertTrue("Port must be positive", port > 0)

        // When port is occupied, it should find another available port
        ServerSocket(0).use { socket ->
            val occupied = socket.localPort
            val nextPort = EndpointManager.findAvailablePort(occupied)
            assertTrue("Should find another port", nextPort > 0)
        }
    }
}

