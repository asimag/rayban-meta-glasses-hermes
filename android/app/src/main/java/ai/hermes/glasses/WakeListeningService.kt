package ai.hermes.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps the app process in the foreground while voice wake-up is on, so standby survives a locked
 * phone or switching apps. The voice pipeline itself stays in [MainActivity]; this service only
 * holds the foreground state, a partial wake lock (the DAT Speech session must be restarted every
 * ~50 s, which needs a running CPU), and the notification's Stop action.
 *
 * Type connectedDevice: audio is captured and transcribed on the glasses side and reaches this
 * app as text over Bluetooth; the app never opens the phone microphone.
 */
class WakeListeningService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequests.tryEmit(Unit)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesGlasses:wake-standby")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        // The pipeline lives in MainActivity; if the process restarts, the user re-opens the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice wake-up", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Hermes listens for the wake phrase on your glasses"
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hermes)
            .setContentTitle("Listening for “Hey Hermes”")
            .setContentText("On your glasses. Say “Hey Hermes, stop listening” or tap Stop.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_wake"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ai.hermes.glasses.action.STOP_WAKE"
        private const val WAKE_LOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L

        private val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Emits when the wearer taps Stop in the notification. */
        val stopRequested: SharedFlow<Unit> = stopRequests.asSharedFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeListeningService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeListeningService::class.java))
        }
    }
}
