package com.jordanrburger.scout.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jordanrburger.scout.R
import com.jordanrburger.scout.ScoutApp

/**
 * Scout → phone push (spec §5). Dormant until app/google-services.json exists;
 * without it this service is never bound because FirebaseApp never initializes.
 *
 * Expected data payload from the engine sender:
 *   {"title": "...", "body": "...", "prefix": "QPD1"?, "urgency": "high"|"normal"}
 */
class ScoutMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Scout"
        val body = data["body"] ?: message.notification?.body ?: ""
        val prefix = data["prefix"]
        val urgent = data["urgency"] == "high"

        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("scout://action-items/${prefix.orEmpty()}"))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val channel = if (urgent) ScoutApp.CHANNEL_ALERTS else ScoutApp.CHANNEL_STATUS
        getSystemService(NotificationManager::class.java).notify(
            (message.messageId ?: body).hashCode(),
            NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(
                    if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
                )
                .build(),
        )
    }

    override fun onNewToken(token: String) {
        // Topic-based delivery (spec §4): no per-device token registry needed.
    }
}
