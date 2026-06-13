package com.jordanrburger.scout.data

import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.api.NotificationEventDto
import com.jordanrburger.scout.data.db.CapturedNotification
import com.jordanrburger.scout.data.db.CapturedNotificationDao
import kotlinx.coroutines.flow.Flow

sealed interface ForwardOutcome {
    data class Forwarded(val count: Int) : ForwardOutcome
    data object Retry : ForwardOutcome
    data class Aborted(val reason: String) : ForwardOutcome
}

class NotificationRepository(
    private val api: BridgeApi,
    private val dao: CapturedNotificationDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val recent: Flow<List<CapturedNotification>> get() = dao.recent()

    suspend fun capture(notification: CapturedNotification) {
        dao.insert(notification)
    }

    /** Persist-then-forward (spec §6): events were already written by the listener;
     *  this ships the unforwarded backlog in bounded batches. */
    suspend fun forwardBatch(): ForwardOutcome {
        val batch = dao.unforwarded(BATCH_LIMIT)
        if (batch.isEmpty()) return ForwardOutcome.Forwarded(0)
        return when (val result = api.postEvents(batch.map { it.toDto() })) {
            is ApiResult.Ok -> {
                dao.markForwarded(batch.map { it.id }, now())
                ForwardOutcome.Forwarded(batch.size)
            }
            is ApiResult.Unreachable -> ForwardOutcome.Retry
            is ApiResult.Failed -> ForwardOutcome.Aborted(result.error)
            ApiResult.Unauthorized -> ForwardOutcome.Aborted("unauthorized")
        }
    }

    suspend fun pruneForwarded(olderThanMs: Long) = dao.pruneForwarded(olderThanMs)

    /** Allowlist changed (or events were queued under the old model): drop any
     *  unforwarded backlog from packages that are no longer allowed, so it can
     *  never reach the engine. Empty allowlist drops everything queued. */
    suspend fun pruneDisallowed(allowed: Set<String>) {
        if (allowed.isEmpty()) dao.deleteAllUnforwarded()
        else dao.deleteUnforwardedNotIn(allowed)
    }

    private fun CapturedNotification.toDto() = NotificationEventDto(
        key = key, pkg = pkg, postTimeMs = postTimeMs, title = title, text = text,
        bigText = bigText, category = category, isOngoing = isOngoing, removed = removed,
        capturedAt = capturedAt,
    )

    private companion object {
        const val BATCH_LIMIT = 200
    }
}
