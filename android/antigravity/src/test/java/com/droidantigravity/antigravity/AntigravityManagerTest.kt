package com.droidantigravity.antigravity

import com.droidantigravity.core.AntigravityState
import com.droidantigravity.core.AppPaths
import com.droidantigravity.core.Result
import com.droidantigravity.runtime.PRootRuntime
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AntigravityManagerTest {

    private lateinit var tempDir: File
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var testPaths: AppPaths
    private lateinit var spawner: FakeProcessSpawner
    private lateinit var runtime: FakePRootRuntime
    private lateinit var manager: AntigravityManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("agy_mgr_test").toFile()
        filesDir = File(tempDir, "files").apply { mkdirs() }
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        testPaths = AppPaths.forTesting(filesDir, cacheDir)

        // Mark rootfs as installed and create agy executable in rootfs
        testPaths.rootfsInstallMarker.parentFile?.mkdirs()
        testPaths.rootfsInstallMarker.createNewFile()
        testPaths.hostAntigravityBin.parentFile?.mkdirs()
        testPaths.hostAntigravityBin.createNewFile()

        spawner = FakeProcessSpawner()
        runtime = FakePRootRuntime(testPaths)
        manager = AntigravityManager(runtime, spawner, testPaths)
    }

    @After
    fun tearDown() {
        manager.stop()
        AppPaths.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun test1_remoteControlUrlPatternMatchesStandardUrl() {
        val raw = "Open https://antigravity.google.com/r/c386be96-0c9f-4318-97be-fb1ea4310d51-v2 on another device to take over."
        val url = RemoteControlUrlParser.parseUrl(raw)
        assertEquals("https://antigravity.google.com/r/c386be96-0c9f-4318-97be-fb1ea4310d51-v2", url)
    }

    @Test
    fun test2_remoteControlUrlPatternMatchesQueryParamsAndFragment() {
        val raw = "Visit https://antigravity.google.com/r/instance-123?p=c%2Fconversation-456&mode=remote#session to connect"
        val url = RemoteControlUrlParser.parseUrl(raw)
        assertEquals("https://antigravity.google.com/r/instance-123?p=c%2Fconversation-456&mode=remote#session", url)
    }

    @Test
    fun test3_remoteControlUrlParserTrimsTrailingPunctuation() {
        val base = "https://antigravity.google.com/r/session-abc"
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: $base."))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: $base,"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: $base;"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: $base:"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: ($base)"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: [$base]"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: {$base}"))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: \"$base\""))
        assertEquals(base, RemoteControlUrlParser.parseUrl("URL: '$base'"))
    }

    @Test
    fun test4_remoteControlUrlParserStripsAnsiEscapeSequences() {
        val ansiText = "\u001B[32mRemote control active:\u001B[0m \u001B[1;4mhttps://antigravity.google.com/r/instance-ansi\u001B[0m\r\n"
        val url = RemoteControlUrlParser.parseUrl(ansiText)
        assertEquals("https://antigravity.google.com/r/instance-ansi", url)
    }

    @Test
    fun test5_startupClassifierDetectsWorkspaceTrustPrompt() {
        val prompt = "Do you trust the contents of this project? Antigravity CLI requires permission to read, edit, and execute files here.\n> Yes, I trust this folder / No, exit"
        assertTrue(StartupOutputClassifier.isTrustPrompt(prompt))
        assertFalse(StartupOutputClassifier.isTrustPrompt("Starting Antigravity..."))
    }

    @Test
    fun test6_startupClassifierDetectsAuthenticationRequired() {
        val text1 = "Error: Not authenticated. Run 'agy auth login' to sign in."
        val text2 = "Authentication required: please log in with Google to continue."
        val text3 = "Authentication failed: invalid session"
        val text4 = "error getting token source: You are not logged into Antigravity."
        val text5 = "Error: unauthenticated request"
        val text6 = "Fatal: no authentication methods available"
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text1))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text2))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text3))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text4))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text5))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(text6))

        // The actual first-launch CLI prompt is an actionable authentication
        // state, because DAntigravity must expose the still-running PTY to the
        // user instead of waiting for a URL that cannot exist yet.
        val infoPrompt1 = "Welcome to the Antigravity CLI. You are currently not signed in."
        val infoPrompt2 = "Signing in... Select login method:"
        val infoPrompt3 = "[RemoteControl] Staying disconnected: remote-control-setting-enabled Mendel flag is off"
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(infoPrompt1))
        assertTrue(StartupOutputClassifier.isAuthenticationRequired(infoPrompt2))
        assertFalse(StartupOutputClassifier.isAuthenticationRequired(infoPrompt3))

        assertEquals(AntigravityStartupError.AUTHENTICATION_REQUIRED, StartupOutputClassifier.classifyError(text1, 1))
    }

    @Test
    fun test6b_firstLaunchCliPromptIsAuthenticationRequired() {
        val prompt = """
            Welcome to the Antigravity CLI. You are currently not signed in.

            Signing in... Select login method:
            > 1. Google OAuth
              2. Use a Google Cloud project
        """.trimIndent()

        assertTrue(StartupOutputClassifier.isAuthenticationRequired(prompt))
        assertEquals(
            AntigravityStartupError.AUTH_REQUIRED,
            StartupOutputClassifier.classifyError(prompt, null)
        )
    }

    @Test
    fun test7_startupClassifierDetectsRemoteControlUnavailable() {
        val text1 = "Error: unknown flag: --remote-control"
        val text2 = "Remote control is not supported on this platform"
        val text3 = "Remote control is disabled for this user"
        assertTrue(StartupOutputClassifier.isRemoteControlUnavailable(text1))
        assertTrue(StartupOutputClassifier.isRemoteControlUnavailable(text2))
        assertTrue(StartupOutputClassifier.isRemoteControlUnavailable(text3))
        assertEquals(AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE, StartupOutputClassifier.classifyError(text1, 1))
    }

    @Test
    fun test8_startupClassifierDetectsRemoteControlStartFailed() {
        val text1 = "bubbletea: error opening TTY: open /dev/tty: no such device or address"
        val text2 = "failed to start remote control: connection refused"
        assertTrue(StartupOutputClassifier.isRemoteControlStartFailed(text1))
        assertTrue(StartupOutputClassifier.isRemoteControlStartFailed(text2))
        assertEquals(AntigravityStartupError.REMOTE_CONTROL_START_FAILED, StartupOutputClassifier.classifyError(text1, 1))
    }

    @Test
    fun test9_cliExitedBeforeRemoteControlClassified() = runBlocking {
        spawner.waitForExitCode = 1
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Process failed without specific message\n")
        }

        val result = manager.start(startupTimeoutMs = 1000)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.CLI_EXITED_BEFORE_REMOTE_CONTROL, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.FAILED, manager.state)
    }

    @Test
    fun test10_authenticationRequiredClassifiesErrorAndState() = runBlocking {
        spawner.waitForExitCode = 1
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Error: Not authenticated. Please run 'agy auth login'\n")
        }

        val result = manager.start(startupTimeoutMs = 1000)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.AUTHENTICATION_REQUIRED, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.AUTHENTICATION_REQUIRED, manager.state)
    }

    @Test
    fun test11_remoteControlUnavailableClassifiesErrorAndState() = runBlocking {
        spawner.waitForExitCode = 1
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Error: flag provided but not defined: --remote-control\n")
        }

        val result = manager.start(startupTimeoutMs = 1000)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.REMOTE_CONTROL_UNAVAILABLE, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.FAILED, manager.state)
    }

    @Test
    fun test12_startupTimeoutWaitingForUrl() = runBlocking {
        spawner.waitForExitCode = -2 // Still running
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Initializing TUI and reverse tunnel...\n")
        }

        val result = manager.start(startupTimeoutMs = 300)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.STARTUP_TIMEOUT, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.FAILED, manager.state)
    }

    @Test
    fun test13_startupCancellationCleansUpProcess() = runBlocking {
        spawner.waitForExitCode = -2 // Still running
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Waiting for network connection...\n")
        }

        val job = launch {
            manager.start(startupTimeoutMs = 10_000)
        }
        delay(100)
        job.cancelAndJoin()

        // Verify PID was killed and stdin fd closed
        assertTrue(spawner.killedPids.any { it.first == spawner.nextPid && it.second == 15 })
        assertTrue(spawner.killedPids.any { it.first == spawner.nextPid && it.second == 9 })
        assertTrue(spawner.closedFds.contains(spawner.nextFd))
        assertNull(manager.processId())
    }

    @Test
    fun test14_processCleanupOnFailure() = runBlocking {
        spawner.shouldFailSpawn = true

        val result = manager.start(startupTimeoutMs = 500)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.CLI_START_FAILED, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.FAILED, manager.state)
        assertNull(manager.processId())
    }

    @Test
    fun test15_credentialRedactionInLogs() {
        val raw = "Header Authorization: Bearer ya29.a0AfH6SMB123456 with Cookie: session_token=secret123 and api_key=sk-998877 in https://antigravity.google.com/r/my-session-id"
        val redacted = AntigravityLogRedactor.redact(raw)

        assertFalse(redacted.contains("ya29.a0AfH6SMB123456"))
        assertFalse(redacted.contains("secret123"))
        assertFalse(redacted.contains("sk-998877"))
        assertTrue(redacted.contains("<redacted>"))
        assertTrue(redacted.contains("https://antigravity.google.com/r/my-session-id"))
        assertTrue(redacted.contains("antigravity.google.com"))
    }

    @Test
    fun test16_isInstalledChecksMarkerAndPaths() {
        // Initially marker and binary exist
        assertTrue(manager.isInstalled())

        // Remove marker
        testPaths.rootfsInstallMarker.delete()
        assertFalse(manager.isInstalled())
        assertEquals(AntigravityState.NOT_INSTALLED, manager.state)

        // Restore marker, remove binary
        testPaths.rootfsInstallMarker.createNewFile()
        testPaths.hostAntigravityBin.delete()
        runtime.executeResult = Result.Success("")
        assertFalse(manager.isInstalled())

        // Now runtime command returns path
        runtime.executeResult = Result.Success("/usr/local/bin/agy\n")
        assertTrue(manager.isInstalled())
    }

    @Test
    fun test17_monitorProcessDetectsExit() = runBlocking {
        spawner.waitForExitCode = -2
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Open https://antigravity.google.com/r/session-monitor-123 on another device to take over.\n")
        }

        val result = manager.start(startupTimeoutMs = 1000)
        assertTrue(result.isSuccess)
        assertEquals(AntigravityState.RUNNING, manager.state)
        assertEquals("https://antigravity.google.com/r/session-monitor-123", manager.currentRemoteControlUrl())
        assertEquals(spawner.nextPid, manager.processId())

        // Process exits
        spawner.waitForExitCode = 0
        delay(500)

        // Monitor job should have updated state
        assertEquals(AntigravityState.STOPPED, manager.state)
        assertNull(manager.currentRemoteControlUrl())
        assertNull(manager.processId())
    }

    @Test
    fun test18_trustPromptAutoResponse() = runBlocking {
        spawner.waitForExitCode = -2

        // Start in background, write trust prompt, then URL
        val job = async {
            manager.start(startupTimeoutMs = 2000)
        }

        delay(50)
        testPaths.antigravityLogFile.writeText("Do you trust the contents of this project? Antigravity CLI requires permission to read, edit, and execute files here.\n> Yes, I trust this folder / No, exit\n")

        delay(150)
        // Verify manager auto-responded with newline
        assertTrue(spawner.writtenStrings.contains("\r\n"))

        // Now CLI proceeds to write URL
        testPaths.antigravityLogFile.appendText("Open https://antigravity.google.com/r/trusted-session-456 on another device.\n")

        val result = job.await()
        assertTrue(result.isSuccess)
        assertEquals("https://antigravity.google.com/r/trusted-session-456", result.getOrNull())
        assertEquals(AntigravityState.RUNNING, manager.state)
    }

    @Test
    fun test21_unauthenticatedRunningCliDoesNotAbortEarly() = runBlocking {
        spawner.waitForExitCode = -2 // Process is still running (e.g. in browser auth flow)
        spawner.onSpawn = { logPath ->
            File(logPath).writeText("Authentication required: please log in with Google to continue.\n")
        }

        val startTime = System.currentTimeMillis()
        val result = manager.start(startupTimeoutMs = 400)
        val elapsed = System.currentTimeMillis() - startTime

        // Authentication is an actionable state. Startup must return it
        // immediately while keeping the CLI/PTy alive for user interaction.
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AntigravityStartupException)
        assertEquals(AntigravityStartupError.AUTH_REQUIRED, (ex as AntigravityStartupException).error)
        assertEquals(AntigravityState.AUTHENTICATION_REQUIRED, manager.state)
        assertEquals(spawner.nextPid, manager.processId())
        assertTrue("Authentication should be surfaced promptly, took ${elapsed}ms", elapsed < 300)
    }

    @Test
    fun test22_officialAuthFlowProducesUrlSuccessfully() = runBlocking {
        spawner.waitForExitCode = -2
        val job = async {
            manager.start(startupTimeoutMs = 2000)
        }

        delay(50)
        testPaths.antigravityLogFile.writeText("Authentication required: please sign in with Google to continue.\n")
        delay(100)
        testPaths.antigravityLogFile.appendText("Open https://antigravity.google.com/r/authenticated-session-789 on another device.\n")

        val result = job.await()
        assertTrue(result.isSuccess)
        assertEquals("https://antigravity.google.com/r/authenticated-session-789", result.getOrNull())
        assertEquals(AntigravityState.RUNNING, manager.state)
    }

    @Test
    fun test23_officialMultilineOutputWithV2UrlParsedSuccessfully() {
        val officialOutput = """
             Ga=q,f=32,s=1,v=1,i=31;AAAAAA==
            > /remote-control
              ⎿  Remote control on for this session.
             Hostname: localhost-mighty-shard
             Open
             https://antigravity.google.com/r/e61732b3-ef5d-4dfc-87b9-dfe262a4ef67-v2 on
             another device to take over.

            ────────────────────────────────────────────────────────────────────────────────
            >
            ────────────────────────────────────────────────────────────────────────────────
        """.trimIndent()

        val parsedUrl = RemoteControlUrlParser.parseUrl(officialOutput)
        assertNotNull(parsedUrl)
        assertEquals("https://antigravity.google.com/r/e61732b3-ef5d-4dfc-87b9-dfe262a4ef67-v2", parsedUrl)
    }
}

