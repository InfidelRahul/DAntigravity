package com.droidantigravity.core.diagnostics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tempFolder.newFolder("logs")
        DiagnosticLogger.init(logsDir)
        DiagnosticLogger.clear()
    }

    @Test
    fun testEventCreationAndFormatting() {
        val event = DiagnosticEvent(
            level = LogLevel.INFO,
            component = "Antigravity",
            event = "start",
            message = "Starting CLI",
            operationId = "AGY-8F214A",
            processId = 12345
        )

        assertEquals(LogLevel.INFO, event.level)
        assertEquals("Antigravity", event.component)
        assertEquals("start", event.event)
        assertEquals("Starting CLI", event.message)
        assertEquals("AGY-8F214A", event.operationId)
        assertEquals(12345, event.processId)

        val json = event.toJson()
        assertTrue(json.contains("\"level\":\"INFO\""))
        assertTrue(json.contains("\"component\":\"Antigravity\""))
        assertTrue(json.contains("\"operationId\":\"AGY-8F214A\""))
        assertTrue(json.contains("\"processId\":12345"))

        val parsed = DiagnosticEvent.fromJson(json)
        assertNotNull(parsed)
        assertEquals(event.level, parsed!!.level)
        assertEquals(event.component, parsed.component)
        assertEquals(event.operationId, parsed.operationId)
        assertEquals(event.processId, parsed.processId)
        assertEquals(event.message, parsed.message)
    }

    @Test
    fun testOperationIdGeneration() {
        val id1 = DiagnosticLogger.createOperationId("AGY")
        val id2 = DiagnosticLogger.createOperationId("AGY")
        val rootfsId = DiagnosticLogger.createOperationId("ROOTFS")

        assertTrue(id1.startsWith("AGY-"))
        assertTrue(id2.startsWith("AGY-"))
        assertTrue(rootfsId.startsWith("ROOTFS-"))
        assertNotEquals(id1, id2)
    }

    @Test
    fun testRedactionDuringLogging() {
        val opId = DiagnosticLogger.createOperationId("TEST")
        DiagnosticLogger.info(
            component = "Auth",
            event = "token_received",
            message = "User token: Bearer ya29.SECRET_TOKEN_VALUE",
            operationId = opId
        )

        val events = DiagnosticLogger.filter(operationId = opId)
        assertEquals(1, events.size)
        val event = events[0]
        assertFalse(event.message.contains("ya29.SECRET_TOKEN_VALUE"))
        assertTrue(event.message.contains("<redacted>") || event.message.contains("<oauth-token-redacted>"))
    }

    @Test
    fun testSeparateStdoutAndStderrRecording() {
        val opId = DiagnosticLogger.createOperationId("STREAM_TEST")
        DiagnosticLogger.recordStdout(opId, 555, "Normal CLI output line")
        DiagnosticLogger.recordStderr(opId, 555, "Error: something failed")

        val stdoutEvents = DiagnosticLogger.getRecentEvents(10).filter { it.stream == "STDOUT" }
        val stderrEvents = DiagnosticLogger.getRecentEvents(10).filter { it.stream == "STDERR" }

        assertTrue(stdoutEvents.isNotEmpty())
        assertTrue(stderrEvents.isNotEmpty())
        assertEquals("STDOUT", stdoutEvents.last().stream)
        assertEquals("STDERR", stderrEvents.last().stream)
        assertEquals(LogLevel.WARN, stderrEvents.last().level)
    }

    @Test
    fun testConcurrentLoggingSafety() {
        val threadCount = 10
        val logsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    val opId = "THREAD-$i"
                    for (j in 0 until logsPerThread) {
                        DiagnosticLogger.i("WorkerThread", "event_$j", "Message $j from thread $i", operationId = opId)
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("All threads should complete within 10s", latch.await(10, TimeUnit.SECONDS))
        assertEquals("No errors during concurrent logging", 0, errorCount.get())

        val events = DiagnosticLogger.getRecentEvents(500)
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun testErrorRecordingWithStackTrace() {
        val opId = DiagnosticLogger.createOperationId("ERR_TEST")
        val ex = IllegalStateException("Something broke unexpectedly")

        DiagnosticLogger.recordError(ex, component = "TestComponent", operationId = opId, appState = "CRASH_STATE")

        val events = DiagnosticLogger.filter(operationId = opId)
        assertEquals(1, events.size)
        val errEvent = events[0]
        assertEquals(LogLevel.ERROR, errEvent.level)
        assertNotNull(errEvent.error)
        assertTrue(errEvent.error!!.contains("IllegalStateException: Something broke unexpectedly"))
    }
}
