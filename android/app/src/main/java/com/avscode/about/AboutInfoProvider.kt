package com.avscode.about

import android.content.Context
import android.os.Build
import com.avscode.BuildConfig
import com.avscode.antigravity.AntigravityManager
import com.avscode.core.AppPaths
import java.io.File

data class AboutInfo(
    val appVersion: String,
    val linuxDistro: String,
    val antigravityVersion: String,
    val androidVersion: String,
    val sdkInt: Int,
    val deviceModel: String,
    val cpuArch: String,
    val kernelVersion: String,
    val projectsPath: String,
    val rootfsPath: String
) {
    val vsCodeVersion: String get() = antigravityVersion
}

class AboutInfoProvider(private val context: Context) {

    private val appPaths = AppPaths.getInstance(context)

    fun getAboutInfo(dynamicVersion: String? = null): AboutInfo {
        val distro = resolveLinuxDistro()
        val agyVer = dynamicVersion ?: resolveAntigravityVersion()
        val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "arm64-v8a"
        val kernel = System.getProperty("os.version") ?: "Linux"

        return AboutInfo(
            appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            linuxDistro = distro,
            antigravityVersion = agyVer,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = device,
            cpuArch = cpuArch,
            kernelVersion = kernel,
            projectsPath = appPaths.guestProjectsPath,
            rootfsPath = appPaths.rootfsDir.absolutePath
        )
    }

    suspend fun resolveDynamicAntigravityVersion(manager: AntigravityManager): String {
        if (!manager.isInstalled()) {
            return "Not installed"
        }
        return "Antigravity CLI (ARM64)"
    }

    private fun resolveLinuxDistro(): String {
        val osReleaseFile = File(appPaths.rootfsDir, "etc/os-release")
        if (osReleaseFile.exists() && osReleaseFile.canRead()) {
            try {
                var prettyName = ""
                osReleaseFile.forEachLine { line ->
                    if (line.startsWith("PRETTY_NAME=")) {
                        prettyName = line.removePrefix("PRETTY_NAME=").trim('"', '\'')
                    }
                }
                if (prettyName.isNotEmpty()) {
                    return "$prettyName (ARM64)"
                }
            } catch (ignored: Exception) {
            }
        }
        return if (appPaths.rootfsInstallMarker.exists()) "Ubuntu 26.04 LTS (ARM64)" else "Not installed"
    }

    private fun resolveAntigravityVersion(): String {
        val cliBin = appPaths.hostAntigravityBin
        if (!cliBin.exists()) {
            return "Not installed"
        }
        return "Antigravity CLI (ARM64)"
    }
}
