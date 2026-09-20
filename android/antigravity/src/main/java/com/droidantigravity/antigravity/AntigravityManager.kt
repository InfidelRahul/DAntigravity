package com.droidantigravity.antigravity

import android.content.Context
import com.droidantigravity.core.AntigravityState
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.Result
import com.droidantigravity.core.runCatchingResult
import com.droidantigravity.runtime.NativeSpawn
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the real Antigravity CLI process inside the Linux userspace.
 *
 * DroidAntigravity does not implement an Antigravity HTTP server. The official
 * CLI establishes the Remote Control reverse tunnel and prints the URL that
 * the Android WebView consumes.
 */
class AntigravityManager(
    private val context: Context,
    private val linuxRuntime: PRootRuntime
) {
    companion object {
        private const val TAG = "AntigravityManager"
        private const val REMOTE_CONTROL_HOST = "antigravity.google.com"
        private val REMOTE_URL = Regex(
            """https://antigravity\.google\.com/r/[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )
    }

    private val paths = AppPaths.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _state = AtomicReference(AntigravityState.UNKNOWN)

    @Volatile private var processPid: Int? = null
    @Volatile private var stdinFd: Int? = null
    @Volatile private var remoteControlUrl: String? = null
    @Volatile private var processLog: File? = null
    private var monitorJob: Job? = null

    val state: AntigravityState get() = _state.get()

    fun isInstalled(): Boolean {
        val installed = try {
            val result = kotlinx.coroutines.runBlocking {
                linuxRuntime.execute("command -v agy 2>/dev/null || true")
            }
    
            result.getOrNull()?.trim()?.isNotEmpty() == true
        } catch (e: Exception) {
            AvsLogger.e(TAG, "Failed to check Antigravity installation: ${e.message}")
            false
        }
    
        if (!installed && state != AntigravityState.RUNNING) {
            _state.set(AntigravityState.NOT_INSTALLED)
        }
    
        return installed
    }

    suspend fun version(): Result<String> = withContext(Dispatchers.IO) {
        linuxRuntime.execute("agy --version")
    }

    /**
     * Installs the official Linux CLI using Google's published installer.
     *
     * The installer is executed inside the persistent Linux HOME. No host-side
     * executable is fabricated.
     */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        _state.set(AntigravityState.STARTING)
        runCatchingResult {
            val command = """
                set -e
                export PATH="/home/user/.local/bin:${'$'}PATH"
                command -v curl >/dev/null 2>&1 || {
                    apt-get update
                    apt-get install -y --no-install-recommends ca-certificates curl git
                }
                mkdir -p /home/user/.local/bin
                tmp="/tmp/antigravity-cli-install.sh"
                curl -fsSL https://antigravity.google/cli/install.sh -o "${'$'}tmp"
                chmod 700 "${'$'}tmp"
                bash "${'$'}tmp"
                rm -f "${'$'}tmp"
                export PATH="/home/user/.local/bin:${'$'}PATH"
                command -v agy >/dev/null
                agy --version
            """.trimIndent()

            val result = linuxRuntime.executeStreaming(command) { output ->
                AvsLogger.d(TAG, redact(output))
            }.getOrThrow()

            if (result != 0) {
                throw IllegalStateException("Antigravity installer exited with code $result")
            }

            if (!isInstalled()) {
                throw IllegalStateException("Official installer completed but 'agy' is not on PATH")
            }

            _state.set(AntigravityState.STOPPED)
        }.also {
            if (it.isFailure) _state.set(AntigravityState.FAILED)
        }
    }

    suspend fun ensureInstalled(): Result<Unit> {
        if (isInstalled()) {
            _state.set(AntigravityState.STOPPED)
            return Result.Success(Unit)
        }
        return install()
    }

    /**
     * Starts one interactive CLI session with Remote Control enabled.
     *
     * The URL is session-scoped and must never be treated as persistent project
     * or conversation identity.
     */
    suspend fun start(): Result<String> = withContext(Dispatchers.IO) {
        if (state == AntigravityState.RUNNING && processPid != null && remoteControlUrl != null) {
            return@withContext Result.Success(remoteControlUrl!!)
        }

        ensureInstalled().getOrThrow()
        stopInternal()

        _state.set(AntigravityState.STARTING)
        remoteControlUrl = null

        val log = paths.antigravityLogFile
        log.parentFile?.mkdirs()
        log.writeText("")
        processLog = log

        val args = linuxRuntime.buildPRootArgs("exec agy --remote-control", paths.guestHomePath)
        val env = linuxRuntime.buildEnvironment(
            paths.guestHomePath,
            mapOf(
                "PATH" to "/home/user/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "AGY_CLI_HIDE_LOGO" to "1"
            )
        )

        runCatchingResult {
            val spawned = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                log.absolutePath
            ) ?: error("Unable to spawn agy")

            processPid = spawned[0]
            stdinFd = spawned.getOrNull(1)?.takeIf { it >= 0 }
            _state.set(AntigravityState.RUNNING)

            monitorJob?.cancel()
            monitorJob = scope.launch {
                monitorProcess(spawned[0], log)
            }

            awaitRemoteControlUrl(log)
                ?: throw IllegalStateException(
                    "agy started but did not publish an Antigravity Remote Control URL"
                )
        }.also { result ->
            if (result.isFailure) {
                _state.set(AntigravityState.FAILED)
                stopInternal()
            }
        }
    }

    fun currentRemoteControlUrl(): String? = remoteControlUrl

    fun processId(): Int? = processPid

    fun stop() {
        stopInternal()
    }

    private suspend fun awaitRemoteControlUrl(log: File): String? {
        val deadline = System.currentTimeMillis() + 60_000
        var lastLength = 0L

        while (System.currentTimeMillis() < deadline) {
            if (!log.exists()) return null

            val text = log.readText()
            val match = REMOTE_URL.find(text)
            if (match != null && match.value.contains(REMOTE_CONTROL_HOST)) {
                val url = match.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
                remoteControlUrl = url
                AvsLogger.i(TAG, "Remote Control URL received")
                return url
            }

            if (log.length() < lastLength) lastLength = 0
            lastLength = log.length()

            val pid = processPid
            if (pid == null || NativeSpawn.waitFor(pid, true) != -2) return null
            delay(100)
        }

        return null
    }

    private suspend fun monitorProcess(pid: Int, log: File) {
        while (currentCoroutineContext().isActive) {
            val status = NativeSpawn.waitFor(pid, true)
            if (status != -2) {
                if (processPid == pid) {
                    processPid = null
                    stdinFd?.let { NativeSpawn.close(it) }
                    stdinFd = null
                    remoteControlUrl = null
                    _state.set(
                        if (isInstalled()) {
                            AntigravityState.STOPPED
                        } else {
                            AntigravityState.NOT_INSTALLED
                        }
                    )
                    AvsLogger.i(TAG, "agy exited with status $status")
                }
                return
            }
            delay(250)
        }
    }

    private fun stopInternal() {
        monitorJob?.cancel()
        monitorJob = null

        val pid = processPid
        if (pid != null) {
            NativeSpawn.kill(pid, 15)
            Thread.sleep(250)
            NativeSpawn.kill(pid, 9)
            NativeSpawn.waitFor(pid, true)
        }

        stdinFd?.let { NativeSpawn.close(it) }
        stdinFd = null
        processPid = null
        remoteControlUrl = null

        if (isInstalled()) _state.set(AntigravityState.STOPPED)
        else _state.set(AntigravityState.NOT_INSTALLED)
    }

    private fun redact(text: String): String {
        return text
            .replace(Regex("""https://antigravity\.google\.com/r/\S+"""), "<remote-control-url>")
            .replace(Regex("""(?i)(token|api[_-]?key|authorization|cookie)=?\S+"""), "$1=<redacted>")
    }
}
