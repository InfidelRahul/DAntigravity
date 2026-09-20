package com.droidantigravity

import android.content.Context
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.diagnostics.ExportLogManager

/**
 * Uncaught exception handler and central application-level error recorder.
 * Captures crash stack traces, updates the diagnostic trail, creates a local failure
 * snapshot, and chains to the system or default handler.
 */
class GlobalErrorRecorder(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GlobalErrorRecorder"

        fun install(context: Context) {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing !is GlobalErrorRecorder) {
                Thread.setDefaultUncaughtExceptionHandler(GlobalErrorRecorder(context.applicationContext, existing))
                DiagnosticLogger.d(TAG, "installed", "Global uncaught exception handler registered")
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val opId = DiagnosticLogger.createOperationId("CRASH")
            DiagnosticLogger.recordError(
                throwable = throwable,
                component = "CrashReport",
                operationId = opId,
                appState = "UNCAUGHT_EXCEPTION",
                message = "Fatal uncaught exception on thread '${thread.name}': ${throwable.message}"
            )

            // Create automatic failure snapshot
            ExportLogManager.createFailureSnapshot(
                context = context,
                operationId = opId,
                error = "Fatal crash: ${throwable.javaClass.simpleName} - ${throwable.message}",
                details = throwable.stackTraceToString()
            )
        } catch (e: Throwable) {
            // Failsafe: never prevent propagation of crash
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
