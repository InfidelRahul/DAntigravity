package com.droidantigravity.core

import android.content.Context
import java.io.File

/**
 * Single source of truth for Android-host and Linux-guest paths.
 *
 * Antigravity application data remains inside the persistent Linux HOME.
 * Android only owns the Linux rootfs, runtime cache and logs.
 */
class AppPaths(private val context: Context) {
    val filesDir: File get() = context.filesDir
    val cacheDir: File get() = context.cacheDir

    val rootfsDir: File get() = File(filesDir, "ubuntu-rootfs")
    val rootfsStagingDir: File get() = File(filesDir, "ubuntu-rootfs-staging")
    val rootfsInstallMarker: File get() = File(rootfsDir, ".installed")

    val prootTmpDir: File get() = File(cacheDir, "proot-tmp").apply { mkdirs() }
    val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    val nativeBinDir: File get() = File(filesDir, "native-bin").apply { mkdirs() }

    val guestHomePath: String = "/home/user"
    val guestProjectsPath: String = "$guestHomePath/projects"
    val hostProjectsDir: File get() = File(rootfsDir, "home/user/projects")

    /** The official installer normally places agy under ~/.local/bin. */
    val guestAntigravityBin: String = "/home/user/.local/bin/agy"
    val hostAntigravityBin: File get() = File(rootfsDir, "home/user/.local/bin/agy")
    val guestAntigravityDataDir: String = "/home/user/.gemini"
    val hostAntigravityDataDir: File get() = File(rootfsDir, "home/user/.gemini")

    val antigravityLogFile: File get() = File(cacheDir, "antigravity.log")
    val runtimeLogFile: File get() = File(cacheDir, "linux-runtime.log")

    /** Project-neutral guest bootstrap paths retained for rootfs compatibility. */
    val guestBootstrapScript: String = "/usr/local/lib/droidantigravity/bootstrap.sh"
    val hostBootstrapScript: File get() = File(rootfsDir, "usr/local/lib/droidantigravity/bootstrap.sh")
    val guestBootstrapMarker: String = "/var/lib/droidantigravity/bootstrapped"
    val hostBootstrapMarker: File get() = File(rootfsDir, "var/lib/droidantigravity/bootstrapped")

    val hostGuestTmpDir: File get() = File(rootfsDir, "tmp")

    companion object {
        @Volatile private var instance: AppPaths? = null

        fun getInstance(context: Context): AppPaths =
            instance ?: synchronized(this) {
                instance ?: AppPaths(context.applicationContext).also { instance = it }
            }
    }
}
