package com.droidantigravity.runtime

import com.droidantigravity.core.AvsLogger
import com.droidantigravity.core.Result
import com.droidantigravity.core.runCatchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Linux security/session prerequisites used by the official Antigravity CLI.
 *
 * This deliberately does not copy credentials into Android. It verifies that
 * the Ubuntu userspace contains a usable D-Bus + Secret Service stack.
 */
class LinuxSecurityServices(private val linuxRuntime: PRootRuntime) {
    companion object {
        private const val TAG = "LinuxSecurityServices"
        private const val PACKAGES = "dbus dbus-user-session gnome-keyring libsecret-1-0 libsecret-tools"
    }

    suspend fun ensureInstalled(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val check = linuxRuntime.execute(
                "command -v dbus-run-session >/dev/null && " +
                    "command -v gnome-keyring-daemon >/dev/null && " +
                    "command -v dbus-send >/dev/null"
            )
            if (check.isSuccess) return@runCatchingResult Unit

            AvsLogger.i(TAG, "Installing D-Bus and Secret Service packages")
            linuxRuntime.executeStreaming(
                "export DEBIAN_FRONTEND=noninteractive; " +
                    "apt-get update -qq && apt-get install -y --no-install-recommends $PACKAGES"
            ) { output ->
                AvsLogger.d(TAG, output.trim())
            }.getOrThrow()
            Unit
        }
    }

    /**
     * Starts a temporary D-Bus session and Secret Service daemon and performs
     * a real D-Bus IPC call. The session is intentionally scoped to this check;
     * Antigravity gets its own long-lived dbus-run-session around its CLI process.
     */
    suspend fun verifySecretService(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val command = """
                set -e
                dbus-run-session -- sh -lc '
                    eval "$(gnome-keyring-daemon --start --components=secrets)"
                    dbus-send --session --dest=org.freedesktop.secrets \
                      --type=method_call --print-reply \
                      /org/freedesktop/secrets \
                      org.freedesktop.DBus.Introspectable.Introspect >/dev/null
                '
            """.trimIndent()
            linuxRuntime.executeStreaming(command) { output ->
                AvsLogger.d(TAG, output.trim())
            }.getOrThrow().also { code ->
                check(code == 0) { "Secret Service D-Bus verification failed with exit code $code" }
            }
            Unit
        }
    }
}
