package com.jordanrburger.scout

import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.ActionItemsState
import com.jordanrburger.scout.data.ForwardOutcome
import com.jordanrburger.scout.data.NotificationRepository
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.db.PendingAction
import com.jordanrburger.scout.testutil.FakeApi
import com.jordanrburger.scout.testutil.FakeCapturedDao
import com.jordanrburger.scout.testutil.FakePendingDao
import com.jordanrburger.scout.testutil.FakeSnapshots
import com.jordanrburger.scout.testutil.captured
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionItemsRepositoryTest {

    private val api = FakeApi()
    private val snapshots = FakeSnapshots()
    private val pending = FakePendingDao()
    private fun repo(now: Long = 1000L) =
        ActionItemsRepository(api, snapshots, pending, now = { now })

    @Test
    fun `refresh success caches snapshot and emits fresh Loaded`() = runTest {
        val repo = repo()
        repo.refresh()
        val state = repo.state.value as ActionItemsState.Loaded
        assertEquals(false, state.stale)
        assertEquals("A1", state.response.items[0].shortPrefix)
        assertNotNull(snapshots.stored)
    }

    @Test
    fun `refresh failure after snapshot emits stale Loaded`() = runTest {
        val repo = repo()
        repo.refresh() // primes snapshot
        api.todayResult = ApiResult.Unreachable("down")
        repo.refresh()
        val state = repo.state.value as ActionItemsState.Loaded
        assertTrue(state.stale)
        assertEquals("A1", state.response.items[0].shortPrefix)
    }

    @Test
    fun `refresh failure without snapshot emits Error`() = runTest {
        api.todayResult = ApiResult.Unreachable("down")
        val repo = repo()
        repo.refresh()
        assertTrue(repo.state.value is ActionItemsState.Error)
    }

    @Test
    fun `refresh 401 emits Unauthorized`() = runTest {
        api.todayResult = ApiResult.Unauthorized
        val repo = repo()
        repo.refresh()
        assertTrue(repo.state.value is ActionItemsState.Unauthorized)
    }

    @Test
    fun `markDone is optimistic and final on success`() = runTest {
        val repo = repo()
        repo.refresh()
        repo.markDone("A1")
        val state = repo.state.value as ActionItemsState.Loaded
        assertEquals("done", state.response.items[0].status)
        assertEquals(listOf("A1"), api.markedDone)
        assertEquals(0, pending.rows.size)
    }

    @Test
    fun `markDone queues when unreachable and keeps optimistic state`() = runTest {
        val repo = repo()
        repo.refresh()
        api.markDoneResult = ApiResult.Unreachable("down")
        repo.markDone("A1")
        val state = repo.state.value as ActionItemsState.Loaded
        assertEquals("done", state.response.items[0].status)
        assertEquals(1, pending.rows.size)
        assertEquals("done", pending.rows[0].action)
    }

    @Test
    fun `markDone rolls back and notices on bridge failure`() = runTest {
        val repo = repo()
        repo.refresh()
        api.markDoneResult = ApiResult.Failed("no such item")
        var notice: String? = null
        repo.markDone("A1") { notice = it }
        val state = repo.state.value as ActionItemsState.Loaded
        assertEquals("open", state.response.items[0].status)
        assertEquals(0, pending.rows.size)
        assertNotNull(notice)
    }

    @Test
    fun `drainPending deletes on success drops on failure stops on unreachable`() = runTest {
        val repo = repo()
        pending.insert(PendingAction(prefix = "A1", action = "done", createdAt = 1))
        pending.insert(PendingAction(prefix = "A2", action = "done", createdAt = 2))
        pending.insert(PendingAction(prefix = "A3", action = "done", createdAt = 3))
        var calls = 0
        val outcomes = mutableListOf<String>()
        repo.drainPending(onDropped = { outcomes.add(it) }) { row ->
            calls++
            when (row.prefix) {
                "A1" -> ApiResult.Ok(Unit)
                "A2" -> ApiResult.Failed("gone")
                else -> ApiResult.Unreachable("down")
            }
        }
        assertEquals(3, calls)
        assertEquals(1, pending.rows.size)          // only A3 remains
        assertEquals("A3", pending.rows[0].prefix)
        assertEquals(1, pending.rows[0].attempts)   // bumped
        assertEquals(1, outcomes.size)              // A2 dropped with notice
    }
}

class NotificationRepositoryTest {

    private val api = FakeApi()
    private val dao = FakeCapturedDao()
    private val repo = NotificationRepository(api, dao, now = { 5000L })

    @Test
    fun `forwardBatch marks forwarded with timestamp`() = runTest {
        dao.insert(captured("k1", 1))
        dao.insert(captured("k2", 2))
        val outcome = repo.forwardBatch()
        assertEquals(ForwardOutcome.Forwarded(2), outcome)
        assertTrue(dao.rows.all { it.forwardedAt == 5000L })
        assertEquals(1, api.postedBatches.size)
    }

    @Test
    fun `forwardBatch with nothing pending is a no-op`() = runTest {
        assertEquals(ForwardOutcome.Forwarded(0), repo.forwardBatch())
        assertEquals(0, api.postedBatches.size)
    }

    @Test
    fun `forwardBatch retries later when unreachable`() = runTest {
        dao.insert(captured("k1", 1))
        api.postEventsResult = ApiResult.Unreachable("down")
        assertEquals(ForwardOutcome.Retry, repo.forwardBatch())
        assertNull(dao.rows[0].forwardedAt)
    }

    @Test
    fun `forwardBatch caps at 200`() = runTest {
        repeat(250) { dao.insert(captured("k$it", it.toLong())) }
        repo.forwardBatch()
        assertEquals(200, api.postedBatches[0].size)
    }
}
