package com.jordanrburger.scout.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.ForwardOutcome
import com.jordanrburger.scout.data.NotificationRepository
import java.util.concurrent.TimeUnit

/** Pure logic half — JVM-testable without WorkManager. Drains the write queue
 *  first so a mark-done taken offline lands before the next fetch (spec §5). */
object ForwardLogic {
    enum class Outcome { SUCCESS, RETRY }

    suspend fun run(
        actionItems: ActionItemsRepository,
        notifications: NotificationRepository,
        allowed: Set<String>,
    ): Outcome {
        actionItems.drainPending()
        notifications.pruneDisallowed(allowed)
        return when (notifications.forwardBatch()) {
            is ForwardOutcome.Forwarded -> Outcome.SUCCESS
            ForwardOutcome.Retry -> Outcome.RETRY
            // Permanent rejection: retrying won't help; events stay local and
            // visible as unforwarded in the Captures screen.
            is ForwardOutcome.Aborted -> Outcome.SUCCESS
        }
    }
}

class ForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = ScoutApp.container(applicationContext)
        return when (
            ForwardLogic.run(
                container.actionItemsRepository,
                container.notificationRepository,
                container.settings.allowedPackages.value,
            )
        ) {
            ForwardLogic.Outcome.SUCCESS -> Result.success()
            ForwardLogic.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "scout-forward-periodic"
        private const val NOW_NAME = "scout-forward-now"

        private val connected =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ForwardWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(connected)
                    .build(),
            )
        }

        /** Debounced "forward soon" after a capture burst. */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ForwardWorker>()
                    .setConstraints(connected)
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
