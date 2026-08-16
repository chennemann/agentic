package de.chennemann.agentic.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.R
import de.chennemann.agentic.domain.notifications.ThreadNotification
import de.chennemann.agentic.domain.notifications.ThreadNotificationKind
import de.chennemann.agentic.domain.notifications.ThreadNotificationPublisher
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteCodec

class AndroidThreadNotificationPublisher(private val context: Context) : ThreadNotificationPublisher {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val timestamps = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun publish(notification: ThreadNotification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val identity = "${notification.environmentId}:${notification.threadId}"
        if (notification.updatedAtEpochMillis <= timestamps.getLong(identity, -1)) return false

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val route = ShortcutRouteCodec.encode(notification.route)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(route), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            identity.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.agentic)
            .setContentTitle(notification.title)
            .setContentText(notification.headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.headline))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                when (notification.kind) {
                    ThreadNotificationKind.APPROVAL, ThreadNotificationKind.INPUT -> NotificationCompat.CATEGORY_MESSAGE
                    ThreadNotificationKind.COMPLETION -> NotificationCompat.CATEGORY_STATUS
                    ThreadNotificationKind.FAILURE -> NotificationCompat.CATEGORY_ERROR
                },
            )
        manager.notify(identity, NOTIFICATION_ID, notificationBuilder.build())
        timestamps.edit().putLong(identity, notification.updatedAtEpochMillis).apply()
        return true
    }

    private companion object {
        const val CHANNEL_ID = "agent_activity"
        const val NOTIFICATION_ID = 1
        const val PREFERENCES = "thread-notification-timestamps"
    }
}