class FakeProcessSpawner : ProcessSpawner {
    var nextPid = 1234
    var nextFd = 42
    var spawnedArgv: Array<String>? = null
    var spawnedEnvp: Array<String>? = null
    var spawnedCwd: String? = null
    var spawnedOutputPath: String? = null
    var onSpawn: ((outputPath: String) -> Unit)? = null
    val writtenBytes = mutableListOf<ByteArray>()
    val writtenStrings = mutableListOf<String>()
    var waitForExitCode = -2 // Default: running
    val killedPids = mutableListOf<Pair<Int, Int>>() // pid, signal
    val closedFds = mutableListOf<Int>()
    var shouldFailSpawn = false

    override fun spawnPty(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String,
        cols: Int,
        rows: Int
    ): IntArray? {
        if (shouldFailSpawn) return null
        spawnedArgv = argv
        spawnedEnvp = envp
        spawnedCwd = cwd
        spawnedOutputPath = outputPath
        onSpawn?.invoke(outputPath)
        return intArrayOf(nextPid, nextFd)
    }

    override fun write(fd: Int, data: ByteArray): Int {
        writtenBytes.add(data)
        writtenStrings.add(String(data, Charsets.UTF_8))
        return data.size
    }

    override fun waitFor(pid: Int, noHang: Boolean): Int {
        return waitForExitCode
    }

    override fun kill(pid: Int, signal: Int): Int {
        killedPids.add(pid to signal)
        return 0
    }

    override fun close(fd: Int): Int {
        closedFds.add(fd)
        return 0
    }
}

class FakePRootRuntime(paths: AppPaths) : PRootRuntime(paths) {
    var executeResult: Result<String> = Result.Success("/home/user/.local/bin/agy")
    var executeStreamingResult: Result<Int> = Result.Success(0)

    override suspend fun execute(command: String): Result<String> = executeResult

    override suspend fun executeStreaming(
        command: String,
        workingDir: String,
        onOutput: (String) -> Unit
    ): Result<Int> = executeStreamingResult

    override fun buildPRootArgs(guestCommand: String, workingDir: String): List<String> =
        listOf("proot", "-w", workingDir, "/bin/bash", "-lc", guestCommand)

    override fun buildEnvironment(homeDir: String, extraEnv: Map<String, String>): Array<String> =
        extraEnv.map { "${it.key}=${it.value}" }.toTypedArray()
}
