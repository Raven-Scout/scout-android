package com.jordanrburger.scout.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.data.db.CapturedNotification
import com.jordanrburger.scout.domain.CaptureFilter
import com.jordanrburger.scout.worker.ForwardWorker
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persist-then-forward (vault spec hard rule): every event is written to Room
 * first; the network forward happens later in ForwardWorker. No network here.
 */
class ScoutNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn, removed = false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = capture(sbn, removed = true)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun capture(sbn: StatusBarNotification, removed: Boolean) {
        val container = ScoutApp.container(applicationContext)
        val allowed = CaptureFilter.shouldCapture(
            pkg = sbn.packageName,
            ownPkg = packageName,
            enabled = container.settings.captureEnabled.value,
            allowed = container.settings.allowedPackages.value,
        )
        if (!allowed) return

        val extras = sbn.notification.extras
        val entity = CapturedNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            postTimeMs = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            category = sbn.notification.category,
            isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            removed = removed,
            capturedAt = Instant.now().toString(),
        )
        scope.launch {
            container.notificationRepository.capture(entity)
            // Debounced by unique-work KEEP + 10s delay: a burst coalesces into one run.
            ForwardWorker.enqueueNow(applicationContext)
        }
    }
}
