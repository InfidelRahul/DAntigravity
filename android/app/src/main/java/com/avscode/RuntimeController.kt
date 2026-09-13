package com.avscode

import android.content.Context
import java.io.File
import com.avscode.antigravity.AntigravityManager
import com.avscode.antigravity.EndpointManager
import com.avscode.codespaces.CodespaceItem
import com.avscode.codespaces.CodespaceManager
import com.avscode.core.*
import com.avscode.rootfs.RootfsInstaller
import com.avscode.runtime.LinuxRuntimeService
import com.avscode.runtime.PRootRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Authoritative single runtime controller for DroidAntigravity.
 *
 * Coordinates:
 * - Local Linux Environment (LinuxDroid / PRoot Ubuntu ARM64)
 * - Antigravity CLI and Web UI Lifecycle (agy)
 * - Cloud Remote Environment (GitHub Codespaces REST API)
 * - Android <-> Linux Authentication Bridge
 */
class RuntimeController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuntimeController"

        @Volatile
        private var instance: RuntimeController? = null

        fun getInstance(context: Context): RuntimeController {
            return instance ?: synchronized(this) {
                instance ?: RuntimeController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    val paths = AppPaths.getInstance(context)
    val rootfsInstaller = RootfsInstaller(context)
    val linuxRuntime = PRootRuntime(context, rootfsInstaller)
    val antigravityManager = AntigravityManager(context, linuxRuntime)
    val codespaceManager = CodespaceManager(context)

    // Android <-> Linux Authentication Bridge
    var onAuthRequestTriggered: ((requestId: String, authUrl: String, title: String?) -> Unit)? = null
    val authBridgeServer = AuthBridgeServer { requestId, authUrl, title ->
        AvsLogger.i(TAG, "Auth request received via bridge: requestId=$requestId authUrl=$authUrl")
        onAuthRequestTriggered?.invoke(requestId, authUrl, title)
    }

    val authBridgePort: Int get() = authBridgeServer.authBridgePort
    val serverPort: Int? get() = antigravityManager.getServerPort()
    val activeServerUrl: String? get() = antigravityManager.getServerUrl()

    private val _appState = MutableStateFlow<AppState>(AppState.NeedsStorageAccess)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _terminalLogs = MutableSharedFlow<String>(replay = 500)
    val terminalLogs: SharedFlow<String> = _terminalLogs.asSharedFlow()

    init {
        updateInitialState()
    }

    fun updateInitialState() {
        if (!StoragePermissionHelper.isStorageConfigured(context)) {
            _appState.value = AppState.NeedsStorageAccess
        } else if (!rootfsInstaller.isInstalled()) {
            _appState.value = AppState.NotInstalled
        } else if (antigravityManager.isServerRunning() && antigravityManager.getServerUrl() != null) {
            _appState.value = AppState.Ready(antigravityManager.getServerUrl()!!, "Local (LinuxDroid)")
        } else if (antigravityManager.isInstalled()) {
            _appState.value = AppState.AntigravityReady
        } else {
            _appState.value = AppState.RootfsReady
        }
    }

    private fun emitLog(line: String) {
        AvsLogger.d(TAG, line)
        scope.launch {
            _terminalLogs.emit(line)
        }
    }

    /**
     * Executes the complete runtime startup chain for the Local Linux environment.
     */
    suspend fun startAll(forceRestart: Boolean = false): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentState = _appState.value
            if (!forceRestart && currentState is AppState.Ready && antigravityManager.isServerRunning()) {
                AvsLogger.i(TAG, "Local Antigravity already ready at ${currentState.url}")
                return@withContext Result.Success(currentState.url)
            }

            // Ensure foreground service is running to avoid process killing
            try {
                LinuxRuntimeService.start(context)
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to start foreground service: ${e.message}")
            }

            // Step 0: Storage access check
            if (!StoragePermissionHelper.isStorageConfigured(context)) {
                _appState.value = AppState.NeedsStorageAccess
                emitLog("[Android] Storage access configuration required before continuing.")
                return@withContext Result.Failure(IllegalStateException("Storage access not configured"))
            }

            emitLog("[Android] Storage access verified.")

            // Step 1: Rootfs Download & Extraction (Android Host responsibility)
            if (!rootfsInstaller.isInstalled()) {
                emitLog("[Rootfs] Downloading and extracting Ubuntu 26.04 ARM64...")
                try {
                    rootfsInstaller.install { progress, status ->
                        if (progress < 0.50f) {
                            _appState.value = AppState.DownloadingRootfs(progress / 0.50f, status)
                        } else {
                            _appState.value = AppState.ExtractingRootfs((progress - 0.50f) / 0.50f, status)
                        }
                        emitLog("[Rootfs] $status")
                    }.getOrThrow()
                    _appState.value = AppState.RootfsReady
                    emitLog("[Rootfs] Rootfs verified and ready.")
                } catch (e: Throwable) {
                    _appState.value = AppState.RootfsFailed("Failed to install rootfs: ${e.message}", e)
                    emitLog("[Rootfs] ERROR: ${e.message}")
                    return@withContext Result.Failure(e)
                }
            } else {
                _appState.value = AppState.RootfsReady
                emitLog("[Rootfs] Existing Ubuntu rootfs verified.")
            }

            // Step 2: Start Linux PRoot Runtime
            _appState.value = AppState.StartingLinux
            emitLog("[Linux] Starting PRoot runtime...")
            try {
                linuxRuntime.start().getOrThrow()
            } catch (e: Throwable) {
                _appState.value = AppState.LinuxFailed("Failed to start PRoot: ${e.message}", e)
                emitLog("[Linux] ERROR: ${e.message}")
                return@withContext Result.Failure(e)
            }

            // Step 3: Verify Linux Userspace Diagnostic Probe
            _appState.value = AppState.VerifyingLinux
            emitLog("[Linux] Entering Ubuntu userspace and verifying diagnostics...")
            try {
                linuxRuntime.verifyGuestUserspace { line ->
                    emitLog(line)
                }.getOrThrow()
                _appState.value = AppState.LinuxReady
                emitLog("[Linux] Linux userspace ready. CLI is now accessible.")
            } catch (e: Throwable) {
                _appState.value = AppState.LinuxFailed("Linux userspace verification failed: ${e.message}", e)
                emitLog("[Linux] VERIFICATION FAILED: ${e.message}")
                return@withContext Result.Failure(e)
            }

            // Step 4: Linux Bootstrap (guest script: packages via apt)
            if (!paths.hostBootstrapMarker.exists()) {
                _appState.value = AppState.InstallingPackages("Configuring guest development tools...")
                emitLog("[Packages] Running Linux guest bootstrap script (/usr/local/lib/avscode/bootstrap.sh)...")
                try {
                    ensureGuestBootstrap { line ->
                        emitLog(line)
                    }.getOrThrow()
                    emitLog("[Packages] Development packages installed successfully.")
                } catch (e: Throwable) {
                    _appState.value = AppState.PackageInstallFailed("Package installation failed: ${e.message}", e)
                    emitLog("[Packages] ERROR: ${e.message}")
                    return@withContext Result.Failure(e)
                }
            } else {
                emitLog("[Packages] Development environment already bootstrapped.")
            }

            // Step 5: Install/Setup Antigravity CLI if needed
            if (!antigravityManager.isInstalled()) {
                _appState.value = AppState.InstallingAntigravity(0f, "Setting up Antigravity CLI...")
                emitLog("[Antigravity] Setting up Antigravity CLI in Ubuntu userspace (/usr/local/bin/agy)...")
                try {
                    antigravityManager.setup().getOrThrow()
                    _appState.value = AppState.AntigravityReady
                    emitLog("[Antigravity] Antigravity CLI installed and verified.")
                } catch (e: Throwable) {
                    _appState.value = AppState.AntigravityFailed("Antigravity setup failed: ${e.message}", e)
                    emitLog("[Antigravity] SETUP FAILED: ${e.message}")
                    return@withContext Result.Failure(e)
                }
            } else {
                emitLog("[Antigravity] Antigravity CLI already present.")
                _appState.value = AppState.AntigravityReady
            }

            // Step 6: Start Android <-> Linux Authentication Bridge
            _appState.value = AppState.StartingAuthBridge("Starting Auth Bridge...")
            val authBridgePort = authBridgeServer.start()
            emitLog("[AuthBridge] Android <-> Linux Authentication Bridge active on 127.0.0.1:$authBridgePort")
            setupGuestAuthHelper(authBridgePort)

            // Step 7: Start Local Antigravity Server
            _appState.value = AppState.StartingAntigravityServer("Starting Antigravity web server...")
            emitLog("[Antigravity] Launching Antigravity Web UI inside Linux userspace...")
            try {
                val serverUrl = antigravityManager.start().getOrThrow()
                _appState.value = AppState.Ready(serverUrl, "Local (LinuxDroid)")
                emitLog("[Antigravity] Local Antigravity ready at $serverUrl")
                Result.Success(serverUrl)
            } catch (e: Throwable) {
                _appState.value = AppState.AntigravityFailed("Antigravity failed to start: ${e.message}", e)
                emitLog("[Antigravity] SERVER START FAILED: ${e.message}")
                Result.Failure(e)
            }
        }
    }

    /**
     * Connects to a remote GitHub Codespace, starting it if stopped, and loading its Antigravity Web UI.
     */
    suspend fun connectCodespace(codespace: CodespaceItem, port: Int = 8080): Result<String> = withContext(Dispatchers.IO) {
        runCatchingResult {
            emitLog("[Codespace] Connecting to ${codespace.name} (${codespace.repositoryFullName})...")

            var activeItem = codespace
            if (activeItem.isStopped) {
                emitLog("[Codespace] Starting stopped Codespace...")
                activeItem = codespaceManager.startCodespace(codespace.name).getOrThrow()
            }

            if (activeItem.state != CodespaceState.AVAILABLE && activeItem.state != CodespaceState.RUNNING) {
                emitLog("[Codespace] Waiting for Codespace to be ready...")
                activeItem = codespaceManager.waitForCodespaceReady(codespace.name).getOrThrow()
            }

            val webUrl = EndpointManager.buildCodespaceEndpointUrl(activeItem.name, port)
            emitLog("[Codespace] Verified endpoint: $webUrl")
            _appState.value = AppState.Ready(webUrl, activeItem.repositoryName.ifEmpty { activeItem.name })
            webUrl
        }
    }

    /**
     * Injects the guest authentication helper script into /usr/local/bin/avscode-auth
     * and installs /usr/local/bin/xdg-open to route guest URLs to the AuthBridge.
     */
    private suspend fun setupGuestAuthHelper(authBridgePort: Int) {
        try {
            val scriptContent = """
                #!/bin/bash
                PORT="${'$'}{AVSCODE_AUTH_BRIDGE_PORT:-$authBridgePort}"
                ENDPOINT="http://127.0.0.1:${'$'}PORT"
                case "${'$'}1" in
                    open)
                        AUTH_URL="${'$'}2"
                        TITLE="${'$'}3"
                        curl -s -X POST -H "Content-Type: application/json" -d "{\"authUrl\":\"${'$'}AUTH_URL\",\"title\":\"${'$'}TITLE\"}" "${'$'}ENDPOINT/auth/request"
                        ;;
                    poll)
                        REQ_ID="${'$'}2"
                        curl -s "${'$'}ENDPOINT/auth/token?requestId=${'$'}REQ_ID"
                        ;;
                    health)
                        curl -s "${'$'}ENDPOINT/health"
                        ;;
                    *)
                        if [[ "${'$'}1" == http* ]]; then
                            curl -s -X POST -H "Content-Type: application/json" -d "{\"authUrl\":\"${'$'}1\",\"title\":\"${'$'}2\"}" "${'$'}ENDPOINT/auth/request"
                        else
                            echo "DroidAntigravity Authentication Bridge Helper"
                            echo "Usage: avscode-auth {open <url> [title] | poll <requestId> | health}"
                        fi
                        ;;
                esac
            """.trimIndent()

            val guestScriptFile = paths.hostAuthHelperScript
            guestScriptFile.parentFile?.mkdirs()
            guestScriptFile.writeText(scriptContent)
            guestScriptFile.setExecutable(true, false)
            linuxRuntime.execute("chmod 755 ${paths.guestAuthHelperScript} 2>/dev/null || true")

            val xdgOpenHostFile = File(paths.rootfsDir, "usr/local/bin/xdg-open")
            xdgOpenHostFile.parentFile?.mkdirs()
            xdgOpenHostFile.writeText("#!/bin/bash\nexec /usr/local/bin/avscode-auth \"$@\"\n")
            xdgOpenHostFile.setExecutable(true, false)
            linuxRuntime.execute("chmod 755 /usr/local/bin/xdg-open 2>/dev/null || true")
        } catch (e: Exception) {
            AvsLogger.w(TAG, "Failed to setup guest auth helper: ${e.message}")
        }
    }

    /**
     * Executes the guest-side bootstrap script /usr/local/lib/avscode/bootstrap.sh inside PRoot.
     */
    suspend fun ensureGuestBootstrap(onOutput: ((String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val marker = paths.hostBootstrapMarker
            if (marker.exists()) {
                AvsLogger.d(TAG, "Bootstrap already completed, skipping")
                onOutput?.invoke("[Bootstrap] Linux environment already bootstrapped.")
                return@runCatchingResult Unit
            }

            AvsLogger.i(TAG, "Running guest bootstrap script: ${paths.guestBootstrapScript}")
            onOutput?.invoke("[Bootstrap] Running guest bootstrap script (/usr/local/lib/avscode/bootstrap.sh)...")

            val exitCode = linuxRuntime.executeStreaming(paths.guestBootstrapScript) { line ->
                onOutput?.invoke(line)
            }.getOrThrow()

            if (exitCode != 0) {
                throw RuntimeException("Guest bootstrap script failed with exit code $exitCode")
            }

            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.createNewFile()
            }

            AvsLogger.i(TAG, "Linux bootstrap completed successfully")
            onOutput?.invoke("[Bootstrap] Linux bootstrap complete.")
            Unit
        }
    }

    suspend fun executeGuestCommand(command: String, onOutput: (String) -> Unit): Result<Int> {
        return linuxRuntime.executeStreaming(command, onOutput)
    }

    /**
     * Starts or attaches to the local Antigravity Server.
     */
    suspend fun startAntigravityServer(): Result<String> = startAll()

    /**
     * Cleanly stops Antigravity without killing Linux PRoot.
     */
    suspend fun stopAntigravityServer(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            _appState.value = AppState.Stopping
            emitLog("[Antigravity] Stopping local Antigravity server...")
            runCatchingResult {
                antigravityManager.stop()
                val nextState = if (antigravityManager.isInstalled()) AppState.AntigravityReady else AppState.LinuxReady
                _appState.value = nextState
                emitLog("[Antigravity] Antigravity stopped cleanly. Linux runtime remains active.")
            }
        }
    }

    /**
     * Ensures the Linux PRoot runtime is running for standalone Terminal access.
     */
    suspend fun ensureLinuxStarted(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (linuxRuntime.state.value.isRunning && _appState.value.canAccessCli) {
                return@withContext Result.Success(Unit)
            }
            if (!StoragePermissionHelper.isStorageConfigured(context)) {
                _appState.value = AppState.NeedsStorageAccess
                return@withContext Result.Failure(IllegalStateException("Storage access not configured"))
            }

            try {
                LinuxRuntimeService.start(context)
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to start foreground service: ${e.message}")
            }

            if (!rootfsInstaller.isInstalled()) {
                emitLog("[Rootfs] Downloading and extracting Ubuntu 26.04 ARM64...")
                rootfsInstaller.install { progress, status ->
                    if (progress < 0.50f) {
                        _appState.value = AppState.DownloadingRootfs(progress / 0.50f, status)
                    } else {
                        _appState.value = AppState.ExtractingRootfs((progress - 0.50f) / 0.50f, status)
                    }
                    emitLog("[Rootfs] $status")
                }.getOrThrow()
                _appState.value = AppState.RootfsReady
            }

            _appState.value = AppState.StartingLinux
            emitLog("[Linux] Starting PRoot runtime...")
            linuxRuntime.start().getOrThrow()

            _appState.value = AppState.VerifyingLinux
            emitLog("[Linux] Verifying guest userspace diagnostics...")
            linuxRuntime.verifyGuestUserspace { line -> emitLog(line) }.getOrThrow()

            if (!paths.hostBootstrapMarker.exists()) {
                _appState.value = AppState.InstallingPackages("Configuring guest development tools...")
                ensureGuestBootstrap { line -> emitLog(line) }.getOrThrow()
            }

            val nextState = if (antigravityManager.isInstalled()) AppState.AntigravityReady else AppState.LinuxReady
            _appState.value = nextState
            emitLog("[Linux] Standalone Linux environment active. CLI is ready.")
            Result.Success(Unit)
        }
    }

    /**
     * Gracefully stops Antigravity, Auth Bridge, Linux runtime, and foreground service.
     */
    suspend fun stopAll(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            _appState.value = AppState.Stopping
            emitLog("[Runtime] Stopping all services...")
            runCatchingResult {
                antigravityManager.stop()
                authBridgeServer.stop()
                linuxRuntime.stop()
                LinuxRuntimeService.stop(context)
                updateInitialState()
                emitLog("[Runtime] All services stopped.")
            }
        }
    }

    suspend fun restartAll(): Result<String> {
        stopAll()
        return startAll(forceRestart = true)
    }
}
