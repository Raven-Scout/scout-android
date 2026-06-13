package com.jordanrburger.scout

import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.NotificationRepository
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.db.PendingAction
import com.jordanrburger.scout.testutil.FakeApi
import com.jordanrburger.scout.testutil.FakeCapturedDao
import com.jordanrburger.scout.testutil.FakePendingDao
import com.jordanrburger.scout.testutil.FakeSnapshots
import com.jordanrburger.scout.testutil.captured
import com.jordanrburger.scout.worker.ForwardLogic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardLogicTest {

    private val api = FakeApi()
    private val pendingDao = FakePendingDao()
    private val capturedDao = FakeCapturedDao()
    private val actionItems = ActionItemsRepository(api, FakeSnapshots(), pendingDao, now = { 1L })
    private val notifications = NotificationRepository(api, capturedDao, now = { 2L })

    private val allowP = setOf("p") // testutil.captured() uses pkg "p"

    @Test
    fun `drains pending actions before forwarding captures`() = runTest {
        pendingDao.insert(PendingAction(prefix = "A1", action = "done", createdAt = 1))
        capturedDao.insert(captured("k1", 1))
        val outcome = ForwardLogic.run(actionItems, notifications, allowP)
        assertEquals(ForwardLogic.Outcome.SUCCESS, outcome)
        assertEquals(0, pendingDao.rows.size)               // queue drained
        assertTrue(capturedDao.rows.all { it.forwardedAt != null })
        assertEquals(listOf("A1"), api.markedDone)          // done call happened
    }

    @Test
    fun `unreachable forward asks for retry`() = runTest {
        capturedDao.insert(captured("k1", 1))
        api.postEventsResult = ApiResult.Unreachable("down")
        assertEquals(ForwardLogic.Outcome.RETRY, ForwardLogic.run(actionItems, notifications, allowP))
    }

    @Test
    fun `permanent rejection does not loop retries`() = runTest {
        capturedDao.insert(captured("k1", 1))
        api.postEventsResult = ApiResult.Failed("schema mismatch")
        assertEquals(ForwardLogic.Outcome.SUCCESS, ForwardLogic.run(actionItems, notifications, allowP))
        assertTrue(capturedDao.rows.all { it.forwardedAt == null }) // stays local + visible
    }

    @Test
    fun `queued events from disallowed apps are pruned not forwarded`() = runTest {
        capturedDao.insert(captured("k1", 1))                       // pkg "p", NOT allowed
        val outcome = ForwardLogic.run(actionItems, notifications, allowed = emptySet())
        assertEquals(ForwardLogic.Outcome.SUCCESS, outcome)
        assertEquals(0, api.postedBatches.size)                     // nothing shipped
        assertTrue(capturedDao.rows.none { it.forwardedAt == null }) // backlog gone
    }

    @Test
    fun `prune keeps allowed events and forwards them`() = runTest {
        capturedDao.insert(captured("k1", 1))
        capturedDao.insert(captured("k2", 2).copy(pkg = "com.disallowed"))
        ForwardLogic.run(actionItems, notifications, allowP)
        assertEquals(1, api.postedBatches.size)
        assertEquals(listOf("k1"), api.postedBatches[0].map { it.key })
    }
}
