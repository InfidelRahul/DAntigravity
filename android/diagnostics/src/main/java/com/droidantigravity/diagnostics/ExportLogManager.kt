package com.droidantigravity.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.diagnostics.DiagnosticEvent
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.DiagnosticSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Production diagnostic bundle exporter and failure snapshot manager.
 *
 * Generates a clean, single ZIP bundle containing all available subsystem logs,
 * sanitized environment details, hardware/system specifications, and metadata.
 */
object ExportLogManager {

    private const val TAG = "Diagnostics.Export"

    /**
     * Builds a comprehensive ZIP support bundle using Context.
     */
    suspend fun exportDiagnosticsZip(
        context: Context,
        operationId: String? = null
    ): File = exportDiagnosticsZip(AppPaths.getInstance(context), operationId, context)

    /**
     * Builds a comprehensive ZIP support bundle using AppPaths directly.
     */
    suspend fun exportDiagnosticsZip(
        paths: AppPaths,
        operationId: String? = null,
        context: Context? = null
    ): File = withContext(Dispatchers.IO) {
        val timestampStr = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val zipFile = File(paths.cacheDir, "DroidAntigravity-Diagnostics-$timestampStr.zip")

        DiagnosticLogger.i(TAG, "export_started", "Generating diagnostic support bundle: ${zipFile.name}", operationId = operationId)

        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zipOut ->
            // 1. App persistent log
            addFileIfPresent(zipOut, paths.diagnosticLogFile, "diagnostics/app.log")

            // 2. Previous rotated app log
            addFileIfPresent(zipOut, paths.diagnosticPreviousLogFile, "diagnostics/app.previous.log")

            // 3. Antigravity CLI combined log
            addFileIfPresent(zipOut, paths.antigravityLogFile, "diagnostics/antigravity.log")

            // 4. Dedicated stdout log
            addFileIfPresent(zipOut, paths.stdoutLogFile, "diagnostics/stdout.log")

            // 5. Dedicated stderr log
            addFileIfPresent(zipOut, paths.stderrLogFile, "diagnostics/stderr.log")

            // 6. Linux runtime log
            addFileIfPresent(zipOut, paths.runtimeLogFile, "diagnostics/runtime.log")

            // 7. WebView log
            addFileIfPresent(zipOut, paths.webviewLogFile, "diagnostics/webview.log")

            // 8. System specifications
            val systemInfo = generateSystemInfo(context, paths)
            addTextEntry(zipOut, "diagnostics/system.txt", systemInfo)

            // 9. Sanitized environment variables
            val envInfo = generateEnvironmentInfo()
            addTextEntry(zipOut, "diagnostics/environment.txt", envInfo)

            // 10. Machine-readable safe metadata
            val metadataJson = generateMetadataJson(context, operationId)
            addTextEntry(zipOut, "diagnostics/metadata.json", metadataJson)
        }

