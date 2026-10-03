package com.opus.airvia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that owns a cast session: the MediaProjection
 * (user consent, one per session), the device-audio capture, and the
 * connection between them and [CastEngine]. Casting keeps running with
 * the app backgrounded or the screen off; the notification offers Stop.
 */
class CastService : Service() {
    companion object {
        private const val ACTION_START = "com.opus.airvia.START"
        private const val ACTION_STOP = "com.opus.airvia.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NAME = "name"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "airvia_cast"

        fun startIntent(
            ctx: Context,
            resultCode: Int,
            data: Intent,
            speaker: Speaker,
        ): Intent = Intent(ctx, CastService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_DATA, data)
            putExtra(EXTRA_HOST, speaker.host)
            putExtra(EXTRA_PORT, speaker.port)
            putExtra(EXTRA_NAME, speaker.name)
        }

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, CastService::class.java).apply { action = ACTION_STOP }
    }

    private var projection: MediaProjection? = null
    private var capture: CaptureSource? = null
    private var shuttingDown = false

    private val engineListener: (CastEngine.State) -> Unit = { st ->
        when (st) {
            CastEngine.State.STREAMING -> updateNotification()
            CastEngine.State.ERROR -> {
                LogBus.log("[service] cast error: ${CastEngine.lastError}")
                shutdown()
            }
            CastEngine.State.IDLE -> shutdown()
            CastEngine.State.CONNECTING -> updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // A new cast replaces any running one (single capture, single session).
        teardownCast()
        shuttingDown = false
        val speaker = Speaker(
            intent.getStringExtra(EXTRA_NAME) ?: "Speaker",
            intent.getStringExtra(EXTRA_HOST) ?: return,
            intent.getIntExtra(EXTRA_PORT, 7000),
        )
        startForeground(
            NOTIF_ID,
            buildNotification(speaker.name, "Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: run {
                LogBus.log("[service] missing capture consent data")
                shutdown()
                return
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
            // Android 14 requires a registered callback before capture.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    LogBus.log("[service] projection stopped by system")
                    shutdown()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj

            val cap = CaptureSource(proj)
            cap.start()
            capture = cap

            VolumeKeys.init(this)
            VolumeKeys.stopHandler = { shutdown() }
            VolumeKeys.setCasting(true)

            CastEngine.addListener(engineListener)
            CastEngine.start(speaker, cap)
        } catch (e: Exception) {
            LogBus.log("[service] start failed: ${e.message}")
            shutdown()
        }
    }

    private fun buildNotification(name: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Airvia → $name")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Stop", stop,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val sp = CastEngine.speaker ?: return
        val text = when (CastEngine.state) {
            CastEngine.State.STREAMING -> "Streaming all device audio"
            CastEngine.State.CONNECTING -> "Connecting…"
            else -> "Casting"
        }
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(sp.name, text))
        } catch (_: Exception) {
        }
    }

    private fun teardownCast() {
        try {
            CastEngine.removeListener(engineListener)
        } catch (_: Exception) {
        }
        CastEngine.stop()
        try {
            capture?.stop()
        } catch (_: Exception) {
        }
        capture = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        VolumeKeys.stopHandler = null
        VolumeKeys.setCasting(false)
    }

    @Synchronized
    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        teardownCast()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
