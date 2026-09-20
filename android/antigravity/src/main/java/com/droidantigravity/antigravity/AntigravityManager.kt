package com.droidantigravity.antigravity

import android.content.Context
import com.droidantigravity.core.AntigravityState
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.Result
import com.droidantigravity.core.diagnostics.DiagnosticEvent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
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
    @Volatile private var processLog: File? = null
    @Volatile private var currentOperationId: String? = null
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
     * Pre-populates cache/onboarding.json so the CLI does not stall on the interactive
     * theme selection / onboarding wizard.
     */
    internal fun ensureOnboardingCompleted(operationId: String? = null) {
        try {
            val cacheDir = File(paths.hostAntigravityDataDir, "antigravity-cli/cache")
            cacheDir.mkdirs()
            val onboardingFile = File(cacheDir, "onboarding.json")
            if (!onboardingFile.exists()) {
                val content = "{\n  \"consumerOnboardingComplete\": true,\n  \"enterpriseOnboardingComplete\": false,\n  \"onboardingComplete\": true\n}\n"
                onboardingFile.writeText(content)
                DiagnosticLogger.d(TAG, "onboarding_configured", "Pre-configured onboardingComplete in cache/onboarding.json", operationId = operationId)
            }
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "onboarding_config_failed", "Failed to pre-configure onboarding: ${e.message}", operationId = operationId)
        }
    }

    /**
     * Provisions official Antigravity OAuth credentials into ~/.gemini/antigravity-cli/antigravity-oauth-token
     * from available host sources (Android app files, environment, or developer configuration).
     */
    internal fun ensureTokenProvisioned(operationId: String? = null) {
        try {
            val targetFile = File(paths.hostAntigravityDataDir, "antigravity-cli/antigravity-oauth-token")
            if (targetFile.exists() && targetFile.length() > 0) {
                return
            }
            val candidateFiles = listOfNotNull(
                File("/root/.gemini/antigravity-cli/antigravity-oauth-token"),
                context?.let { File(it.filesDir, "antigravity-oauth-token") },
                context?.let { File(it.filesDir, "antigravity-cli/antigravity-oauth-token") }
            )
            for (candidate in candidateFiles) {
                if (candidate.exists() && candidate.length() > 0) {
                    targetFile.parentFile?.mkdirs()
                    candidate.copyTo(targetFile, overwrite = true)
                    targetFile.setReadable(true, true)
                    targetFile.setWritable(true, true)
                    DiagnosticLogger.i(TAG, "oauth_token_provisioned", "Provisioned Antigravity OAuth token from ${candidate.path}", operationId = operationId)
                    return
                }
            }
            val envToken = System.getenv("ANTIGRAVITY_OAUTH_TOKEN") ?: System.getenv("AGY_OAUTH_TOKEN")
            if (!envToken.isNullOrBlank()) {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(envToken)
                targetFile.setReadable(true, true)
                targetFile.setWritable(true, true)
                DiagnosticLogger.i(TAG, "oauth_token_provisioned_env", "Provisioned Antigravity OAuth token from environment", operationId = operationId)
            }
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "token_provision_failed", "Failed to provision token: ${e.message}", operationId = operationId)
        }
    }

    fun hasValidToken(): Boolean {
        val targetFile = File(paths.hostAntigravityDataDir, "antigravity-cli/antigravity-oauth-token")
        return targetFile.exists() && targetFile.length() > 0
    }

    /**
     * Pre-populates trusted workspaces in ~/.gemini/antigravity-cli/settings.json
     * so the official CLI never blocks indefinitely on the interactive trust prompt.
     */
    internal fun ensureWorkspaceTrusted(operationId: String? = null) {
        try {
            val cliDataDir = File(paths.hostAntigravityDataDir, "antigravity-cli")
            cliDataDir.mkdirs()
            val settingsFile = File(cliDataDir, "settings.json")
            val defaultWorkspaces = listOf(
                paths.guestHomePath,
                paths.guestProjectsPath,
                "/workspace/bold-ramanujan"
            )

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
            DiagnosticLogger.d(TAG, "workspace_trust_configured", "Pre-configured trusted workspaces in settings.json: $currentWorkspaces", operationId = operationId)
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "workspace_trust_failed", "Failed to pre-configure trusted workspaces: ${e.message}", operationId = operationId)
        }
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

        // 2. Pre-configure workspace, onboarding, and token
        ensureWorkspaceTrusted(opId)
        ensureOnboardingCompleted(opId)
        ensureTokenProvisioned(opId)

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
        val guestCommand = "exec $agyBin --remote-control --dangerously-skip-permissions"
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
                _state.set(AntigravityState.AUTHENTICATION_REQUIRED)
            } else {
                _state.set(AntigravityState.FAILED)
            }
            stopInternal(preserveState = true, operationId = opId)
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

    fun processId(): Int? = processPid

    fun currentOpId(): String? = currentOperationId

    fun stop() {
        stopInternal(preserveState = false, operationId = currentOperationId)
    }

    internal suspend fun awaitRemoteControlUrl(
        log: File,
        timeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS
    ): String = awaitRemoteControlUrl(log, paths.stderrLogFile, timeoutMs, currentOperationId ?: "AGY-INIT")

    internal suspend fun awaitRemoteControlUrl(
        log: File,
        stderrLog: File,
        timeoutMs: Long,
        operationId: String
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val startTime = System.currentTimeMillis()
        var trustConfirmed = false
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

            // 1. Check for valid Remote Control URL
            if (text.contains("https://antigravity.google.com/r/")) {
                DiagnosticLogger.d(TAG, "REMOTE_CONTROL_URL_CANDIDATE", "Candidate Remote Control URL detected in stdout", operationId = operationId)
            }

            val url = RemoteControlUrlParser.parseUrl(text)
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

            // 3. Early detection of fatal conditions
            if (StartupOutputClassifier.isAuthenticationRequired(text)) {
                DiagnosticLogger.w(TAG, "AUTHENTICATION_REQUIRED", "CLI reported authentication required", operationId = operationId)
                throw AntigravityStartupException(
                    AntigravityStartupError.AUTH_REQUIRED,
                    "Antigravity requires authentication before starting Remote Control",
                    details = DiagnosticSanitizer.redact(text),
                    operationId = operationId
                )
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
                    _state.set(
                        if (isInstalled()) {
                            AntigravityState.STOPPED
                        } else {
                            AntigravityState.NOT_INSTALLED
                        }
                    )
                    DiagnosticLogger.i(TAG, "process_exited", "Antigravity CLI process exited with status $status", operationId = operationId, processId = pid, exitCode = status)
                }
                return
            }
            delay(250)
        }
    }

    internal fun stopInternal(preserveState: Boolean = false, operationId: String? = null) {
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