        DiagnosticLogger.i(TAG, "export_completed", "Diagnostic support bundle generated (${zipFile.length()} bytes): ${zipFile.absolutePath}", operationId = operationId)
        zipFile
    }

    /**
     * Creates an automatic local failure snapshot on critical errors.
     */
    fun createFailureSnapshot(
        context: Context?,
        operationId: String,
        error: String,
        details: String? = null
    ): File? {
        val paths = if (context != null) AppPaths.getInstance(context) else AppPaths()
        return createFailureSnapshot(paths, operationId, error, details)
    }

    /**
     * Creates an automatic local failure snapshot on critical errors using AppPaths.
     */
    fun createFailureSnapshot(
        paths: AppPaths,
        operationId: String,
        error: String,
        details: String? = null
    ): File? {
        return try {
            val failureDir = paths.failureSnapshotDir
            val timestamp = DiagnosticEvent.currentIsoTimestamp()

            val snapshotJson = """
                {
                  "operationId": "$operationId",
                  "timestamp": "$timestamp",
                  "error": "${escapeJson(DiagnosticSanitizer.redact(error))}",
                  "details": "${escapeJson(DiagnosticSanitizer.redact(details ?: ""))}"
                }
            """.trimIndent()

            val failureFile = File(failureDir, "failure.json")
            failureFile.writeText(snapshotJson)

            // Copy active logs if present
            if (paths.diagnosticLogFile.exists()) {
                paths.diagnosticLogFile.copyTo(File(failureDir, "app.log"), overwrite = true)
            }
            if (paths.stdoutLogFile.exists()) {
                paths.stdoutLogFile.copyTo(File(failureDir, "stdout.log"), overwrite = true)
            }
            if (paths.stderrLogFile.exists()) {
                paths.stderrLogFile.copyTo(File(failureDir, "stderr.log"), overwrite = true)
            }

            DiagnosticLogger.i(TAG, "failure_snapshot_created", "Saved diagnostic failure snapshot in ${failureDir.absolutePath}", operationId = operationId)
            failureFile
        } catch (e: Exception) {
            DiagnosticLogger.e(TAG, "failure_snapshot_error", "Failed to create failure snapshot: ${e.message}", e, operationId = operationId)
            null
        }
    }

    private fun addFileIfPresent(zipOut: ZipOutputStream, file: File, entryName: String) {
        if (file.exists() && file.length() > 0) {
            val entry = ZipEntry(entryName)
            zipOut.putNextEntry(entry)
            FileInputStream(file).use { input ->
                input.copyTo(zipOut)
            }
            zipOut.closeEntry()
        }
    }

    private fun addTextEntry(zipOut: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zipOut.putNextEntry(entry)
        zipOut.write(content.toByteArray(StandardCharsets.UTF_8))
        zipOut.closeEntry()
    }

    private fun generateSystemInfo(context: Context?, paths: AppPaths): String {
        val sb = StringBuilder()
        sb.appendLine("=== DroidAntigravity System Diagnostics ===")
        sb.appendLine("Timestamp: ${DiagnosticEvent.currentIsoTimestamp()}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER ?: "Android"}")
        sb.appendLine("Model: ${Build.MODEL ?: "Generic Device"}")
        sb.appendLine("Device: ${Build.DEVICE ?: "generic"}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE ?: "14"} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS?.joinToString(", ") ?: "arm64-v8a"}")
        sb.appendLine("CPU Cores: ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("Max JVM Memory: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
        val freeSpaceMb = runCatching { context?.filesDir?.freeSpace?.div(1024 * 1024) }.getOrNull() ?: 1024L
        sb.appendLine("Internal Storage Free: $freeSpaceMb MB")
        sb.appendLine("Rootfs Directory: ${paths.rootfsDir.absolutePath}")
        sb.appendLine("Rootfs Installed: ${paths.rootfsInstallMarker.exists()}")
        sb.appendLine("Antigravity Binary Present: ${paths.hostAntigravityBin.exists()}")
        sb.appendLine("Native Library Dir: ${paths.nativeLibDir.absolutePath}")
        return sb.toString()
    }

    private fun generateEnvironmentInfo(): String {
        val env = DiagnosticSanitizer.sanitizeEnvironment(System.getenv())
        val sb = StringBuilder()
        sb.appendLine("=== Safe Environment Variables ===")
        env.toSortedMap().forEach { (k, v) ->
            sb.appendLine("$k=$v")
        }
        return sb.toString()
    }

    private fun generateMetadataJson(context: Context?, operationId: String?): String {
        val isDebug = runCatching {
            context?.let { (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: true
        }.getOrDefault(true)

        val appVersion = runCatching {
            context?.let {
                val pInfo = it.packageManager.getPackageInfo(it.packageName, 0)
                pInfo.versionName
            } ?: "1.0.0"
        }.getOrDefault("1.0.0")

        return """
            {
              "appVersion": "$appVersion",
              "buildType": "${if (isDebug) "debug" else "release"}",
              "deviceArchitecture": "arm64-v8a",
              "androidVersion": "${Build.VERSION.RELEASE ?: "14"}",
              "sdkVersion": ${Build.VERSION.SDK_INT},
              "deviceModel": "${escapeJson(Build.MODEL ?: "Generic Device")}",
              "deviceManufacturer": "${escapeJson(Build.MANUFACTURER ?: "Android")}",
              "kernelVersion": "${escapeJson(System.getProperty("os.version") ?: "unknown")}",
              "timestamp": "${DiagnosticEvent.currentIsoTimestamp()}",
              "operationId": "${operationId ?: "N/A"}",
              "antigravityCliVersion": "1.2.7",
              "runtime": "PRoot"
            }
        """.trimIndent()
    }

    private fun escapeJson(str: String?): String {
        return (str ?: "").replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
