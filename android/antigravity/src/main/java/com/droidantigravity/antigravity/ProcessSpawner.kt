package com.droidantigravity.antigravity

import com.droidantigravity.runtime.NativeSpawn

/**
 * Abstraction for process spawning and I/O management.
 * Facilitates unit testing and decouples process lifecycle from direct native JNI invocations.
 */
interface ProcessSpawner {
    /**
     * Spawns an interactive process inside a pseudo-terminal (PTY).
     *
     * @return IntArray of [pid, masterPtyFd] or null on failure.
     */
    fun spawnPty(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String,
        cols: Int = 80,
        rows: Int = 24
    ): IntArray?

    /**
     * Spawns an interactive process inside a PTY with separate stdout and stderr destination files.
     */
    fun spawnPtyWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?,
        cols: Int = 80,
        rows: Int = 24,
        operationId: String? = null
    ): IntArray? = spawnPty(argv, envp, cwd, stdoutPath, cols, rows)

    /**
     * Spawns a standard non-interactive process with separate streams.
     */
    fun spawnWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?,
        operationId: String? = null
    ): IntArray? = null

    /**
     * Writes raw bytes to a file descriptor.
     */
    fun write(fd: Int, data: ByteArray): Int

    /**
     * Writes a UTF-8 string to a file descriptor.
     */
    fun writeString(fd: Int, str: String): Boolean {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return write(fd, bytes) == bytes.size
    }

    /**
     * Checks or waits for process termination.
     *
     * @return exit status, or -2 if still running.
     */
    fun waitFor(pid: Int, noHang: Boolean): Int

    /**
     * Checks or waits for process termination with operation tracing.
     */
    fun waitFor(pid: Int, noHang: Boolean, operationId: String?): Int = waitFor(pid, noHang)

    /**
     * Sends a signal to the process or process group.
     */
    fun kill(pid: Int, signal: Int): Int

    /**
     * Sends a signal to the process with operation tracing.
     */
    fun kill(pid: Int, signal: Int, operationId: String?): Int = kill(pid, signal)

    /**
     * Closes an open file descriptor.
     */
    fun close(fd: Int): Int

    /**
     * Closes an open file descriptor with operation tracing.
     */
    fun close(fd: Int, operationId: String?): Int = close(fd)
}

/**
 * Default implementation delegating directly to NativeSpawn JNI calls with diagnostics.
 */
class NativeProcessSpawner : ProcessSpawner {
    override fun spawnPty(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String,
        cols: Int,
        rows: Int
    ): IntArray? = NativeSpawn.spawnPtyInstrumented(argv, envp, cwd, outputPath, null, cols, rows)

    override fun spawnPtyWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?,
        cols: Int,
        rows: Int,
        operationId: String?
    ): IntArray? = NativeSpawn.spawnPtyInstrumented(argv, envp, cwd, stdoutPath, stderrPath, cols, rows, operationId)

    override fun spawnWithStreams(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String?,
        operationId: String?
    ): IntArray? = NativeSpawn.spawnInstrumented(argv, envp, cwd, stdoutPath, stderrPath, operationId)

    override fun write(fd: Int, data: ByteArray): Int = NativeSpawn.write(fd, data)

    override fun waitFor(pid: Int, noHang: Boolean): Int = NativeSpawn.waitForInstrumented(pid, noHang)

    override fun waitFor(pid: Int, noHang: Boolean, operationId: String?): Int =
        NativeSpawn.waitForInstrumented(pid, noHang, operationId)

    override fun kill(pid: Int, signal: Int): Int = NativeSpawn.killInstrumented(pid, signal)

    override fun kill(pid: Int, signal: Int, operationId: String?): Int =
        NativeSpawn.killInstrumented(pid, signal, operationId)

    override fun close(fd: Int): Int = NativeSpawn.closeInstrumented(fd)

    override fun close(fd: Int, operationId: String?): Int =
        NativeSpawn.closeInstrumented(fd, operationId)
}
