package com.droidantigravity.core.diagnostics

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized, production-grade diagnostic logging engine for DroidAntigravity.
 *
 * Provides:
 * - Structured JSON Lines persistent logging (app.log, app.previous.log)
 * - Separate streams for STDOUT and STDERR (stdout.log, stderr.log)
 * - In-memory event buffer with reactive StateFlow for the UI log viewer
 * - Bounded log rotation to prevent unlimited disk growth
 * - Non-blocking asynchronous writing via background coroutine channel
 * - Universal credential and token redaction via [DiagnosticSanitizer]
 * - Unique operation ID generation (e.g. AGY-A82F91)
 */
object DiagnosticLogger {

    private const val DEFAULT_TAG = "DroidAntigravity"
    private const val MAX_MEMORY_EVENTS = 2000
    private const val MAX_LOG_FILE_SIZE_BYTES = 5L * 1024 * 1024 // 5 MB

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<LogTask>(capacity = 1000)

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    private val memoryEvents = ArrayList<DiagnosticEvent>(MAX_MEMORY_EVENTS)

    @Volatile private var baseDir: File? = null
    @Volatile private var appLogFile: File? = null
    @Volatile private var appPreviousLogFile: File? = null
    @Volatile private var stdoutLogFile: File? = null
    @Volatile private var stderrLogFile: File? = null

    private val appLogSize = AtomicLong(0)

    // Optional listener for tests or external log forwarders
    var onEventLogged: ((DiagnosticEvent) -> Unit)? = null

    init {
        // Start async disk writer loop
        scope.launch {
            for (task in logChannel) {
                try {
                    when (task) {
                        is LogTask.WriteEvent -> writeEventToDisk(task.event)
                        is LogTask.WriteStream -> writeStreamToDisk(task.stream, task.line)
                        is LogTask.Flush -> { /* flush handled by streams */ }
                    }
                } catch (e: Throwable) {
                    safeLogcat(LogLevel.ERROR, DEFAULT_TAG, "Failed to write diagnostic task to disk", e)
                }
            }
        }
    }

    private sealed class LogTask {
        data class WriteEvent(val event: DiagnosticEvent) : LogTask()
        data class WriteStream(val stream: String, val line: String) : LogTask()
        object Flush : LogTask()
    }

    /**
     * Initializes directory paths for persistent file logging.
     */
    fun init(logsDir: File) {
        synchronized(this) {
            baseDir = logsDir.apply { mkdirs() }
            appLogFile = File(logsDir, "app.log")
            appPreviousLogFile = File(logsDir, "app.previous.log")
            stdoutLogFile = File(logsDir, "stdout.log")
            stderrLogFile = File(logsDir, "stderr.log")

            if (appLogFile?.exists() == true) {
                appLogSize.set(appLogFile!!.length())
            } else {
                appLogSize.set(0)
            }
        }
        info("Logger", "init", "DiagnosticLogger initialized with log dir: ${logsDir.absolutePath}")
    }

    /**
     * Generates a unique, traceable Operation ID with a subsystem prefix.
     * Example: prefix "AGY" -> "AGY-8F21B4"
     */
    fun createOperationId(prefix: String): String {
        val shortId = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        return "$prefix-$shortId"
    }

    /**
     * Primary logging method. Accepts a [DiagnosticEvent] and routes it through
     * memory buffer, disk queue, and Android Logcat with automatic credential redaction.
     */
    fun log(event: DiagnosticEvent) {
        val sanitizedEvent = sanitize(event)

        // 1. Update in-memory reactive flow
        synchronized(memoryEvents) {
            memoryEvents.add(sanitizedEvent)
            while (memoryEvents.size > MAX_MEMORY_EVENTS) {
                memoryEvents.removeAt(0)
            }
            _events.value = ArrayList(memoryEvents)
        }

        // 2. Queue for asynchronous persistent disk write
        logChannel.trySend(LogTask.WriteEvent(sanitizedEvent))

        // 3. Android Logcat bridge
        safeLogcat(
            sanitizedEvent.level,
            sanitizedEvent.component,
            sanitizedEvent.toFormattedString(),
            null
        )

        // 4. Callback
        try {
            onEventLogged?.invoke(sanitizedEvent)
        } catch (ignored: Throwable) {}
    }

