package com.droidantigravity.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.droidantigravity.DroidAntigravityApplication
import com.droidantigravity.MainActivity
import com.droidantigravity.R
import com.droidantigravity.RuntimeController
import com.droidantigravity.core.AppState
import com.droidantigravity.core.AvsLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that keeps the Linux userspace available while the app is in the background.
 * Delegates to the authoritative RuntimeController singleton.
 */
class LinuxRuntimeService : Service() {

    companion object {
        private const val TAG = "LinuxRuntimeService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.droidantigravity.runtime.START"
        const val ACTION_STOP = "com.droidantigravity.runtime.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LinuxRuntimeService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to start foreground service directly: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinuxRuntimeService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): LinuxRuntimeService = this@LinuxRuntimeService
    }

    override fun onCreate() {
        super.onCreate()
        AvsLogger.i(TAG, "LinuxRuntimeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundWithNotification()
                observeRuntimeState()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundWithNotification() {
        val notification = createNotification("DroidAntigravity Linux userspace active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeRuntimeState() {
        val controller = RuntimeController.getInstance(applicationContext)
        scope.launch {
            controller.appState.collectLatest { state ->
                val text = when (state) {
                    is AppState.NeedsStorageAccess -> "Storage access required"
                    is AppState.NotInstalled -> "Rootfs not installed"
                    is AppState.DownloadingRootfs -> "Downloading rootfs: ${(state.progress * 100).toInt()}%"
                    is AppState.ExtractingRootfs -> "Extracting rootfs: ${(state.progress * 100).toInt()}%"
                    is AppState.RootfsReady -> "Ubuntu rootfs ready"
                    is AppState.StartingLinux -> "Starting Linux userspace..."
                    is AppState.VerifyingLinux -> "Verifying Linux guest userspace..."
                    is AppState.LinuxReady -> "Linux guest userspace active"
                    is AppState.InstallingPackages -> state.status
                    is AppState.InstallingAntigravity -> "Installing Antigravity: ${(state.progress * 100).toInt()}%"
                    is AppState.AntigravityReady -> "Antigravity ready"
                    is AppState.StartingAntigravityServer -> state.status
                    is AppState.Ready -> "Active at ${state.url}"
                    is AppState.Stopping -> "Stopping Linux userspace..."
                    is AppState.AuthenticationRequired -> "Authentication required"
                    is AppState.RootfsFailed -> "Rootfs error: ${state.message}"
                    is AppState.LinuxFailed -> "Linux error: ${state.message}"
                    is AppState.PackageInstallFailed -> "Package error: ${state.message}"
                    is AppState.AntigravityFailed -> "Antigravity error: ${state.message}"
                    is AppState.Failed -> "Linux runtime error: ${state.message}"
                }
                updateNotification(text)
            }
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DroidAntigravityApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("DroidAntigravity")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "LinuxRuntimeService destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
