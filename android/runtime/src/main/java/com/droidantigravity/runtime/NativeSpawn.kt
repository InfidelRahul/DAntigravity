package com.droidantigravity.runtime

import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.DiagnosticSanitizer
import com.droidantigravity.core.diagnostics.LogLevel

/**
 * Native process spawner integrating with LinuxDroid PRoot process management.
 * Provides process group isolation (setpgid), standard I/O redirection,
 * pseudo-terminal (PTY) support, separate stdout/stderr capture, and clean termination.
 * Fully instrumented with [DiagnosticLogger].
 */
object NativeSpawn {
    private const val TAG = "NativeSpawn"

    init {
        try {
            System.loadLibrary("droidantigravityspawn")
            DiagnosticLogger.d(TAG, "library_loaded", "Loaded native process spawner library")
        } catch (e: UnsatisfiedLinkError) {
            DiagnosticLogger.e(TAG, "library_load_failed", "Failed to load native process spawner", e)
        }
    }

    external fun spawn(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String
    ): IntArray?

    external fun spawnWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?
    ): IntArray?

    external fun spawnPty(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String,
        cols: Int = 80,
        rows: Int = 24
    ): IntArray?

    external fun spawnPtyWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?,
        cols: Int = 80,
        rows: Int = 24
    ): IntArray?

    /**
     * Spawn a true interactive PTY. The returned array is [pid, masterFd].
     * The caller owns the master fd and is responsible for reading/writing it.
     */
    external fun spawnPtyInteractive(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        cols: Int = 80,
        rows: Int = 24
    ): IntArray?

    external fun write(fd: Int, data: ByteArray): Int

    /** Reads up to data.size bytes from a PTY/file descriptor. */
    external fun read(fd: Int, data: ByteArray): Int

    /** Updates the PTY window size. */
    external fun resizePty(fd: Int, cols: Int, rows: Int): Int

    fun writeString(fd: Int, str: String): Boolean {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return write(fd, bytes) == bytes.size
    }

    external fun waitFor(pid: Int, noHang: Boolean): Int

    external fun kill(pid: Int, signal: Int): Int

    external fun close(fd: Int): Int

    // Instrumented wrappers for diagnostics

    fun spawnInstrumented(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String? = null,
        operationId: String? = null
    ): IntArray? {
        val envSummary = summarizeEnvironment(envp)
        val cmdStr = argv.joinToString(" ")

        DiagnosticLogger.d(
            TAG,
            "spawn_requested",
            "Spawning process: cmd=[$cmdStr], cwd=[$cwd], stdout=[$stdoutPath], stderr=[$stderrPath], env=[$envSummary]",
            operationId = operationId
        )

        val result = if (stderrPath != null) {
            spawnWithStreams(argv, envp, cwd, stdoutPath, stderrPath)
        } else {
            spawn(argv, envp, cwd, stdoutPath)
        }

        if (result != null && result.isNotEmpty()) {
            val pid = result[0]
            val stdinFd = result.getOrNull(1) ?: -1
            DiagnosticLogger.i(
                TAG,
                "spawn_success",
                "Process spawned successfully: pid=$pid, stdinFd=$stdinFd",
                operationId = operationId,
                processId = pid
            )
        } else {
            DiagnosticLogger.e(
                TAG,
                "spawn_failed",
                "Process spawn failed for command: $cmdStr",
                operationId = operationId
            )
        }
        return result
    }

    fun spawnPtyInstrumented(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String? = null,
        cols: Int = 80,
        rows: Int = 24,
        operationId: String? = null
    ): IntArray? {
        val envSummary = summarizeEnvironment(envp)
        val cmdStr = argv.joinToString(" ")

        DiagnosticLogger.d(
            TAG,
            "spawn_pty_requested",
            "Spawning PTY process: cmd=[$cmdStr], cwd=[$cwd], stdout=[$stdoutPath], stderr=[$stderrPath], size=${cols}x$rows, env=[$envSummary]",
            operationId = operationId
        )

        val result = if (stderrPath != null) {
            spawnPtyWithStreams(argv, envp, cwd, stdoutPath, stderrPath, cols, rows)
        } else {
            spawnPty(argv, envp, cwd, stdoutPath, cols, rows)
        }

        if (result != null && result.isNotEmpty()) {
            val pid = result[0]
            val masterFd = result.getOrNull(1) ?: -1
            DiagnosticLogger.i(
                TAG,
                "spawn_pty_success",
                "PTY process spawned successfully: pid=$pid, masterPtyFd=$masterFd",
                operationId = operationId,
                processId = pid
            )
        } else {
            DiagnosticLogger.e(
                TAG,
                "spawn_pty_failed",
                "PTY process spawn failed for command: $cmdStr",
                operationId = operationId
            )
        }
        return result
    }

    fun waitForInstrumented(pid: Int, noHang: Boolean, operationId: String? = null): Int {
        val status = waitFor(pid, noHang)
        if (status != -2) {
            // Process terminated or error
            DiagnosticLogger.i(
                TAG,
                "wait_exit",
                "Process wait returned status $status (noHang=$noHang)",
                operationId = operationId,
                processId = pid
            )
        }
        return status
    }

    fun killInstrumented(pid: Int, signal: Int, operationId: String? = null): Int {
        val result = kill(pid, signal)
        DiagnosticLogger.i(
            TAG,
            "kill_signal",
            "Sent signal $signal to pid $pid, result=$result",
            operationId = operationId,
            processId = pid
        )
        return result
    }

    fun closeInstrumented(fd: Int, operationId: String? = null): Int {
        val result = close(fd)
        DiagnosticLogger.d(
            TAG,
            "close_fd",
            "Closed file descriptor $fd, result=$result",
            operationId = operationId
        )
        return result
    }

    private fun summarizeEnvironment(envp: Array<String>): String {
        val map = envp.mapNotNull {
            val idx = it.indexOf('=')
            if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
        }.toMap()
        val sanitized = DiagnosticSanitizer.sanitizeEnvironment(map)
        return sanitized.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
