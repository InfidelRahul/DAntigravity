package com.droidantigravity.antigravity

import android.content.Context
import com.droidantigravity.core.AntigravityState
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.Result
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.DiagnosticSanitizer
import com.droidantigravity.core.runCatchingResult
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the real Antigravity CLI process inside the Linux userspace.
 *
 * DroidAntigravity does not implement an Antigravity HTTP server. The official
 * CLI establishes the Remote Control reverse tunnel and prints the URL that
 * the Android WebView consumes.
 *
 * Fully instrumented with [DiagnosticLogger] and traceable operation IDs.
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
        private const val TAG = "Antigravity"
        const val DEFAULT_STARTUP_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _state = AtomicReference(AntigravityState.UNKNOWN)

    @Volatile private var processPid: Int? = null
    @Volatile private var stdinFd: Int? = null
    @Volatile private var remoteControlUrl: String? = null
    @Volatile private var currentAuthUrl: String? = null
    @Volatile private var processLog: File? = null
    @Volatile private var currentOperationId: String? = null
    private var monitorJob: Job? = null
    private var ptyReaderJob: Job? = null
    private val browserOpened = AtomicBoolean(false)

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
            DiagnosticLogger.e(TAG, "install_check_error", "Failed to check Antigravity installation", e)
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

    suspend fun capabilities(): Result<AntigravityCapabilities> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val cliVersion = version().getOrThrow().trim()
            val help = linuxRuntime.execute("agy remote-control --help 2>&1 || true").getOrDefault("")
            AntigravityCapabilities(
                version = cliVersion,
                interactiveRemoteControl = true,
                remoteControlDaemon = help.contains("remote-control", ignoreCase = true) &&
                    help.contains("start", ignoreCase = true)
            )
        }
    }

    /**
     * Installs the official Linux CLI using Google's published installer.
     */
    suspend fun install(operationId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val opId = operationId ?: DiagnosticLogger.createOperationId("AGY_INSTALL")
        DiagnosticLogger.i(TAG, "install_start", "Starting official Antigravity CLI installation", operationId = opId)
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
                DiagnosticLogger.d(TAG, "installer_output", DiagnosticSanitizer.redact(output), operationId = opId)
            }.getOrThrow()

            if (result != 0) {
                throw IllegalStateException("Antigravity installer exited with code $result")
            }

            if (!isInstalled()) {
                throw IllegalStateException("Official installer completed but 'agy' is not on PATH")
            }

            DiagnosticLogger.i(TAG, "install_success", "Official Antigravity CLI installed successfully", operationId = opId)
            _state.set(AntigravityState.STOPPED)
        }.also {
            if (it.isFailure) {
                val err = it.exceptionOrNull()
                DiagnosticLogger.e(TAG, "install_failed", "Antigravity installation failed: ${err?.message}", err, operationId = opId)
                _state.set(AntigravityState.FAILED)
            }
        }
    }

    suspend fun ensureInstalled(operationId: String? = null): Result<Unit> {
        val opId = operationId ?: currentOperationId
        DiagnosticLogger.d(TAG, "ensure_installed_check", "Checking if Antigravity CLI is installed", operationId = opId)
        if (isInstalled()) {
            if (_state.get() == AntigravityState.UNKNOWN || _state.get() == AntigravityState.NOT_INSTALLED) {
                _state.set(AntigravityState.STOPPED)
            }
            DiagnosticLogger.d(TAG, "ensure_installed_ok", "Antigravity CLI is already installed", operationId = opId)
            return Result.Success(Unit)
        }
        return install(opId)
    }

    /**
     * Starts one interactive CLI session with Remote Control enabled.
     */
    suspend fun start(): Result<String> = start(DEFAULT_STARTUP_TIMEOUT_MS)

    suspend fun start(startupTimeoutMs: Long): Result<String> = start(startupTimeoutMs, null)

    suspend fun start(startupTimeoutMs: Long, operationId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val opId = operationId ?: DiagnosticLogger.createOperationId("AGY")
        currentOperationId = opId
        val startTime = System.currentTimeMillis()

        DiagnosticLogger.i(TAG, "start", "Starting Antigravity CLI lifecycle", operationId = opId)

        if (state == AntigravityState.RUNNING && processPid != null && remoteControlUrl != null) {
            val check = spawner.waitFor(processPid!!, true, opId)
            if (check == -2) {
                DiagnosticLogger.i(TAG, "already_running", "Reusing existing running Remote Control session", operationId = opId, processId = processPid)
                return@withContext Result.Success(remoteControlUrl!!)
            }
        }

        // 1. Verify installation
        DiagnosticLogger.d(TAG, "check_installation", "Verifying agy binary availability", operationId = opId)
        if (!isInstalled()) {
            DiagnosticLogger.i(TAG, "install_required", "Antigravity CLI not installed. Triggering installation.", operationId = opId)
            val installResult = install(opId)
            if (installResult.isFailure) {
                val ex = AntigravityStartupException(
                    AntigravityStartupError.NOT_INSTALLED,
                    "Antigravity CLI is not installed and auto-installation failed: ${installResult.exceptionOrNull()?.message}",
                    operationId = opId,
                    cause = installResult.exceptionOrNull()
                )
                _state.set(AntigravityState.FAILED)
                DiagnosticLogger.recordError(ex, TAG, opId, "FAILED", "Installation failed")
                return@withContext Result.Failure(ex, ex.message)
            }
        }

        // Query and log version
        val cliVersion = try {
            version().getOrNull()?.trim() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        DiagnosticLogger.i(TAG, "version_verified", "Antigravity CLI version: $cliVersion", operationId = opId)

        stopInternal(preserveState = false, operationId = opId)

        _state.set(AntigravityState.STARTING)
        remoteControlUrl = null
        currentAuthUrl = null
        browserOpened.set(false)

        // 2. Keep Antigravity-owned credentials and configuration untouched.
        // The official CLI owns authentication, session persistence, onboarding and logout.
        // DroidAntigravity only provides the Linux runtime and process lifecycle.

        // 3. Prepare log destinations (separate stdout and stderr)
        val combinedLog = paths.antigravityLogFile
        val stdoutLog = paths.stdoutLogFile
        val stderrLog = paths.stderrLogFile

        combinedLog.parentFile?.mkdirs()
        stdoutLog.parentFile?.mkdirs()
        stderrLog.parentFile?.mkdirs()

        combinedLog.writeText("")
        stdoutLog.writeText("")
        stderrLog.writeText("")
        processLog = combinedLog

        // 4. CLI environment diagnostics
        val agyBin = if (paths.hostAntigravityBin.exists()) paths.guestAntigravityBin else "agy"
        // Run the CLI as the persistent Linux user, not PRoot's synthetic root.
        // This is important for Secret Service: dbus-daemon otherwise sees uid 0
        // and attempts the privileged 65536-fd rlimit path, which Android/PRoot
        // cannot satisfy (CAP_SYS_RESOURCE is unavailable).
        //
        // The browser bridge is intentionally tiny: when the official CLI asks
        // its local browser launcher to open OAuth, the bridge records the URL
        // inside the persistent guest rootfs. Android observes that file and
        // opens the URL with ACTION_VIEW. No credentials or tokens are stored
        // by DroidAntigravity.
        val browserBridge = paths.antigravityBrowserBridgeScript.absolutePath
        val browserUrlFile = paths.antigravityBrowserUrlFile.absolutePath
        val launcherScript = paths.antigravityLauncherScript
        paths.antigravityBrowserBridgeScript.parentFile?.mkdirs()
        paths.antigravityBrowserUrlFile.parentFile?.mkdirs()
        paths.antigravityBrowserUrlFile.delete()

        // Linux-side browser bridge: the official CLI still owns the OAuth
        // flow. This helper only hands its generated authorization URL to the
        // Android host; it never stores credentials/tokens.
        val xdgOpen = paths.antigravityBrowserBridgeScript.parentFile!!.resolve("xdg-open")
        val sensibleBrowser = paths.antigravityBrowserBridgeScript.parentFile!!.resolve("sensible-browser")
        val browserBridgeScript = "#!/bin/sh\nprintf '%s' \"\$1\" > '$browserUrlFile.tmp'\nmv -f '$browserUrlFile.tmp' '$browserUrlFile'\nexit 0\n"
        paths.antigravityBrowserBridgeScript.writeText(browserBridgeScript)
        xdgOpen.writeText(browserBridgeScript)
        sensibleBrowser.writeText(browserBridgeScript)
        paths.antigravityBrowserBridgeScript.setExecutable(true, false)
        xdgOpen.setExecutable(true, false)
        sensibleBrowser.setExecutable(true, false)

        // Keep the command itself in a guest script. This avoids nested shell
        // quoting errors and guarantees clean environment isolation.
        launcherScript.writeText("#!/bin/bash\nset -e\nexport HOME=/home/user\nexport USER=user\nexport LOGNAME=user\nexport PATH=/home/user/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nexport BROWSER='$browserBridge'\nexport XDG_RUNTIME_DIR=/tmp/droidantigravity-runtime-1000\nmkdir -p \"\$XDG_RUNTIME_DIR\"\nchmod 700 \"\$XDG_RUNTIME_DIR\"\nexec dbus-run-session -- /bin/bash -lc 'eval \"\$(gnome-keyring-daemon --start --components=secrets 2>/dev/null)\"; exec $agyBin --remote-control --dangerously-skip-permissions'\n")
        launcherScript.setExecutable(true, false)
        val guestCommand = "exec /bin/bash '${launcherScript.absolutePath}'"
        val guestCwd = paths.guestHomePath
        val guestPath = "/home/user/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        DiagnosticLogger.i(
            TAG,
            "cli_diagnostics_ready",
            "CLI startup config: bin=[${paths.guestAntigravityBin}], version=[$cliVersion], cmd=[$guestCommand], home=[${paths.guestHomePath}], PATH=[$guestPath], cwd=[$guestCwd]",
            operationId = opId
        )

        // 5. Build PRoot command & environment
        DiagnosticLogger.d(TAG, "proot_command_creating", "Building PRoot arguments and environment", operationId = opId)
        val args = linuxRuntime.buildPRootArgs(guestCommand, guestCwd)
        val env = linuxRuntime.buildEnvironment(
            guestCwd,
            mapOf(
                "PATH" to guestPath,
                "AGY_CLI_HIDE_LOGO" to "1",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor"
            )
        )

        DiagnosticLogger.i(TAG, "REMOTE_CONTROL_STARTING", "Initiating Remote Control process spawn", operationId = opId)

        try {
            val spawned = spawner.spawnPtyWithStreams(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                stdoutPath = combinedLog.absolutePath,
                stderrPath = stderrLog.absolutePath,
                cols = 80,
                rows = 24,
                operationId = opId
            ) ?: throw AntigravityStartupException(
                AntigravityStartupError.START_FAILED,
                "Unable to spawn agy process using PTY",
                operationId = opId
            )

            val pid = spawned[0]
            processPid = pid
            stdinFd = spawned.getOrNull(1)?.takeIf { it >= 0 }

            DiagnosticLogger.i(
                TAG,
                "REMOTE_CONTROL_PROCESS_STARTED",
                "Antigravity CLI process spawned: pid=$pid, masterPtyFd=${spawned.getOrNull(1)}",
                operationId = opId,
                processId = pid
            )

            // The PTY has exactly one reader. Capture its bytes here rather than
            // in native code so interactive input/output cannot be stolen by a
            // second diagnostic pump.
            startPtyReader(spawned.getOrNull(1) ?: -1, combinedLog, stderrLog, opId)

            // 6. Polling & liveness loop
            val url = awaitRemoteControlUrl(combinedLog, stderrLog, startupTimeoutMs, opId)
            val duration = System.currentTimeMillis() - startTime

            _state.set(AntigravityState.RUNNING)
            DiagnosticLogger.i(
                TAG,
                "REMOTE_CONTROL_READY",
                "Antigravity Remote Control URL established in ${duration}ms: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}",
                operationId = opId,
                processId = pid
            )

            // 7. Background process monitor
            monitorJob?.cancel()
            monitorJob = scope.launch {
                monitorProcess(pid, combinedLog, opId)
            }

            Result.Success(url)
        } catch (e: AntigravityStartupException) {
            val duration = System.currentTimeMillis() - startTime
            DiagnosticLogger.e(
                TAG,
                "start_failed",
                "Antigravity startup failed in ${duration}ms: [${e.error}] ${e.message}",
                e,
                operationId = opId,
                processId = processPid,
                exitCode = e.exitCode
            )
            if (e.error == AntigravityStartupError.AUTH_REQUIRED) {
                // Keep the interactive CLI/PTy alive. The user must be able to
                // complete the official authentication flow in the attached
                // terminal; killing the process here makes authentication
                // impossible and caused the previous 60s timeout loop.
                _state.set(AntigravityState.AUTHENTICATION_REQUIRED)
                val pid = processPid
                if (pid != null && spawner.waitFor(pid, true, opId) == -2) {
                    monitorJob?.cancel()
                    monitorJob = scope.launch {
                        monitorProcess(pid, combinedLog, opId)
                    }
                } else {
                    stopInternal(preserveState = true, operationId = opId)
                }
            } else {
                _state.set(AntigravityState.FAILED)
                stopInternal(preserveState = true, operationId = opId)
            }
            Result.Failure(e, e.message)
        } catch (e: CancellationException) {
            DiagnosticLogger.i(TAG, "start_cancelled", "Antigravity startup was cancelled", operationId = opId)
            _state.set(if (isInstalled()) AntigravityState.STOPPED else AntigravityState.NOT_INSTALLED)
            stopInternal(preserveState = true, operationId = opId)
            throw e
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            DiagnosticLogger.e(
                TAG,
                "start_error",
                "Antigravity startup unexpected error in ${duration}ms: ${e.message}",
                e,
                operationId = opId,
                processId = processPid
            )
            _state.set(AntigravityState.FAILED)
            stopInternal(preserveState = true, operationId = opId)
            Result.Failure(e, e.message)
        }
    }

    fun currentRemoteControlUrl(): String? = remoteControlUrl

    fun currentAuthUrl(): String? = currentAuthUrl

    fun processId(): Int? = processPid

    fun currentOpId(): String? = currentOperationId

    /**
     * Sends arbitrary string input to the running CLI process's stdin.
     */
    fun sendInput(input: String): Boolean {
        val fd = stdinFd ?: return false
        val pid = processPid ?: return false
        if (spawner.waitFor(pid, true, currentOperationId) != -2) return false
        return spawner.writeString(fd, input)
    }

    /**
     * Submits the user's authorization code to the STILL-RUNNING agy process stdin,
     * then awaits the final Remote Control URL.
     */
    suspend fun submitAuthorizationCode(
        code: String,
        timeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        val opId = currentOperationId ?: DiagnosticLogger.createOperationId("AGY_AUTH_CODE")
        DiagnosticLogger.i(TAG, "AUTH_CODE_SUBMISSION_START", "Submitting authorization code to running agy process", operationId = opId)

        val pid = processPid
        val fd = stdinFd
        if (pid == null || fd == null || fd < 0) {
            val err = AntigravityStartupException(
                AntigravityStartupError.START_FAILED,
                "Antigravity CLI process is not running. Please restart Antigravity.",
                operationId = opId
            )
            _state.set(AntigravityState.FAILED)
            return@withContext Result.Failure(err, err.message)
        }

        val status = spawner.waitFor(pid, true, opId)
        if (status != -2) {
            val err = AntigravityStartupException(
                AntigravityStartupError.PROCESS_EXITED,
                "Antigravity CLI process exited with status $status before code submission.",
                exitCode = status,
                operationId = opId
            )
            _state.set(AntigravityState.FAILED)
            return@withContext Result.Failure(err, err.message)
        }

        val cleanCode = code.trim()
        require(cleanCode.isNotEmpty()) { "Authorization code cannot be empty" }

        DiagnosticLogger.i(
            TAG,
            "AUTH_CODE_SUBMITTED",
            "Writing authorization code to CLI stdin (length=${cleanCode.length})",
            operationId = opId,
            processId = pid
        )

        // Write authorization code followed by newline to the existing process PTY stdin
        val written = spawner.writeString(fd, "$cleanCode\n")
        if (!written) {
            val err = AntigravityStartupException(
                AntigravityStartupError.FAILED,
                "Failed to write authorization code to Antigravity CLI stdin",
                operationId = opId
            )
            return@withContext Result.Failure(err, err.message)
        }

        _state.set(AntigravityState.STARTING)

        try {
            val url = awaitRemoteControlUrl(
                paths.antigravityLogFile,
                paths.stderrLogFile,
                timeoutMs,
                opId,
                authenticationAlreadyHandled = true
            )
            remoteControlUrl = url
            _state.set(AntigravityState.RUNNING)
            DiagnosticLogger.i(
                TAG,
                "REMOTE_CONTROL_READY",
                "Remote Control URL established after authentication: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}",
                operationId = opId,
                processId = pid
            )
            Result.Success(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AntigravityStartupException) {
            DiagnosticLogger.e(TAG, "auth_code_verification_failed", "Authentication failed: [${e.error}] ${e.message}", e, operationId = opId)
            if (e.error == AntigravityStartupError.AUTH_REQUIRED) {
                _state.set(AntigravityState.AUTHENTICATION_REQUIRED)
            } else {
                _state.set(AntigravityState.FAILED)
            }
            Result.Failure(e, e.message)
        } catch (e: Throwable) {
            DiagnosticLogger.e(TAG, "auth_code_error", "Unexpected error during auth completion: ${e.message}", e, operationId = opId)
            _state.set(AntigravityState.FAILED)
            Result.Failure(e, e.message)
        }
    }

    @Deprecated("Antigravity process is dedicated to Remote Control; interactive shell runs independently")
    fun takeInteractivePty(): IntArray? {
        DiagnosticLogger.w(TAG, "takeInteractivePty_deprecated", "takeInteractivePty called but Antigravity CLI process is preserved for Remote Control")
        return null
    }

    /**
     * Continues URL detection after the user has completed authentication.
     * The existing agy process is reused.
     */
    suspend fun continueAfterAuthentication(timeoutMs: Long = 10 * 60 * 1000L): Result<String> =
        withContext(Dispatchers.IO) {
            val opId = currentOperationId ?: DiagnosticLogger.createOperationId("AGY_AUTH")
            val pid = processPid
            if (pid == null || spawner.waitFor(pid, true, opId) != -2) {
                return@withContext start(DEFAULT_STARTUP_TIMEOUT_MS, opId)
            }

            try {
                val url = awaitRemoteControlUrl(
                    paths.antigravityLogFile,
                    paths.stderrLogFile,
                    timeoutMs,
                    opId,
                    authenticationAlreadyHandled = true
                )
                remoteControlUrl = url
                _state.set(AntigravityState.RUNNING)
                monitorJob?.cancel()
                monitorJob = scope.launch { monitorProcess(pid, paths.antigravityLogFile, opId) }
                Result.Success(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AntigravityStartupException) {
                _state.set(AntigravityState.FAILED)
                stopInternal(preserveState = true, operationId = opId)
                Result.Failure(e, e.message)
            } catch (e: Throwable) {
                _state.set(AntigravityState.FAILED)
                stopInternal(preserveState = true, operationId = opId)
                Result.Failure(e, e.message)
            }
        }

    fun stop() {
        stopInternal(preserveState = false, operationId = currentOperationId)
    }

    private fun readBrowserBridgeUrl(): String? {
        val file = paths.antigravityBrowserUrlFile
        if (!file.exists()) return null
        return try {
            val content = file.readText().trim()
            if (content.startsWith("http://") || content.startsWith("https://")) {
                content
            } else null
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun awaitRemoteControlUrl(
        log: File,
        timeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS
    ): String = awaitRemoteControlUrl(log, paths.stderrLogFile, timeoutMs, currentOperationId ?: "AGY-INIT")

    internal suspend fun awaitRemoteControlUrl(
        log: File,
        stderrLog: File,
        timeoutMs: Long,
        operationId: String,
        authenticationAlreadyHandled: Boolean = false
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val startTime = System.currentTimeMillis()
        var trustConfirmed = false
        var authNoticeLogged = false
        var lastLivenessCheck = System.currentTimeMillis()
        var lastStdoutSize = 0L
        var lastStderrSize = 0L
        var lastStdoutAt: Long? = null
        var lastStderrAt: Long? = null

        DiagnosticLogger.i(TAG, "WAITING_FOR_REMOTE_CONTROL", "Beginning Remote Control URL detection (timeout=${timeoutMs}ms)", operationId = operationId)

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()

            val text = if (log.exists()) {
                try { log.readText() } catch (e: Exception) { "" }
            } else ""

            val stderrText = if (stderrLog.exists()) {
                try { stderrLog.readText() } catch (e: Exception) { "" }
            } else ""

            // Stream tracking
            val currentStdoutSize = log.length()
            if (currentStdoutSize > lastStdoutSize) {
                lastStdoutAt = System.currentTimeMillis()
                val newBytes = currentStdoutSize - lastStdoutSize
                DiagnosticLogger.d(TAG, "REMOTE_CONTROL_OUTPUT_RECEIVED", "Received $newBytes stdout bytes from CLI", operationId = operationId)
                lastStdoutSize = currentStdoutSize
            }

            val currentStderrSize = stderrLog.length()
            if (currentStderrSize > lastStderrSize) {
                lastStderrAt = System.currentTimeMillis()
                val newBytes = currentStderrSize - lastStderrSize
                DiagnosticLogger.w(TAG, "stderr_output_received", "Received $newBytes stderr bytes from CLI: ${DiagnosticSanitizer.redact(stderrText.takeLast(500))}", operationId = operationId)
                lastStderrSize = currentStderrSize
            }

            // 1. Check for valid Remote Control URL in stdout or stderr
            if (text.contains("/r/")) {
                DiagnosticLogger.d(TAG, "REMOTE_CONTROL_URL_CANDIDATE", "Candidate Remote Control URL detected in stdout", operationId = operationId)
            }

            val url = RemoteControlUrlParser.parseUrl(text) ?: RemoteControlUrlParser.parseUrl(stderrText)
            if (url != null) {
                remoteControlUrl = url
                DiagnosticLogger.i(
                    TAG,
                    "REMOTE_CONTROL_URL_VALIDATED",
                    "Validated Remote Control URL: ${DiagnosticSanitizer.redactRemoteControlUrl(url)}",
                    operationId = operationId
                )
                return url
            }

            // 2. Check for interactive workspace trust prompt fallback
            if (!trustConfirmed && StartupOutputClassifier.isTrustPrompt(text)) {
                stdinFd?.let { fd ->
                    DiagnosticLogger.i(TAG, "TRUST_PROMPT_DETECTED", "Workspace trust prompt detected. Sending auto-confirmation to PTY.", operationId = operationId)
                    spawner.writeString(fd, "\r\n")
                    trustConfirmed = true
                }
            }

            // 3. Continuously detect candidate authentication URL
            val detectedAuthUrl = AuthenticationUrlParser.extractAuthUrl(text)
                ?: AuthenticationUrlParser.extractAuthUrl(stderrText)
                ?: readBrowserBridgeUrl()

            if (detectedAuthUrl != null && currentAuthUrl == null) {
                currentAuthUrl = detectedAuthUrl
                DiagnosticLogger.i(
                    TAG,
                    "AUTH_URL_AVAILABLE",
                    "Authentication URL detected: ${DiagnosticSanitizer.redact(detectedAuthUrl)}",
                    operationId = operationId
                )
            }

            // 4. Handle login method selection if prompted (e.g. "Select login method: 1. Google OAuth")
            if (text.contains("select login method", ignoreCase = true) || text.contains("1. google oauth", ignoreCase = true)) {
                val fd = stdinFd
                if (fd != null && browserOpened.compareAndSet(false, true)) {
                    DiagnosticLogger.i(TAG, "AUTH_GOOGLE_SELECTED", "Selecting Google OAuth in official CLI", operationId = operationId)
                    spawner.writeString(fd, "\r")
                }
            }

            // 5. Authentication required check
            if (!authNoticeLogged && (StartupOutputClassifier.isAuthenticationRequired(text) || currentAuthUrl != null || StartupOutputClassifier.isWaitingForAuthCode(text))) {
                authNoticeLogged = true
                DiagnosticLogger.i(
                    TAG,
                    "AUTHENTICATION_REQUIRED",
                    "Official CLI reported an authentication state. Waiting for authorization code.",
                    operationId = operationId
                )
                if (!authenticationAlreadyHandled) {
                    _state.set(AntigravityState.AUTHENTICATION_REQUIRED)
                    throw AntigravityStartupException(
                        error = AntigravityStartupError.AUTH_REQUIRED,
                        message = "Authorization required. Complete authentication in your browser. Then paste the authorization code here.",
                        details = DiagnosticSanitizer.redact(text.takeLast(4000)),
                        operationId = operationId,
                        authUrl = currentAuthUrl
                    )
                }
            }

            if (StartupOutputClassifier.isRemoteControlUnavailable(text)) {
                DiagnosticLogger.e(TAG, "REMOTE_CONTROL_UNAVAILABLE", "Remote control feature disabled or unavailable", operationId = operationId)
                throw AntigravityStartupException(
                    AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE,
                    "Antigravity Remote Control feature is unavailable or unsupported",
                    details = DiagnosticSanitizer.redact(text),
                    operationId = operationId
                )
            }

            if (StartupOutputClassifier.isNetworkError(text)) {
                DiagnosticLogger.e(TAG, "NETWORK_ERROR", "Network connection failed during Remote Control setup", operationId = operationId)
                throw AntigravityStartupException(
                    AntigravityStartupError.NETWORK_ERROR,
                    "Antigravity Remote Control encountered a network connection error",
                    details = DiagnosticSanitizer.redact(text),
                    operationId = operationId
                )
            }

            // 4. Process liveness check
            val pid = processPid
            if (pid == null) {
                throw AntigravityStartupException(
                    AntigravityStartupError.START_FAILED,
                    "CLI process PID is null during startup",
                    operationId = operationId
                )
            }

            val status = spawner.waitFor(pid, true, operationId)
            if (status != -2) {
                // Process terminated prematurely
                val finalText = if (log.exists()) {
                    try { log.readText() } catch (e: Exception) { "" }
                } else ""
                val finalStderr = if (stderrLog.exists()) {
                    try { stderrLog.readText() } catch (e: Exception) { "" }
                } else ""

                val error = StartupOutputClassifier.classifyError(finalText, status)
                val sanitizedStdout = DiagnosticSanitizer.redact(finalText)
                val sanitizedStderr = DiagnosticSanitizer.redact(finalStderr)

                DiagnosticLogger.e(
                    TAG,
                    "PROCESS_EXITED_EARLY",
                    "CLI exited prematurely with exitCode=$status before publishing URL. Error: ${error.description}",
                    operationId = operationId,
                    processId = pid,
                    exitCode = status
                )

                throw AntigravityStartupException(
                    error,
                    "Antigravity CLI exited with status $status before publishing Remote Control URL: ${error.description}",
                    exitCode = status,
                    stdout = sanitizedStdout,
                    stderr = sanitizedStderr,
                    details = "$sanitizedStdout\n$sanitizedStderr",
                    operationId = operationId
                )
            }

            // Periodic liveness heartbeat (every 2 seconds)
            val now = System.currentTimeMillis()
            if (now - lastLivenessCheck >= 2000L) {
                val elapsed = now - startTime
                DiagnosticLogger.d(
                    TAG,
                    "WAIT_REMOTE_CONTROL",
                    "Waiting for Remote Control: elapsed=${elapsed}ms, pidAlive=true, stdoutBytes=$currentStdoutSize, stderrBytes=$currentStderrSize",
                    operationId = operationId,
                    processId = pid
                )
                lastLivenessCheck = now
            }

            delay(100)
        }

        // Timeout reached - construct full diagnostic snapshot
        val elapsedMs = System.currentTimeMillis() - startTime
        val pid = processPid
        val isAlive = pid?.let { spawner.waitFor(it, true, operationId) == -2 } ?: false
        val finalExitCode = pid?.let { spawner.waitFor(it, true, operationId) } ?: -1
        val timeoutStdout = if (log.exists()) DiagnosticSanitizer.redact(log.readText()) else ""
        val timeoutStderr = if (stderrLog.exists()) DiagnosticSanitizer.redact(stderrLog.readText()) else ""

        DiagnosticLogger.e(
            TAG,
            "REMOTE_CONTROL_TIMEOUT",
            "Timeout waiting for Remote Control URL: elapsed=${elapsedMs}ms, pidAlive=$isAlive, stdoutBytes=${log.length()}, stderrBytes=${stderrLog.length()}, exitCode=$finalExitCode",
            operationId = operationId,
            processId = pid,
            exitCode = if (isAlive) null else finalExitCode
        )

        throw AntigravityStartupException(
            AntigravityStartupError.STARTUP_TIMEOUT,
            "Timed out after ${timeoutMs}ms waiting for Antigravity Remote Control URL",
            exitCode = if (isAlive) null else finalExitCode,
            stdout = timeoutStdout,
            stderr = timeoutStderr,
            details = "STDOUT:\n$timeoutStdout\n\nSTDERR:\n$timeoutStderr",
            operationId = operationId
        )
    }

    private fun startPtyReader(fd: Int, stdout: File, stderr: File, operationId: String) {
        if (fd < 0) return
        ptyReaderJob?.cancel()
        ptyReaderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(32 * 1024)
            try {
                while (currentCoroutineContext().isActive && processPid != null) {
                    val count = spawner.read(fd, buffer)
                    if (count > 0) {
                        val text = String(buffer, 0, count, Charsets.UTF_8)
                        stdout.parentFile?.mkdirs()
                        stdout.appendText(text)
                        // stderr shares the PTY for interactive CLI mode. Keep
                        // stderr file useful by recording only explicit diagnostic
                        // stream data there when the process later exits.
                    } else if (count == -4) {
                        continue
                    } else if (count == -11) {
                        delay(5)
                    } else if (count < 0) {
                        break
                    } else {
                        delay(5)
                    }
                }
            } catch (_: CancellationException) {
                // Ownership may be transferred to the Android terminal.
            } catch (t: Throwable) {
                DiagnosticLogger.w(TAG, "pty_reader_stopped", "PTY reader stopped: ${t.message}", operationId = operationId)
            }
        }
    }

    private fun stopPtyReader() {
        ptyReaderJob?.cancel()
        ptyReaderJob = null
    }

    internal suspend fun monitorProcess(pid: Int, log: File, operationId: String?) {
        DiagnosticLogger.d(TAG, "monitor_started", "Background process monitor started for PID $pid", operationId = operationId, processId = pid)
        while (currentCoroutineContext()[Job]?.isActive == true) {
            val status = spawner.waitFor(pid, true, operationId)
            if (status != -2) {
                if (processPid == pid) {
                    processPid = null
                    stdinFd?.let { spawner.close(it, operationId) }
                    stdinFd = null
                    remoteControlUrl = null
                    if (_state.get() != AntigravityState.AUTHENTICATION_REQUIRED) {
                        _state.set(
                            if (isInstalled()) {
                                AntigravityState.STOPPED
                            } else {
                                AntigravityState.NOT_INSTALLED
                            }
                        )
                    }
                    DiagnosticLogger.i(TAG, "process_exited", "Antigravity CLI process exited with status $status", operationId = operationId, processId = pid, exitCode = status)
                }
                return
            }
            delay(250)
        }
    }

    internal fun stopInternal(preserveState: Boolean = false, operationId: String? = null) {
        stopPtyReader()
        monitorJob?.cancel()
        monitorJob = null

        val pid = processPid
        if (pid != null) {
            DiagnosticLogger.d(TAG, "stop_process", "Terminating Antigravity CLI process group for PID $pid", operationId = operationId, processId = pid)
            spawner.kill(pid, 15, operationId) // SIGTERM
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {}
            spawner.kill(pid, 9, operationId)  // SIGKILL
            spawner.waitFor(pid, true, operationId)
        }

        stdinFd?.let { spawner.close(it, operationId) }
        stdinFd = null
        processPid = null
        remoteControlUrl = null

        if (!preserveState) {
            if (isInstalled()) _state.set(AntigravityState.STOPPED)
            else _state.set(AntigravityState.NOT_INSTALLED)
        }
    }
}
