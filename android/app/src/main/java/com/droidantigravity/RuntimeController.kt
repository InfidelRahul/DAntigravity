package com.droidantigravity

import android.content.Context
import com.droidantigravity.antigravity.AntigravityManager
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.AppState
import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.diagnostics.ExportLogManager
import com.droidantigravity.core.Result
import com.droidantigravity.core.runCatchingResult
import com.droidantigravity.rootfs.RootfsInstaller
import com.droidantigravity.runtime.LinuxRuntimeService
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application-level lifecycle coordinator.
 *
 * Android owns lifecycle. Linux owns the development environment. Antigravity
 * owns its Remote Control web application. This class only coordinates them.
 */
class RuntimeController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuntimeController"

        @Volatile private var instance: RuntimeController? = null

        fun getInstance(context: Context): RuntimeController =
            instance ?: synchronized(this) {
                instance ?: RuntimeController(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    val paths = AppPaths.getInstance(context)
    val rootfsInstaller = RootfsInstaller(context)
    val linuxRuntime = PRootRuntime(context, rootfsInstaller)
    val linuxSecurityServices = com.droidantigravity.runtime.LinuxSecurityServices(linuxRuntime)
    val antigravityManager = AntigravityManager(context, linuxRuntime)

    private val _appState = MutableStateFlow<AppState>(AppState.NotInstalled)
    val appState = _appState.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 200)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    init {
        updateInitialState()
    }

    fun updateInitialState() {
        _appState.value = when {
            !rootfsInstaller.isInstalled() -> AppState.NotInstalled
            !linuxRuntime.state.value.isRunning -> AppState.RootfsReady
            !antigravityManager.isInstalled() -> AppState.LinuxReady
            antigravityManager.state.isRunning &&
                antigravityManager.currentRemoteControlUrl() != null ->
                AppState.Ready(antigravityManager.currentRemoteControlUrl()!!)
            else -> AppState.AntigravityReady
        }
    }

    private suspend fun log(message: String) {
        AvsLogger.i(TAG, message)
        _logs.emit(message)
    }

    /**
     * Full local startup:
     * rootfs -> Linux -> real agy -> Remote Control -> WebView URL.
     */
    suspend fun startAll(forceRestart: Boolean = false): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val opId = DiagnosticLogger.createOperationId("AGY")
            DiagnosticLogger.i(TAG, "start_all_initiated", "Initiating complete local runtime startup", operationId = opId)

            if (!forceRestart) {
                antigravityManager.currentRemoteControlUrl()?.let {
                    if (antigravityManager.state.isRunning) return@withContext Result.Success(it)
                }
            }

            runCatchingResult {
                try {
                    LinuxRuntimeService.start(context)
                } catch (e: Exception) {
                    DiagnosticLogger.w(TAG, "service_start_warning", "Foreground service could not start: ${e.message}", operationId = opId)
                }

                ensureRootfs()
                ensureLinux()
                ensureLinuxSecurityServices()
                ensureAntigravity()

                _appState.value = AppState.StartingAntigravityServer(
                    "Starting Antigravity Remote Control…"
                )
                log("[Antigravity] Starting official agy with Remote Control.")

                val url = antigravityManager.start(AntigravityManager.DEFAULT_STARTUP_TIMEOUT_MS, opId).getOrThrow()
                _appState.value = AppState.Ready(url, "Antigravity")
                log("[Antigravity] Remote Control is ready.")
                url
            }.also { result ->
                if (result.isFailure) {
                    val error = result.exceptionOrNull()!!
                    val failureOpId = (error as? com.droidantigravity.antigravity.AntigravityStartupException)?.operationId ?: opId
                    DiagnosticLogger.e(TAG, "start_all_failed", "Runtime startup failed: ${error.message}", error, operationId = failureOpId)

                    ExportLogManager.createFailureSnapshot(
                        context = context,
                        operationId = failureOpId,
                        error = error.message ?: "Runtime startup failed",
                        details = error.stackTraceToString()
                    )

                    val startupEx = error as? com.droidantigravity.antigravity.AntigravityStartupException
                    if (startupEx?.error == com.droidantigravity.antigravity.AntigravityStartupError.AUTH_REQUIRED) {
                        _appState.value = AppState.AuthenticationRequired(
                            error.message ?: "Google authentication required to enable Antigravity Remote Control",
                            operationId = failureOpId
                        )
                    } else {
                        _appState.value = AppState.AntigravityFailed(
                            error.message ?: "Unable to start Antigravity",
                            error,
                            operationId = failureOpId
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureRootfs() {
        if (rootfsInstaller.isInstalled()) {
            _appState.value = AppState.RootfsReady
            log("[Rootfs] Existing Ubuntu rootfs is ready.")
            return
        }

        log("[Rootfs] Installing Ubuntu ARM64 rootfs.")
        rootfsInstaller.install { progress, status ->
            _appState.value =
                if (progress < 0.5f) {
                    AppState.DownloadingRootfs(progress * 2f, status)
                } else {
                    AppState.ExtractingRootfs((progress - 0.5f) * 2f, status)
                }
            AvsLogger.i(TAG, "[Rootfs] $status")
        }.getOrThrow()

        _appState.value = AppState.RootfsReady
        log("[Rootfs] Rootfs installation completed.")
    }

    private suspend fun ensureLinux() {
        if (!linuxRuntime.state.value.isRunning) {
            _appState.value = AppState.StartingLinux
            log("[Linux] Starting PRoot userspace.")
            linuxRuntime.start().getOrThrow()
        }

        _appState.value = AppState.VerifyingLinux
        linuxRuntime.verifyGuestUserspace { output ->
            AvsLogger.d(TAG, "[Linux] $output")
        }.getOrThrow()

        _appState.value = AppState.LinuxReady
        log("[Linux] Guest userspace verified.")
    }

    private suspend fun ensureLinuxSecurityServices() {
        _appState.value = AppState.InstallingPackages(
            "Preparing D-Bus and Linux Secret Service…"
        )
        log("[Linux] Verifying D-Bus and Secret Service support.")
        linuxSecurityServices.ensureInstalled().getOrThrow()
        linuxSecurityServices.verifySecretService().getOrThrow()
        _appState.value = AppState.LinuxReady
        log("[Linux] D-Bus and Secret Service are operational.")
    }

    private suspend fun ensureAntigravity() {
        if (!antigravityManager.isInstalled()) {
            _appState.value = AppState.InstallingAntigravity(
                0.1f,
                "Installing the official Antigravity CLI…"
            )
            log("[Antigravity] Installing official CLI inside Linux HOME.")
            antigravityManager.install().getOrThrow()
        }

        _appState.value = AppState.AntigravityReady
        val capabilities = antigravityManager.capabilities().getOrNull()
        if (capabilities != null) {
            log("[Antigravity] ${capabilities.version}; interactive Remote Control=${capabilities.interactiveRemoteControl}; daemon capability=${capabilities.remoteControlDaemon}")
        } else {
            val version = antigravityManager.version().getOrNull()?.trim()
            if (!version.isNullOrBlank()) log("[Antigravity] $version")
        }
    }

    suspend fun stopAntigravity(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatchingResult {
                _appState.value = AppState.Stopping
                antigravityManager.stop()
                _appState.value = AppState.LinuxReady
                log("[Antigravity] Stopped. Linux remains running.")
            }
        }
    }

    suspend fun stopAll(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatchingResult {
                _appState.value = AppState.Stopping
                antigravityManager.stop()
                linuxRuntime.stop()
                LinuxRuntimeService.stop(context)
                updateInitialState()
                log("[Runtime] Linux and Antigravity stopped.")
            }
        }
    }

    suspend fun restartAll(): Result<String> {
        stopAll()
        return startAll(forceRestart = true)
    }

    suspend fun executeGuestCommand(
        command: String,
        onOutput: (String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        linuxRuntime.executeStreaming(command, onOutput)
    }

    fun isLinuxRunning(): Boolean = linuxRuntime.state.value.isRunning
}
