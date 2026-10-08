package com.emerge.app.audio

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** MediaProjection is used only for AudioPlaybackCapture. No display or microphone is created. */
class ProjectionAudioService : Service() {
    private val runtime by lazy { AudioRuntime.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var sessionId: String? = null
    private var closing = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!closing) {
                sessionId?.let { runtime.projectionEnded(it) }
                finishService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用声音转写", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示应用声音转写状态，并提供暂停和停止控制"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> if (sessionId == null) stopSelf(startId) else runtime.pausePlayback()
            ACTION_RESUME -> if (sessionId == null) stopSelf(startId) else runtime.resumePlayback()
            ACTION_STOP -> if (sessionId == null) stopSelf(startId) else runtime.stopPlayback()
            ACTION_REFRESH -> if (sessionId != null) updateNotification() else stopSelf(startId)
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SESSION)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                if (id == null || packageName == null || sessionId != null) { stopSelf(startId); return START_NOT_STICKY }
                sessionId = id
                try {
                    startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    } ?: error("缺少系统的声音记录授权，请重新开始")
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    val created = manager.getMediaProjection(resultCode, data) ?: error("系统的声音记录授权已失效")
                    projection = created
                    created.registerCallback(callback, handler)
                    runtime.attachPlayback(id, packageName, created) { handler.post { finishService() } }
                } catch (error: Throwable) {
                    runtime.failPlayback(id, error.message?.take(200) ?: "无法开始记录声音，请重新授权")
                    finishService()
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun notification(): Notification {
        val paused = runtime.state.value.isPaused
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val action = if (paused) ACTION_RESUME else ACTION_PAUSE
        fun pending(actionName: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, ProjectionAudioService::class.java).setAction(actionName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "破茧 · 声音转写已暂停" else "破茧 · 正在转写应用声音")
            .setContentText("仅处理所选应用的声音，最长 5 分钟")
            .setContentIntent(content)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停", pending(action, 1)).build())
            .addAction(Notification.Action.Builder(null, "停止", pending(ACTION_STOP, 2)).build())
            .build()
    }

    private fun finishService() {
        if (closing) return
        closing = true
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runtime.stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runtime.serviceDestroyed(sessionId)
        closing = true
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.emerge.audio.START"
        const val ACTION_PAUSE = "com.emerge.audio.PAUSE"
        const val ACTION_RESUME = "com.emerge.audio.RESUME"
        const val ACTION_STOP = "com.emerge.audio.STOP"
        private const val ACTION_REFRESH = "com.emerge.audio.REFRESH"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_PACKAGE = "source_package"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "emerge_audio_capture"
        private const val NOTIFICATION = 4301
        internal fun refreshNotification(context: Context) {
            context.startService(Intent(context, ProjectionAudioService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
