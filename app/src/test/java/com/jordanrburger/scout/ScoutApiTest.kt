package com.jordanrburger.scout

import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeConfig
import com.jordanrburger.scout.data.api.NotificationEventDto
import com.jordanrburger.scout.data.api.ScoutApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScoutApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ScoutApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        api = ScoutApi(client) { BridgeConfig(server.url("/").toString().trimEnd('/'), "s3cret") }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `today parses items and sends bearer auth`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"date":"2026-06-12","generated_at":"2026-06-12T20:00:00Z",
                   "items":[{"title":"t","priority":"🔴","status":"open",
                             "section":"🔴 Urgent","short_prefix":"QPD1"}]}"""
            )
        )
        val result = api.today()
        val recorded = server.takeRequest()
        assertEquals("/api/v1/action-items/today", recorded.path)
        assertEquals("Bearer s3cret", recorded.getHeader("Authorization"))
        val ok = result as ApiResult.Ok
        assertEquals(1, ok.value.items.size)
        assertEquals("QPD1", ok.value.items[0].shortPrefix)
    }

    @Test
    fun `markDone posts to prefix path and maps ok=false to Failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":false,"error":"no such item"}"""))
        val result = api.markDone("QPD1")
        val recorded = server.takeRequest()
        assertEquals("/api/v1/action-items/QPD1/done", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("no such item", (result as ApiResult.Failed).error)
    }

    @Test
    fun `snooze sends until date`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val result = api.snooze("QPD1", "2026-06-15")
        val recorded = server.takeRequest()
        assertEquals("/api/v1/action-items/QPD1/snooze", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"until\":\"2026-06-15\""))
        assertTrue(result is ApiResult.Ok)
    }

    @Test
    fun `401 maps to Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false}"""))
        assertTrue(api.today() is ApiResult.Unauthorized)
    }

    @Test
    fun `timeout maps to Unreachable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(api.today() is ApiResult.Unreachable)
    }

    @Test
    fun `blank base url fails gracefully instead of crashing`() = runBlocking {
        val unpaired = ScoutApi(OkHttpClient()) { BridgeConfig("", "s") }
        val result = unpaired.today()
        assertTrue(result is ApiResult.Failed)
        assertTrue((result as ApiResult.Failed).error.contains("Not paired"))
    }

    @Test
    fun `malformed base url fails gracefully instead of crashing`() = runBlocking {
        val typo = ScoutApi(OkHttpClient()) { BridgeConfig("192.168.1.10:8787", "s") } // no scheme
        assertTrue(typo.today() is ApiResult.Failed)
        assertTrue(typo.markDone("QPD1") is ApiResult.Failed)
    }

    @Test
    fun `status GETs and parses`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"running":{"active":false},"today_spend_usd":1.5,"connectors":[],"recent_runs":[]}"""))
        val result = api.status()
        assertEquals("/api/v1/status", server.takeRequest().path)
        assertEquals(1.5, (result as ApiResult.Ok).value.todaySpendUsd, 0.001)
    }

    @Test
    fun `runNow posts type and bypass`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"started":true}"""))
        val result = api.runNow("consolidation", bypassBudget = true)
        val rec = server.takeRequest()
        assertEquals("/api/v1/runs", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"type\":\"consolidation\""))
        assertTrue(body.contains("\"bypass_budget\":true"))
        assertTrue(result is ApiResult.Ok)
    }

    @Test
    fun `runNow over_budget maps to Failed with reason`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"started":false,"reason":"over_budget"}"""))
        val result = api.runNow("consolidation", bypassBudget = false)
        assertEquals("over_budget", (result as ApiResult.Failed).error)
    }

    @Test
    fun `retryRun posts run_id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"started":true}"""))
        api.retryRun("scout-2026-06-13_10-56", bypassBudget = false)
        val rec = server.takeRequest()
        assertEquals("/api/v1/runs/retry", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"run_id\":\"scout-2026-06-13_10-56\""))
    }

    @Test
    fun `runLog GETs with tail query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"r","lines":["a","b"]}"""))
        val result = api.runLog("scout-2026-06-13_10-56", 200)
        assertEquals("/api/v1/runs/scout-2026-06-13_10-56/log?tail=200", server.takeRequest().path)
        assertEquals(listOf("a", "b"), (result as ApiResult.Ok).value.lines)
    }

    @Test
    fun `postEvents sends events array and returns accepted count`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"accepted":2}"""))
        val e = NotificationEventDto(
            key = "k", pkg = "p", postTimeMs = 1, title = null, text = null, bigText = null,
            category = null, isOngoing = false, removed = false, capturedAt = "2026-06-12T15:00:00Z",
        )
        val result = api.postEvents(listOf(e, e.copy(key = "k2")))
        val recorded = server.takeRequest()
        assertEquals("/api/v1/events/notifications", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"events\":["))
        assertEquals(2, (result as ApiResult.Ok).value)
    }
}
