package com.droidantigravity.core

/**
 * Runtime state machine for the Linux environment.
 */
enum class RuntimeState {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED;
    
    val isInstalled: Boolean get() = this != NOT_INSTALLED && this != INSTALLING && this != FAILED
    val isRunning: Boolean get() = this == RUNNING
    val isReady: Boolean get() = this == READY || this == RUNNING
}

/**
 * Runtime state for the Local LinuxDroid (PRoot) environment.
 */
enum class LocalRuntimeState {
    NEW,
    NOT_INSTALLED,
    INSTALLING,
    STOPPED,
    STARTING,
    READY,
    ANTIGRAVITY_STARTING,
    WEB_READY,
    RUNNING,
    FAILED;

    val isInstalled: Boolean get() = this != NOT_INSTALLED && this != INSTALLING && this != NEW
    val isRunning: Boolean get() = this == RUNNING || this == WEB_READY
}

/**
 * GitHub Codespace states matching the official GitHub REST API v2022-11-28.
 */
enum class CodespaceState {
    UNKNOWN,
    AVAILABLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    SHUTDOWN,
    FAILED,
    EXPORTING,
    UPDATING,
    REBUILDING;

    val isRunning: Boolean get() = this == RUNNING || this == AVAILABLE
    val isStopped: Boolean get() = this == STOPPED || this == SHUTDOWN
    val isBusy: Boolean get() = this == STARTING || this == STOPPING || this == UPDATING || this == REBUILDING
}

/**
 * Antigravity lifecycle state.
 */
enum class AntigravityState {
    UNKNOWN,
    NOT_INSTALLED,
    STOPPED,
    STARTING,
    RUNNING,
    AUTHENTICATION_REQUIRED,
    FAILED;

    val isRunning: Boolean get() = this == RUNNING
}

/**
 * High-level application state observed by UI.
 */
sealed class AppState {
    object NeedsStorageAccess : AppState()
    data class DownloadingRootfs(val progress: Float, val status: String) : AppState()
    data class ExtractingRootfs(val progress: Float, val status: String) : AppState()
    object RootfsReady : AppState()
    object StartingLinux : AppState()
    object VerifyingLinux : AppState()
    object LinuxReady : AppState()
    data class InstallingPackages(val status: String) : AppState()

    // Antigravity states
    data class InstallingAntigravity(val progress: Float, val status: String) : AppState()
    object AntigravityReady : AppState()
    data class StartingAntigravityServer(val status: String) : AppState()
    data class Ready(val url: String, val title: String = "DroidAntigravity") : AppState()
    object Stopping : AppState()

    // Specific stage failures
    data class RootfsFailed(val message: String, val throwable: Throwable? = null) : AppState()
    data class LinuxFailed(val message: String, val throwable: Throwable? = null) : AppState()
    data class PackageInstallFailed(val message: String, val throwable: Throwable? = null) : AppState()
    data class AntigravityFailed(val message: String, val throwable: Throwable? = null) : AppState()

    // General states
    object NotInstalled : AppState()
    data class Failed(val message: String, val throwable: Throwable? = null) : AppState()

    val isReady: Boolean get() = this is Ready
    val isFailed: Boolean get() = this is Failed || this is RootfsFailed || this is LinuxFailed || this is PackageInstallFailed || this is AntigravityFailed
    val canAccessCli: Boolean get() = this is LinuxReady || this is InstallingPackages || this is InstallingAntigravity || this is AntigravityReady || this is StartingAntigravityServer || this is Ready || this is AntigravityFailed
}

/**
 * Core result type for operations that can fail.
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Failure(val error: Throwable, val message: String? = null) : Result<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error
    }

    fun getOrDefault(defaultValue: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> defaultValue
    }
    
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }
    
    fun exceptionOrNull(message: String?): String? = when (this) {
        is Success -> null
        is Failure -> message ?: error.message ?: error.javaClass.simpleName
    }
}

/**
 * Extension function to run a block and capture any exceptions.
 */
inline fun <T> runCatchingResult(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Throwable) {
        Result.Failure(e, e.message)
    }
}

/**
 * Extension to convert from kotlin.Result to our custom Result.
 */
fun <T> kotlin.Result<T>.toAvsResult(): Result<T> = fold(
    onSuccess = { Result.Success(it) },
    onFailure = { Result.Failure(it, it.message) }
)
