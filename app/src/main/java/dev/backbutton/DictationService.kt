package dev.backbutton

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Started explicitly from the visible setup screen, before any cross-app microphone use. */
class DictationService : Service() {
    companion object {
        const val STOP = "dev.backbutton.STOP_DICTATION"
        private const val CHANNEL = "dictation-ready"
        private const val NOTIFICATION = 1

        fun enable(context: Context) {
            context.startForegroundService(Intent(context, DictationService::class.java))
        }

        fun disable(context: Context) {
            context.stopService(Intent(context, DictationService::class.java))
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DictationAccessibilityService.current?.cancelTarget()
            dictation.interrupt("Recording stopped because the phone was locked.")
            dictation.audio.stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Dictation ready", NotificationManager.IMPORTANCE_LOW),
        )
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dictation.notice = "Allow microphone access, then enable cross-app dictation again."
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DictationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Dictation ready")
            .setContentText("Press your physical button to dictate into a text field.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (dictation.button.hasPermissions()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            startForeground(NOTIFICATION, notification, types)
            dictation.crossAppEnabled = true
            dictation.notice = null
            dictation.button.resume()
        } catch (_: SecurityException) {
            dictation.notice = "Open tap and enable cross-app dictation again."
            stopSelf()
        }
        // A killed service must be enabled again from a visible screen, never restarted into capture.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DictationAccessibilityService.current?.cancelTarget()
        dictation.crossAppEnabled = false
        dictation.interrupt("Recording stopped because cross-app dictation was turned off.")
        if (!dictation.activityVisible) dictation.button.pause()
        unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
