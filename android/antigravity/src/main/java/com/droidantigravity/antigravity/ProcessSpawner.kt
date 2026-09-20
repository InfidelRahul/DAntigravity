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
     * Sends a signal to the process or process group.
     */
    fun kill(pid: Int, signal: Int): Int

    /**
     * Closes an open file descriptor.
     */
    fun close(fd: Int): Int
}

/**
 * Default implementation delegating directly to NativeSpawn JNI calls.
 */
class NativeProcessSpawner : ProcessSpawner {
    override fun spawnPty(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String,
        cols: Int,
        rows: Int
    ): IntArray? = NativeSpawn.spawnPty(argv, envp, cwd, outputPath, cols, rows)

    override fun write(fd: Int, data: ByteArray): Int = NativeSpawn.write(fd, data)

    override fun waitFor(pid: Int, noHang: Boolean): Int = NativeSpawn.waitFor(pid, noHang)

    override fun kill(pid: Int, signal: Int): Int = NativeSpawn.kill(pid, signal)

    override fun close(fd: Int): Int = NativeSpawn.close(fd)
}
