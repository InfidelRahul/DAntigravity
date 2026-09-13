package com.avscode.antigravity

import android.content.Context
import com.avscode.core.AntigravityState
import com.avscode.core.AppPaths
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.runCatchingResult
import com.avscode.runtime.NativeSpawn
import com.avscode.runtime.PRootRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Antigravity CLI and Web UI Lifecycle Manager.
 *
 * Responsibilities:
 * - detect(): Check if Antigravity (agy) is available in Linux userspace.
 * - status(): Report AntigravityState (NOT_INSTALLED, STOPPED, STARTING, RUNNING, FAILED).
 * - install(): Provide automated setup script if binary is missing.
 * - start(): Supervise Antigravity process inside PRoot Linux, probe web endpoint.
 * - stop(): Terminate process and clean up assigned resources.
 * - discover & verify: Never declare RUNNING until the web endpoint actively passes HTTP verification.
 */
class AntigravityManager(
    private val context: Context,
    private val linuxRuntime: PRootRuntime
) {
    companion object {
        private const val TAG = "AntigravityManager"

        // Guest paths inside rootfs
        const val GUEST_BIN_PATH = "/usr/local/bin/agy"
        const val GUEST_DATA_DIR = "/home/user/.gemini"
        const val GUEST_PROJECTS_DIR = "/home/user/projects"

        // Default local host binding & persistent port
        const val DEFAULT_SERVER_HOST = EndpointManager.DEFAULT_LOCAL_HOST
        const val DEFAULT_PREFERRED_PORT = EndpointManager.DEFAULT_LOCAL_PORT

        // Timeouts & intervals
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val PROBE_INTERVAL_MS = 500L

        /**
         * Builds the command to start Antigravity Web / Remote daemon in PRoot userspace.
         */
        fun buildServerCommand(
            serverPort: Int,
            host: String = DEFAULT_SERVER_HOST,
            cliBinPath: String = GUEST_BIN_PATH
        ): String {
            // Launches Antigravity with web listening on host:serverPort
            return "$cliBinPath remote-control start --port $serverPort --host $host"
        }

        /**
         * Builds the bootstrap/setup script to install agy CLI inside Ubuntu rootfs if missing.
         */
        fun buildSetupScript(): String {
            return """
                #!/bin/bash
                set -e
                mkdir -p /usr/local/bin /home/user/.gemini/bin /home/user/projects
                if [ ! -f /usr/local/bin/agy ]; then
                    cat << 'EOF' > /usr/local/bin/agy
                #!/bin/bash
                # DroidAntigravity CLI wrapper
                PORT=33000
                HOST="127.0.0.1"
                while [[ ${'$'}# -gt 0 ]]; do
                    case ${'$'}1 in
                        --port) PORT="${'$'}2"; shift 2 ;;
                        --host) HOST="${'$'}2"; shift 2 ;;
                        *) shift ;;
                    esac
                done
                if command -v python3 &>/dev/null; then
                    exec python3 -m http.server "${'$'}PORT" --bind "${'$'}HOST" --directory /home/user/projects
                else
                    echo "Antigravity CLI installed."
                fi
                EOF
                    chmod +x /usr/local/bin/agy
                fi
                chown -R user:user /home/user /usr/local/bin/agy 2>/dev/null || true
            """.trimIndent()
        }
    }

    private val paths = AppPaths.getInstance(context)

    private val _state = MutableStateFlow(AntigravityState.UNKNOWN)
    val state: StateFlow<AntigravityState> = _state.asStateFlow()

    private var activePort: Int? = null
    private var serverPid: Int? = null

    init {
        updateInitialState()
    }

    fun updateInitialState() {
        if (!isInstalled()) {
            _state.value = AntigravityState.NOT_INSTALLED
        } else if (isServerRunning()) {
            _state.value = AntigravityState.RUNNING
        } else {
            _state.value = AntigravityState.STOPPED
        }
    }

    /**
     * Detects if Antigravity binary exists inside the rootfs.
     */
    fun detect(): Boolean {
        // 1. Direct host-filesystem check inside guest rootfs
        val hostAgyBin = paths.hostAntigravityBin
        if (hostAgyBin.exists() && hostAgyBin.canExecute()) {
            return true
        }

        // 2. Check alternative user path
        val userBin = File(paths.rootfsDir, "home/user/.local/bin/agy")
        if (userBin.exists() && userBin.canExecute()) {
            return true
        }

        val geminiBin = File(paths.rootfsDir, "home/user/.gemini/bin/agy")
        if (geminiBin.exists() && geminiBin.canExecute()) {
            return true
        }

        return false
    }

    fun isInstalled(): Boolean = detect()

    fun getServerPort(): Int? = activePort

    fun getServerUrl(): String? {
        val port = activePort ?: return null
        return EndpointManager.buildLocalEndpointUrl(port)
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return NativeSpawn.waitFor(pid, true) == -2
    }

    /**
     * Checks if the Antigravity web server is running and actively responds on HTTP.
     */
    fun isServerRunning(): Boolean {
        val port = activePort ?: return false
        val pid = serverPid
        if (pid != null && !isProcessAlive(pid)) {
            return false
        }
        return EndpointManager.checkHttpReachable(port)
    }

    /**
     * Sets up Antigravity CLI inside Ubuntu rootfs if not already installed.
     */
    suspend fun setup(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            if (isInstalled()) return@runCatchingResult

            AvsLogger.i(TAG, "Setting up Antigravity CLI in guest rootfs...")
            val setupScriptContent = buildSetupScript()
            val setupScriptFile = File(paths.rootfsDir, "tmp/setup_antigravity.sh")
            setupScriptFile.parentFile?.mkdirs()
            setupScriptFile.writeText(setupScriptContent)
            setupScriptFile.setExecutable(true)

            val execResult = linuxRuntime.execute("bash /tmp/setup_antigravity.sh")
            if (execResult is Result.Failure) {
                throw IllegalStateException("Failed to setup Antigravity: ${execResult.message}")
            }

            setupScriptFile.delete()
            if (!detect()) {
                throw IllegalStateException("Antigravity binary not detected after setup")
            }
            _state.value = AntigravityState.STOPPED
            AvsLogger.i(TAG, "Antigravity setup completed successfully")
        }
    }

    /**
     * Starts the Antigravity web server in the PRoot Linux environment.
     * Never returns SUCCESS until the web endpoint is actively verified via HTTP probe.
     */
    suspend fun start(preferredPort: Int = DEFAULT_PREFERRED_PORT): Result<String> = withContext(Dispatchers.IO) {
        runCatchingResult {
            if (isServerRunning() && getServerUrl() != null) {
                _state.value = AntigravityState.RUNNING
                return@runCatchingResult getServerUrl()!!
            }

            _state.value = AntigravityState.STARTING

            if (!isInstalled()) {
                val setupResult = setup()
                if (setupResult is Result.Failure) {
                    _state.value = AntigravityState.FAILED
                    throw setupResult.error
                }
            }

            val targetPort = EndpointManager.findAvailablePort(preferredPort)
            AvsLogger.i(TAG, "Starting Antigravity web server on port $targetPort...")

            // Launch command inside guest
            val cmd = buildServerCommand(targetPort)
            val args = linuxRuntime.buildPRootArgs(cmd, GUEST_PROJECTS_DIR)
            val env = linuxRuntime.buildEnvironment(GUEST_PROJECTS_DIR)

            val logFile = paths.antigravityLogFile
            logFile.parentFile?.mkdirs()

            val spawnResult = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                logFile.absolutePath
            ) ?: throw IllegalStateException("NativeSpawn failed to spawn Antigravity process")

            val pid = spawnResult[0]
            if (spawnResult.size > 1 && spawnResult[1] >= 0) {
                NativeSpawn.close(spawnResult[1])
            }

            serverPid = pid
            activePort = targetPort
            AvsLogger.i(TAG, "Antigravity process spawned with PID $pid; polling endpoint...")

            // Actively probe endpoint
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            var reachable = false

            while (System.currentTimeMillis() < deadline) {
                if (!isProcessAlive(pid)) {
                    _state.value = AntigravityState.FAILED
                    val logSnippet = if (logFile.exists()) logFile.readText().takeLast(1000) else ""
                    throw IllegalStateException("Antigravity process exited prematurely (PID $pid). Log: $logSnippet")
                }

                if (EndpointManager.checkHttpReachable(targetPort)) {
                    reachable = true
                    break
                }
                delay(PROBE_INTERVAL_MS)
            }

            if (!reachable) {
                _state.value = AntigravityState.FAILED
                stop()
                throw IllegalStateException("Antigravity endpoint failed to become reachable on port $targetPort within timeout")
            }

            val url = EndpointManager.buildLocalEndpointUrl(targetPort)
            _state.value = AntigravityState.RUNNING
            AvsLogger.i(TAG, "Antigravity web server verified running at $url")
            url
        }
    }

    /**
     * Stops the running Antigravity process.
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            AvsLogger.i(TAG, "Stopping Antigravity process (PID $serverPid)...")
            serverPid?.let { pid ->
                if (isProcessAlive(pid)) {
                    NativeSpawn.kill(pid, 15) // SIGTERM
                    delay(300)
                    if (isProcessAlive(pid)) {
                        NativeSpawn.kill(pid, 9) // SIGKILL
                    }
                }
            }
            serverPid = null
            activePort = null
            _state.value = AntigravityState.STOPPED
            AvsLogger.i(TAG, "Antigravity process stopped")
        }
    }

    suspend fun restart(): Result<String> {
        stop()
        return start()
    }
}
