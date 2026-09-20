package com.droidantigravity.core

import com.droidantigravity.core.diagnostics.DiagnosticEvent
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backward-compatible logging bridge for DroidAntigravity components.
 * Delegates all operations to [DiagnosticLogger].
 */
object AvsLogger {
    private const val TAG = "DroidAntigravity"

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _errors = MutableStateFlow<List<LogEntry>>(emptyList())
    val errors: StateFlow<List<LogEntry>> = _errors.asStateFlow()

    private val _warnings = MutableStateFlow<List<LogEntry>>(emptyList())
    val warnings: StateFlow<List<LogEntry>> = _warnings.asStateFlow()

    private val maxLogs = 1000
    private val maxErrors = 200
    private val logHistory = mutableListOf<LogEntry>()
    private val errorHistory = mutableListOf<LogEntry>()

    var onNewLog: ((LogEntry) -> Unit)? = null

    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val throwable: Throwable? = null,
        val threadName: String = Thread.currentThread().name
    ) {
        enum class Level {
            VERBOSE, DEBUG, INFO, WARN, ERROR;

            fun toLogLevel(): LogLevel = when (this) {
                VERBOSE -> LogLevel.VERBOSE
                DEBUG -> LogLevel.DEBUG
                INFO -> LogLevel.INFO
                WARN -> LogLevel.WARN
                ERROR -> LogLevel.ERROR
            }
        }

        fun toFormattedString(): String {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
            val base = "[$time] [$level] [$threadName] $tag: $message"
            return if (throwable != null) {
                "$base\n${throwable.stackTraceToString()}"
            } else {
                base
            }
        }
    }

    /**
     * Log a message at the specified level.
     */
    fun log(level: LogEntry.Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(level, tag, message, System.currentTimeMillis(), throwable)

        synchronized(logHistory) {
            logHistory.add(entry)
            while (logHistory.size > maxLogs) {
                logHistory.removeAt(0)
            }
            _logs.value = logHistory.toList()
        }

        when (level) {
            LogEntry.Level.ERROR -> {
                synchronized(errorHistory) {
                    errorHistory.add(entry)
                    while (errorHistory.size > maxErrors) {
                        errorHistory.removeAt(0)
                    }
                    _errors.value = errorHistory.toList()
                }
            }
            LogEntry.Level.WARN -> {
                _warnings.value = (_warnings.value + entry).takeLast(maxErrors)
            }
            else -> {}
        }

        // Delegate to DiagnosticLogger
        DiagnosticLogger.log(DiagnosticEvent(
            level = level.toLogLevel(),
            component = tag,
            event = "log",
            message = message,
            error = throwable?.message
        ))

        onNewLog?.invoke(entry)
    }

    fun v(tag: String, message: String) = log(LogEntry.Level.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogEntry.Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogEntry.Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogEntry.Level.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogEntry.Level.ERROR, tag, message, throwable)

    fun exception(tag: String, message: String, throwable: Throwable) {
        e(tag, "$message: ${throwable.javaClass.simpleName} - ${throwable.message}", throwable)
    }

    inline fun <T> timed(tag: String, operation: String, block: () -> T): T {
        return DiagnosticLogger.trace(tag, operation) {
            block()
        }
    }

    fun getLogsByLevel(level: LogEntry.Level): List<LogEntry> {
        return logHistory.filter { it.level == level }
    }

    fun getLogsSince(timestamp: Long): List<LogEntry> {
        return logHistory.filter { it.timestamp >= timestamp }
    }

    fun getRecentLogs(count: Int = 50): List<LogEntry> {
        return logHistory.takeLast(count)
    }

    fun exportLogs(includeVerbose: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("=== DroidAntigravity Log Export ===")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total entries: ${logHistory.size}")
        sb.appendLine()

        val filteredLogs = if (includeVerbose) {
            logHistory
        } else {
            logHistory.filter { it.level != LogEntry.Level.VERBOSE }
        }

        filteredLogs.forEach { entry ->
            sb.appendLine(entry.toFormattedString())
        }

        return sb.toString()
    }

    fun exportErrors(): String {
        val sb = StringBuilder()
        sb.appendLine("=== DroidAntigravity Error Report ===")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total errors: ${errorHistory.size}")
        sb.appendLine()

        errorHistory.forEach { entry ->
            sb.appendLine(entry.toFormattedString())
        }

        return sb.toString()
    }

    fun clear() {
        synchronized(logHistory) {
            logHistory.clear()
            errorHistory.clear()
            _logs.value = emptyList()
            _errors.value = emptyList()
            _warnings.value = emptyList()
        }
        DiagnosticLogger.clear()
    }

    fun getStats(): LogStats {
        return LogStats(
            total = logHistory.size,
            verbose = logHistory.count { it.level == LogEntry.Level.VERBOSE },
            debug = logHistory.count { it.level == LogEntry.Level.DEBUG },
            info = logHistory.count { it.level == LogEntry.Level.INFO },
            warn = logHistory.count { it.level == LogEntry.Level.WARN },
            error = errorHistory.size,
            oldestLog = logHistory.firstOrNull()?.timestamp,
            newestLog = logHistory.lastOrNull()?.timestamp
        )
    }
}

data class LogStats(
    val total: Int,
    val verbose: Int,
    val debug: Int,
    val info: Int,
    val warn: Int,
    val error: Int,
    val oldestLog: Long?,
    val newestLog: Long?
) {
    fun summary(): String {
        return "Total: $total | V:$verbose D:$debug I:$info W:$warn E:$error"
    }
}
