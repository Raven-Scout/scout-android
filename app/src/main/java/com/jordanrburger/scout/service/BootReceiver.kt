package com.jordanrburger.scout.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jordanrburger.scout.R
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.worker.ForwardWorker

/** Vault spec reboot-recovery rule: NotificationListener binding does not
 *  survive reboot if access was revoked — re-check and nudge. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        ForwardWorker.schedulePeriodic(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val granted = manager.isNotificationListenerAccessGranted(
            ComponentName(context, ScoutNotificationListenerService::class.java)
        )
        if (!granted) {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, ScoutApp.CHANNEL_STATUS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Re-enable Scout notification access")
                    .setContentText("Notification capture stopped after reboot — tap to re-grant.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}
