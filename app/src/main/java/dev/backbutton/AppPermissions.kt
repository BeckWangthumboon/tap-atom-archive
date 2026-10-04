package dev.backbutton

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings

/** Platform settings, independent of whether the dictation service is currently connected. */
internal data class AppPermissions(
    val microphone: Boolean,
    val textInsertion: Boolean,
    val notifications: Boolean,
) {
    companion object {
        fun read(context: Context): AppPermissions {
            fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val ownService = ComponentName(context, DictationAccessibilityService::class.java)
            val textInsertion = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').mapNotNull(ComponentName::unflattenFromString).any { it == ownService }
            val notifications = context.getSystemService(NotificationManager::class.java)
            val channel = notifications.getNotificationChannel(DictationService.CHANNEL)
            return AppPermissions(
                microphone = granted(Manifest.permission.RECORD_AUDIO),
                textInsertion = textInsertion,
                notifications = notifications.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE,
            )
        }
    }
}
