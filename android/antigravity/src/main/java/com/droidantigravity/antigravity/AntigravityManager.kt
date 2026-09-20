package com.droidantigravity.antigravity

import android.content.Context
import com.droidantigravity.core.AntigravityState
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.Result
import com.droidantigravity.core.runCatchingResult
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
class AntigravityManager internal constructor(
    private val context: Context?,
    private val linuxRuntime: PRootRuntime,
    private val spawner: ProcessSpawner = NativeProcessSpawner(),
    private val paths: AppPaths = context?.let { AppPaths.getInstance(it) } ?: AppPaths()
) {
    constructor(context: Context, linuxRuntime: PRootRuntime) : this(context, linuxRuntime, NativeProcessSpawner())

    constructor(
        linuxRuntime: PRootRuntime,
        spawner: ProcessSpawner,
        paths: AppPaths
    ) : this(null, linuxRuntime, spawner, paths)

    companion object {
        private const val TAG = "AntigravityManager"
        const val DEFAULT_STARTUP_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _state = AtomicReference(AntigravityState.UNKNOWN)

    @Volatile private var processPid: Int? = null
    @Volatile private var stdinFd: Int? = null
    @Volatile private var remoteControlUrl: String? = null
    @Volatile private var processLog: File? = null
    private var monitorJob: Job? = null

    val state: AntigravityState get() = _state.get()

    fun isInstalled(): Boolean {
        if (!paths.rootfsInstallMarker.exists()) {
            if (state != AntigravityState.RUNNING && state != AntigravityState.AUTHENTICATION_REQUIRED) {
                _state.set(AntigravityState.NOT_INSTALLED)
            }
            return false
        }

        if (paths.hostAntigravityBin.exists() ||
            File(paths.rootfsDir, "usr/local/bin/agy").exists() ||
            File(paths.rootfsDir, "usr/bin/agy").exists() ||
            File(paths.rootfsDir, "bin/agy").exists()
        ) {
            return true
        }

        val installed = try {
            val result = kotlinx.coroutines.runBlocking {
                linuxRuntime.execute("command -v agy 2>/dev/null || true")
            }

            result.getOrNull()?.trim()?.isNotEmpty() == true
        } catch (e: Exception) {
            AvsLogger.e(TAG, "Failed to check Antigravity installation: ${e.message}")
            false
        }

        if (!installed && state != AntigravityState.RUNNING && state != AntigravityState.AUTHENTICATION_REQUIRED) {
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
                AvsLogger.d(TAG, AntigravityLogRedactor.redact(output))
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
            if (_state.get() == AntigravityState.UNKNOWN || _state.get() == AntigravityState.NOT_INSTALLED) {
                _state.set(AntigravityState.STOPPED)
            }
            return Result.Success(Unit)
        }
        return install()
    }

    /**
     * Pre-populates trusted workspaces in ~/.gemini/antigravity-cli/settings.json
     * so the official CLI never blocks indefinitely on the interactive trust prompt.
     */
    internal fun ensureWorkspaceTrusted() {
        try {
            val cliDataDir = File(paths.hostAntigravityDataDir, "antigravity-cli")
            cliDataDir.mkdirs()
            val settingsFile = File(cliDataDir, "settings.json")
            val defaultWorkspaces = listOf(paths.guestHomePath, paths.guestProjectsPath)

            val currentWorkspaces = if (settingsFile.exists()) {
                val text = settingsFile.readText()
                val match = Regex(""""trustedWorkspaces"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(text)
                val existing = match?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"', '\'', ' ', '\t', '\r', '\n') }
                    ?.filter { it.isNotEmpty() } ?: emptyList()
                (existing + defaultWorkspaces).distinct()
            } else {
                defaultWorkspaces
            }

            val jsonArray = currentWorkspaces.joinToString(",\n    ") { "\"$it\"" }
            val json = "{\n  \"trustedWorkspaces\": [\n    $jsonArray\n  ]\n}\n"
            settingsFile.writeText(json)
            AvsLogger.d(TAG, "Configured trusted workspaces in settings.json")
        } catch (e: Exception) {
            AvsLogger.w(TAG, "Failed to pre-configure trusted workspaces: ${e.message}")
        }
    }

    /**
     * Starts one interactive CLI session with Remote Control enabled.
     *
     * The URL is session-scoped and must never be treated as persistent project
     * or conversation identity.
     */
    suspend fun start(): Result<String> = start(DEFAULT_STARTUP_TIMEOUT_MS)

    suspend fun start(startupTimeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        if (state == AntigravityState.RUNNING && processPid != null && remoteControlUrl != null) {
            val check = spawner.waitFor(processPid!!, true)
            if (check == -2) {
                return@withContext Result.Success(remoteControlUrl!!)
            }
        }

        if (!isInstalled()) {
            val installResult = install()
            if (installResult.isFailure) {
                val ex = AntigravityStartupException(
                    AntigravityStartupError.NOT_INSTALLED,
                    "Antigravity CLI is not installed and auto-installation failed: ${installResult.exceptionOrNull()?.message}",
                    cause = installResult.exceptionOrNull()
                )
                _state.set(AntigravityState.FAILED)
                return@withContext Result.Failure(ex, ex.message)
            }
        }
        stopInternal(preserveState = false)

        _state.set(AntigravityState.STARTING)
        remoteControlUrl = null

        // Ensure workspace is pre-trusted so agy doesn't block waiting for confirmation
        ensureWorkspaceTrusted()

        val log = paths.antigravityLogFile
        log.parentFile?.mkdirs()
        log.writeText("")
        processLog = log

        val args = linuxRuntime.buildPRootArgs("exec agy --remote-control", paths.guestHomePath)
        val env = linuxRuntime.buildEnvironment(
            paths.guestHomePath,
            mapOf(
                "PATH" to "/home/user/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "AGY_CLI_HIDE_LOGO" to "1",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor"
            )
        )

        try {
            val spawned = spawner.spawnPty(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                log.absolutePath,
                cols = 80,
                rows = 24
            ) ?: throw AntigravityStartupException(
                AntigravityStartupError.START_FAILED,
                "Unable to spawn agy process using PTY"
            )

            val pid = spawned[0]
            processPid = pid
            stdinFd = spawned.getOrNull(1)?.takeIf { it >= 0 }

            val url = awaitRemoteControlUrl(log, startupTimeoutMs)

            _state.set(AntigravityState.RUNNING)

            monitorJob?.cancel()
            monitorJob = scope.launch {
                monitorProcess(pid, log)
            }

            Result.Success(url)
        } catch (e: AntigravityStartupException) {
            AvsLogger.e(TAG, "Antigravity startup failed: [${e.error}] ${e.message}")
            if (e.error == AntigravityStartupError.AUTH_REQUIRED) {
                _state.set(AntigravityState.AUTHENTICATION_REQUIRED)
            } else {
                _state.set(AntigravityState.FAILED)
            }
            stopInternal(preserveState = true)
            Result.Failure(e, e.message)
        } catch (e: CancellationException) {
            AvsLogger.i(TAG, "Antigravity startup was cancelled")
            _state.set(if (isInstalled()) AntigravityState.STOPPED else AntigravityState.NOT_INSTALLED)
            stopInternal(preserveState = true)
            throw e
        } catch (e: Throwable) {
            AvsLogger.e(TAG, "Antigravity startup error: ${e.message}", e)
            _state.set(AntigravityState.FAILED)
            stopInternal(preserveState = true)
            Result.Failure(e, e.message)
        }
    }

    fun currentRemoteControlUrl(): String? = remoteControlUrl

    fun processId(): Int? = processPid

    fun stop() {
        stopInternal(preserveState = false)
    }

    internal suspend fun awaitRemoteControlUrl(log: File, timeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var trustConfirmed = false

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()

            val text = if (log.exists()) {
                try {
                    log.readText()
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }

            // 1. Check for valid Remote Control URL
            val url = RemoteControlUrlParser.parseUrl(text)
            if (url != null) {
                remoteControlUrl = url
                AvsLogger.i(TAG, "Remote Control URL received: ${AntigravityLogRedactor.redact(url)}")
                return url
            }

            // 2. Check for interactive workspace trust prompt fallback
            if (!trustConfirmed && StartupOutputClassifier.isTrustPrompt(text)) {
                stdinFd?.let { fd ->
                    AvsLogger.i(TAG, "Workspace trust prompt detected. Sending confirmation to PTY.")
                    spawner.writeString(fd, "\r\n")
                    trustConfirmed = true
                }
            }

            // 3. Early detection of fatal conditions
            if (StartupOutputClassifier.isAuthenticationRequired(text)) {
                throw AntigravityStartupException(
                    AntigravityStartupError.AUTH_REQUIRED,
                    "Antigravity requires authentication before starting Remote Control",
                    details = AntigravityLogRedactor.redact(text)
                )
            }

            if (StartupOutputClassifier.isRemoteControlUnavailable(text)) {
                throw AntigravityStartupException(
                    AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE,
                    "Antigravity Remote Control feature is unavailable or unsupported",
                    details = AntigravityLogRedactor.redact(text)
                )
            }

            if (StartupOutputClassifier.isNetworkError(text)) {
                throw AntigravityStartupException(
                    AntigravityStartupError.NETWORK_ERROR,
                    "Antigravity Remote Control encountered a network connection error",
                    details = AntigravityLogRedactor.redact(text)
                )
            }

            // 4. Check if process has terminated
            val pid = processPid
            if (pid == null) {
                throw AntigravityStartupException(
                    AntigravityStartupError.START_FAILED,
                    "CLI process PID is null during startup"
                )
            }

            val status = spawner.waitFor(pid, true)
            if (status != -2) {
                val finalText = if (log.exists()) {
                    try {
                        log.readText()
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }

                val error = StartupOutputClassifier.classifyError(finalText, status)
                val sanitizedDetails = AntigravityLogRedactor.redact(finalText)

                throw AntigravityStartupException(
                    error,
                    "Antigravity CLI exited with status $status before publishing Remote Control URL: ${error.description}",
                    exitCode = status,
                    details = sanitizedDetails
                )
            }

            delay(100)
        }

        val timeoutText = if (log.exists()) {
            try {
                AntigravityLogRedactor.redact(log.readText())
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }

        throw AntigravityStartupException(
            AntigravityStartupError.STARTUP_TIMEOUT,
            "Timed out after ${timeoutMs}ms waiting for Antigravity Remote Control URL",
            details = timeoutText
        )
    }

    internal suspend fun monitorProcess(pid: Int, log: File) {
        while (currentCoroutineContext()[Job]?.isActive == true) {
            val status = spawner.waitFor(pid, true)
            if (status != -2) {
                if (processPid == pid) {
                    processPid = null
                    stdinFd?.let { spawner.close(it) }
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

    internal fun stopInternal(preserveState: Boolean = false) {
        monitorJob?.cancel()
        monitorJob = null

        val pid = processPid
        if (pid != null) {
            spawner.kill(pid, 15) // SIGTERM
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {
            }
            spawner.kill(pid, 9)  // SIGKILL
            spawner.waitFor(pid, true)
        }

        stdinFd?.let { spawner.close(it) }
        stdinFd = null
        processPid = null
        remoteControlUrl = null

        if (!preserveState) {
            if (isInstalled()) _state.set(AntigravityState.STOPPED)
            else _state.set(AntigravityState.NOT_INSTALLED)
        }
    }

    private fun redact(text: String): String = AntigravityLogRedactor.redact(text)
}
