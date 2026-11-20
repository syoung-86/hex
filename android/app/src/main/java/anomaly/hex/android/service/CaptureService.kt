package anomaly.hex.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import anomaly.hex.android.DebugLog
import anomaly.hex.android.MainActivity
import anomaly.hex.android.R
import anomaly.hex.android.audio.Recorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The M0 experiment. Android 12+ restricts starting a microphone foreground
 * service from the background, so this one is started from the visible app and
 * then kept alive. Every later trigger should record inside it rather than
 * starting a new service.
 */
class CaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        _running.value = true
        DebugLog.log("Service", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Recorder.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        DebugLog.log("Service", "foreground with microphone type")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Recorder.stop()
        _running.value = false
        DebugLog.log("Service", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hex)
            .setContentTitle("Hex is ready")
            .setContentText("Microphone foreground service (M0)")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Dictation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while Hex can record"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hex.ready"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "anomaly.hex.android.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java),
            )
            true
        } catch (error: Exception) {
            DebugLog.log(
                "Service",
                "start failed: ${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, CaptureService::class.java).setAction(ACTION_STOP),
                )
            } catch (error: Exception) {
                DebugLog.log(
                    "Service",
                    "stop failed: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
    }
}
