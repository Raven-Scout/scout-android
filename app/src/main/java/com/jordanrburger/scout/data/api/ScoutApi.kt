package com.jordanrburger.scout.data.api

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class BridgeConfig(val baseUrl: String, val secret: String)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class Unreachable(val cause: String?) : ApiResult<Nothing>
    data class Failed(val error: String) : ApiResult<Nothing>
}

/** Bridge HTTP client (spec §4). Auth + base URL are read per call so Settings
 *  changes apply without rebuilding the client. */
class ScoutApi(
    private val callFactory: Call.Factory,
    private val config: () -> BridgeConfig,
) : BridgeApi {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun health(): ApiResult<HealthResponse> =
        get("/api/v1/health") { ScoutJson.decodeFromString<HealthResponse>(it) }

    override suspend fun today(): ApiResult<ActionItemsResponse> =
        get("/api/v1/action-items/today") { ScoutJson.decodeFromString<ActionItemsResponse>(it) }

    override suspend fun markDone(prefix: String): ApiResult<Unit> =
        postForOk("/api/v1/action-items/$prefix/done", "{}")

    override suspend fun snooze(prefix: String, untilIso: String): ApiResult<Unit> =
        postForOk(
            "/api/v1/action-items/$prefix/snooze",
            ScoutJson.encodeToString(SnoozeRequest(untilIso)),
        )

    override suspend fun postEvents(events: List<NotificationEventDto>): ApiResult<Int> =
        execute({
            builder("/api/v1/events/notifications")
                .post(ScoutJson.encodeToString(EventsRequest(events)).toRequestBody(jsonMedia))
                .build()
        }) { body ->
            val resp = ScoutJson.decodeFromString<EventsResponse>(body)
            if (resp.ok) ApiResult.Ok(resp.accepted) else ApiResult.Failed("bridge rejected events")
        }

    override suspend fun status(): ApiResult<StatusResponse> =
        get("/api/v1/status") { ScoutJson.decodeFromString<StatusResponse>(it) }

    override suspend fun runNow(type: String, bypassBudget: Boolean): ApiResult<Unit> =
        postForStarted(
            "/api/v1/runs",
            ScoutJson.encodeToString(RunRequest(type, bypassBudget)),
        )

    override suspend fun retryRun(runId: String, bypassBudget: Boolean): ApiResult<Unit> =
        postForStarted(
            "/api/v1/runs/retry",
            ScoutJson.encodeToString(RetryRequest(runId, bypassBudget)),
        )

    override suspend fun runLog(runId: String, tail: Int): ApiResult<RunLogResponse> =
        get("/api/v1/runs/$runId/log?tail=$tail") { ScoutJson.decodeFromString<RunLogResponse>(it) }

    private suspend fun postForStarted(path: String, body: String): ApiResult<Unit> =
        execute({ builder(path).post(body.toRequestBody(jsonMedia)).build() }) { text ->
            val resp = ScoutJson.decodeFromString<RunStartedResponse>(text)
            if (resp.started) ApiResult.Ok(Unit)
            else ApiResult.Failed(resp.reason ?: "not started")
        }

    private suspend fun <T> get(path: String, parse: (String) -> T): ApiResult<T> =
        execute({ builder(path).get().build() }) { ApiResult.Ok(parse(it)) }

    private suspend fun postForOk(path: String, body: String): ApiResult<Unit> =
        execute({ builder(path).post(body.toRequestBody(jsonMedia)).build() }) { text ->
            val resp = ScoutJson.decodeFromString<OkResponse>(text)
            if (resp.ok) ApiResult.Ok(Unit) else ApiResult.Failed(resp.error ?: "unknown bridge error")
        }

    private fun builder(path: String): Request.Builder {
        val cfg = config()
        return Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${cfg.secret}")
    }

    /** Request CONSTRUCTION is fallible too: a blank base URL (fresh install,
     *  nothing paired yet) or a typo'd one must surface as a result, never as an
     *  uncaught IllegalArgumentException from OkHttp's URL parser. */
    private suspend fun <T> execute(
        buildRequest: () -> Request,
        onSuccess: (String) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (config().baseUrl.isBlank()) {
            return@withContext ApiResult.Failed("Not paired — set the Bridge URL in Settings")
        }
        val request = try {
            buildRequest()
        } catch (e: IllegalArgumentException) {
            return@withContext ApiResult.Failed(
                "Invalid bridge URL — include the scheme, e.g. http://192.168.1.10:8787"
            )
        }
        try {
            callFactory.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> ApiResult.Unauthorized
                    !response.isSuccessful -> ApiResult.Failed("HTTP ${response.code}")
                    else -> try {
                        onSuccess(body)
                    } catch (e: Exception) {
                        ApiResult.Failed("bad bridge response: ${e.message}")
                    }
                }
            }
        } catch (e: IOException) {
            ApiResult.Unreachable(e.message)
        }
    }
}