    private fun sanitize(event: DiagnosticEvent): DiagnosticEvent {
        val cleanMessage = DiagnosticSanitizer.redact(event.message)
        val cleanError = event.error?.let { DiagnosticSanitizer.redact(it) }
        return event.copy(message = cleanMessage, error = cleanError)
    }

    // Convenience logging methods

    fun v(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) {
        log(DiagnosticEvent(level = LogLevel.VERBOSE, component = component, event = event, message = message, operationId = operationId, processId = processId, exitCode = exitCode))
    }

    fun d(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) {
        log(DiagnosticEvent(level = LogLevel.DEBUG, component = component, event = event, message = message, operationId = operationId, processId = processId, exitCode = exitCode))
    }

    fun i(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) {
        log(DiagnosticEvent(level = LogLevel.INFO, component = component, event = event, message = message, operationId = operationId, processId = processId, exitCode = exitCode))
    }

    fun w(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) {
        log(DiagnosticEvent(level = LogLevel.WARN, component = component, event = event, message = message, operationId = operationId, processId = processId, exitCode = exitCode))
    }

    fun e(component: String, event: String, message: String, throwable: Throwable? = null, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) {
        val errStr = throwable?.let { "${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString()}" }
        log(DiagnosticEvent(
            level = LogLevel.ERROR,
            component = component,
            event = event,
            message = message,
            operationId = operationId,
            processId = processId,
            error = errStr,
            exitCode = exitCode
        ))
    }

    fun info(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) =
        i(component, event, message, operationId, processId, exitCode)

    fun debug(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) =
        d(component, event, message, operationId, processId, exitCode)

    fun warn(component: String, event: String, message: String, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) =
        w(component, event, message, operationId, processId, exitCode)

    fun error(component: String, event: String, message: String, throwable: Throwable? = null, operationId: String? = null, processId: Int? = null, exitCode: Int? = null) =
        e(component, event, message, throwable, operationId, processId, exitCode)

