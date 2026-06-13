package com.jordanrburger.scout.data.api

/** The bridge surface repositories depend on; [ScoutApi] is the OkHttp implementation. */
interface BridgeApi {
    suspend fun health(): ApiResult<HealthResponse>
    suspend fun today(): ApiResult<ActionItemsResponse>
    suspend fun markDone(prefix: String): ApiResult<Unit>
    suspend fun snooze(prefix: String, untilIso: String): ApiResult<Unit>
    suspend fun postEvents(events: List<NotificationEventDto>): ApiResult<Int>
    suspend fun status(): ApiResult<StatusResponse>
    suspend fun runNow(type: String, bypassBudget: Boolean): ApiResult<Unit>
    suspend fun retryRun(runId: String, bypassBudget: Boolean): ApiResult<Unit>
    suspend fun runLog(runId: String, tail: Int): ApiResult<RunLogResponse>
}
