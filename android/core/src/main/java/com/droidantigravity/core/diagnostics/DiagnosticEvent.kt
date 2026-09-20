package com.droidantigravity.core.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Diagnostic log level.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        fun fromString(value: String): LogLevel {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
        }
    }
}

/**
 * Structured diagnostic event recorded across all DroidAntigravity subsystems.
 */
data class DiagnosticEvent(
    val timestamp: String = currentIsoTimestamp(),
    val timestampMs: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val component: String,
    val event: String,
    val message: String,
    val operationId: String? = null,
    val processId: Int? = null,
    val state: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val exitCode: Int? = null,
    val stream: String? = null,
    val threadName: String = Thread.currentThread().name
) {
    companion object {
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        fun currentIsoTimestamp(timeMs: Long = System.currentTimeMillis()): String {
            synchronized(ISO_FORMAT) {
                return ISO_FORMAT.format(Date(timeMs))
            }
        }

        fun formatTime(timeMs: Long): String {
            synchronized(TIME_FORMAT) {
                return TIME_FORMAT.format(Date(timeMs))
            }
        }

        /**
         * Parses a JSON Lines record into a DiagnosticEvent.
         */
        fun fromJson(json: String): DiagnosticEvent? {
            return try {
                fun extractField(fieldName: String): String? {
                    val pattern = Regex(""""$fieldName"\s*:\s*("([^"\\]*(\\.[^"\\]*)*)"|([0-9]+|null|true|false))""")
                    val match = pattern.find(json) ?: return null
                    val stringVal = match.groups[2]?.value
                    if (stringVal != null) {
                        return unescapeJson(stringVal)
                    }
                    val rawVal = match.groups[4]?.value
                    return if (rawVal == "null") null else rawVal
                }

                val timestamp = extractField("timestamp") ?: currentIsoTimestamp()
                val levelStr = extractField("level") ?: "INFO"
                val component = extractField("component") ?: "App"
                val event = extractField("event") ?: "log"
                val message = extractField("message") ?: ""
                val operationId = extractField("operationId")
                val processId = extractField("processId")?.toIntOrNull()
                val state = extractField("state")
                val durationMs = extractField("durationMs")?.toLongOrNull()
                val error = extractField("error")
                val exitCode = extractField("exitCode")?.toIntOrNull()
                val stream = extractField("stream")
                val threadName = extractField("threadName") ?: "main"

                DiagnosticEvent(
                    timestamp = timestamp,
                    level = LogLevel.fromString(levelStr),
                    component = component,
                    event = event,
                    message = message,
                    operationId = operationId,
                    processId = processId,
                    state = state,
                    durationMs = durationMs,
                    error = error,
                    exitCode = exitCode,
                    stream = stream,
                    threadName = threadName
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun unescapeJson(str: String): String {
            return str.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }

    /**
     * Serializes this event into a machine-readable JSON Line.
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"timestamp\":\"").append(escapeJson(timestamp)).append("\",")
        sb.append("\"level\":\"").append(level.name).append("\",")
        sb.append("\"component\":\"").append(escapeJson(component)).append("\",")
        sb.append("\"event\":\"").append(escapeJson(event)).append("\",")
        sb.append("\"message\":\"").append(escapeJson(message)).append("\"")

        if (operationId != null) {
            sb.append(",\"operationId\":\"").append(escapeJson(operationId)).append("\"")
        }
        if (processId != null) {
            sb.append(",\"processId\":").append(processId)
        }
        if (state != null) {
            sb.append(",\"state\":\"").append(escapeJson(state)).append("\"")
        }
        if (durationMs != null) {
            sb.append(",\"durationMs\":").append(durationMs)
        }
        if (error != null) {
            sb.append(",\"error\":\"").append(escapeJson(error)).append("\"")
        }
        if (exitCode != null) {
            sb.append(",\"exitCode\":").append(exitCode)
        }
        if (stream != null) {
            sb.append(",\"stream\":\"").append(escapeJson(stream)).append("\"")
        }
        sb.append(",\"threadName\":\"").append(escapeJson(threadName)).append("\"")
        sb.append("}")
        return sb.toString()
    }

    /**
     * Human-readable formatted string for UI viewing.
     */
    fun toFormattedString(): String {
        val time = formatTime(timestampMs)
        val opTag = if (operationId != null) " [$operationId]" else ""
        val pidTag = if (processId != null) " (pid=$processId)" else ""
        val streamTag = if (stream != null) " [$stream]" else ""
        val durTag = if (durationMs != null) " in ${durationMs}ms" else ""
        val errTag = if (error != null) " [error=$error]" else ""
        val exitTag = if (exitCode != null) " [exit=$exitCode]" else ""

        return "[$time] [$level] [$component]$opTag$pidTag$streamTag $event: $message$durTag$errTag$exitTag"
    }

    private fun escapeJson(str: String): String {
        val out = StringBuilder()
        for (ch in str) {
            when (ch) {
                '\"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }
}
