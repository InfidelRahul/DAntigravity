package com.droidantigravity.terminal

import com.droidantigravity.core.AvsLogger
import com.droidantigravity.runtime.NativeSpawn
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.os.Looper
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Linux terminal session.
 *
 * Android owns only the PTY transport and terminal emulator. The Linux login
 * shell owns cwd, history, prompt, environment, aliases, parsing and job
 * control. No command is executed through Android one command at a time.
 */
class PtyTerminalSession(
    private val linuxRuntime: PRootRuntime,
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS
) {
    companion object {
        private const val TAG = "PtyTerminalSession"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        private const val READ_BUFFER_SIZE = 32 * 1024

        const val KEY_ESC = "\u001b"
        const val KEY_TAB = "\t"
        const val KEY_UP = "\u001b[A"
        const val KEY_DOWN = "\u001b[B"
        const val KEY_LEFT = "\u001b[D"
        const val KEY_RIGHT = "\u001b[C"
        const val KEY_CTRL_C = "\u0003"
        const val KEY_CTRL_D = "\u0004"
        const val KEY_CTRL_Z = "\u001a"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)

    @Volatile private var pid: Int = -1
    @Volatile private var masterFd: Int = -1
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    /** libvterm-backed terminal emulator. */
    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = rows,
        initialCols = cols,
        onKeyboardInput = { bytes -> write(bytes) },
        onResize = { dimensions ->
            resize(dimensions.columns, dimensions.rows)
        }
    )

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return true
        return try {
            val result = linuxRuntime.spawnInteractiveShell(cols, rows)
                ?: throw IllegalStateException("Unable to create Linux PTY")
            pid = result[0]
            masterFd = result.getOrNull(1) ?: -1
            if (pid <= 0 || masterFd < 0) {
                throw IllegalStateException("Linux PTY returned invalid pid/fd")
            }

            writerJob = scope.launch {
                for (bytes in inputQueue) {
                    writeNow(bytes)
                }
            }
            readerJob = scope.launch {
                readLoop()
            }
            true
        } catch (t: Throwable) {
            started.set(false)
            AvsLogger.e(TAG, "Failed to start PTY terminal: ${t.message}", t)
            false
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (isActive && masterFd >= 0) {
                val count = NativeSpawn.read(masterFd, buffer)
                when {
                    count > 0 -> {
                        // Copy only the bytes returned by the native read. The emulator
                        // is thread-safe; never invoke UI callbacks from this IO thread.
                        emulator.writeInput(buffer, 0, count)
                    }
                    count == 0 -> delay(2)
                    count == -4 -> continue // EINTR
                    else -> break
                }
            }
        } catch (_: CancellationException) {
            // Normal session shutdown.
        } catch (t: Throwable) {
            AvsLogger.e(TAG, "PTY read loop failed: ${t.message}", t)
        } finally {
            started.set(false)
        }
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /** Use the emulator's bracketed-paste aware path when supported by the shell/app. */
    fun paste(text: String) {
        if (text.isNotEmpty()) emulator.pasteText(text)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        inputQueue.trySend(bytes.copyOf())
    }

    private fun writeNow(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size && masterFd >= 0) {
            val count = NativeSpawn.write(masterFd, bytes.copyOfRange(offset, bytes.size))
            if (count <= 0) break
            offset += count
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (masterFd >= 0) {
            NativeSpawn.resizePty(masterFd, newCols.coerceAtLeast(4), newRows.coerceAtLeast(4))
        }
    }

    fun sendControl(sequence: String) = write(sequence)

    fun isRunning(): Boolean = started.get() && pid > 0 && masterFd >= 0

    fun close() {
        if (!started.compareAndSet(true, false)) return
        readerJob?.cancel()
        writerJob?.cancel()
        readerJob = null
        writerJob = null
        inputQueue.close()

        val currentPid = pid
        val currentFd = masterFd
        pid = -1
        masterFd = -1

        if (currentPid > 0) {
            NativeSpawn.kill(currentPid, 15)
            NativeSpawn.kill(currentPid, 9)
        }
        if (currentFd >= 0) NativeSpawn.close(currentFd)
        scope.coroutineContext.cancel()
    }
}