    /**
     * Executes [block] while logging operation start, completion duration, and error handling.
     */
    inline fun <T> trace(component: String, operation: String, operationId: String? = null, processId: Int? = null, block: () -> T): T {
        i(component, "${operation}_started", "Starting $operation", operationId = operationId, processId = processId)
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val dur = System.currentTimeMillis() - start
            log(DiagnosticEvent(
                level = LogLevel.INFO,
                component = component,
                event = "${operation}_completed",
                message = "Completed $operation in ${dur}ms",
                operationId = operationId,
                processId = processId,
                durationMs = dur
            ))
            result
        } catch (t: Throwable) {
            val dur = System.currentTimeMillis() - start
            log(DiagnosticEvent(
                level = LogLevel.ERROR,
                component = component,
                event = "${operation}_failed",
                message = "Failed $operation after ${dur}ms: ${t.message}",
                operationId = operationId,
                processId = processId,
                durationMs = dur,
                error = "${t.javaClass.simpleName}: ${t.message}"
            ))
            throw t
        }
    }

    /**
     * Records one line or chunk of STDOUT output separately into stdout.log
     * and as a structured event.
     */
    fun recordStdout(operationId: String?, processId: Int?, content: String) {
        val clean = DiagnosticSanitizer.redact(content)
        val formatted = "$operationId STDOUT pid=$processId $clean"
        logChannel.trySend(LogTask.WriteStream("STDOUT", formatted))

        log(DiagnosticEvent(
            level = LogLevel.DEBUG,
            component = "NativeSpawn",
            event = "stdout",
            message = clean,
            operationId = operationId,
            processId = processId,
            stream = "STDOUT"
        ))
    }

    /**
     * Records one line or chunk of STDERR output separately into stderr.log
     * and as a structured event.
     */
    fun recordStderr(operationId: String?, processId: Int?, content: String) {
        val clean = DiagnosticSanitizer.redact(content)
        val formatted = "$operationId STDERR pid=$processId $clean"
        logChannel.trySend(LogTask.WriteStream("STDERR", formatted))

        log(DiagnosticEvent(
            level = LogLevel.WARN,
            component = "NativeSpawn",
            event = "stderr",
            message = clean,
            operationId = operationId,
            processId = processId,
            stream = "STDERR"
        ))
    }

    /**
     * Records a global or unexpected exception.
     */
    fun recordError(throwable: Throwable, component: String = "App", operationId: String? = null, appState: String? = null, message: String? = null) {
        val msg = message ?: "Uncaught or fatal exception in $component: ${throwable.message}"
        val stackTrace = throwable.stackTraceToString()
        val cleanTrace = DiagnosticSanitizer.redact(stackTrace)

        log(DiagnosticEvent(
            level = LogLevel.ERROR,
            component = component,
            event = "exception",
            message = msg,
            operationId = operationId,
            state = appState,
            error = "${throwable.javaClass.name}: ${throwable.message}\n$cleanTrace"
        ))
    }

    // Disk I/O & Bounded Log Rotation

    private fun writeEventToDisk(event: DiagnosticEvent) {
        val file = appLogFile ?: return
        try {
            val jsonLine = event.toJson() + "\n"
            val bytes = jsonLine.toByteArray(StandardCharsets.UTF_8)

            checkRotation(file, bytes.size.toLong())

            FileOutputStream(file, true).use { out ->
                out.write(bytes)
                out.flush()
            }
            appLogSize.addAndGet(bytes.size.toLong())
        } catch (e: Throwable) {
            safeLogcat(LogLevel.ERROR, DEFAULT_TAG, "Error writing event to app.log", e)
        }
    }

    private fun writeStreamToDisk(stream: String, line: String) {
        val file = if (stream == "STDERR") stderrLogFile else stdoutLogFile
        if (file == null) return

        try {
            val entry = "[${DiagnosticEvent.currentIsoTimestamp()}] $line\n"
            val bytes = entry.toByteArray(StandardCharsets.UTF_8)
            FileOutputStream(file, true).use { out ->
                out.write(bytes)
                out.flush()
            }
        } catch (e: Throwable) {
            safeLogcat(LogLevel.ERROR, DEFAULT_TAG, "Error writing stream $stream to disk", e)
        }
    }

    private fun checkRotation(currentFile: File, additionalBytes: Long) {
        if (appLogSize.get() + additionalBytes > MAX_LOG_FILE_SIZE_BYTES) {
            try {
                val prev = appPreviousLogFile
                if (prev != null) {
                    if (prev.exists()) prev.delete()
                    currentFile.renameTo(prev)
                } else {
                    currentFile.delete()
                }
                currentFile.createNewFile()
                appLogSize.set(0)
            } catch (e: Throwable) {
                safeLogcat(LogLevel.WARN, DEFAULT_TAG, "Failed to rotate log file", e)
            }
        }
    }

    private fun safeLogcat(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        try {
            when (level) {
                LogLevel.VERBOSE -> Log.v(tag, msg)
                LogLevel.DEBUG -> Log.d(tag, msg)
                LogLevel.INFO -> Log.i(tag, msg)
                LogLevel.WARN -> Log.w(tag, msg, t)
                LogLevel.ERROR -> Log.e(tag, msg, t)
            }
        } catch (ignored: Throwable) {
            // Not in Android runtime (e.g. JVM unit test without robolectric)
        }
    }

    /**
     * Clears all in-memory events and truncates disk logs.
     */
    fun clear() {
        synchronized(memoryEvents) {
            memoryEvents.clear()
            _events.value = emptyList()
        }
        try {
            appLogFile?.writeText("")
            appPreviousLogFile?.delete()
            stdoutLogFile?.writeText("")
            stderrLogFile?.writeText("")
            appLogSize.set(0)
        } catch (ignored: Throwable) {}
    }

    // In-memory queries for UI

    fun getRecentEvents(count: Int = 100): List<DiagnosticEvent> {
        synchronized(memoryEvents) {
            return memoryEvents.takeLast(count)
        }
    }

    fun filter(
        component: String? = null,
        level: LogLevel? = null,
        operationId: String? = null,
        query: String? = null
    ): List<DiagnosticEvent> {
        synchronized(memoryEvents) {
            return memoryEvents.filter { event ->
                (component == null || event.component.equals(component, ignoreCase = true)) &&
                (level == null || event.level == level) &&
                (operationId == null || event.operationId == operationId) &&
                (query.isNullOrBlank() || event.message.contains(query, ignoreCase = true) || event.event.contains(query, ignoreCase = true))
            }
        }
    }
}
