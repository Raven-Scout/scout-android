package com.jordanrburger.scout

import com.jordanrburger.scout.data.StatusRepository
import com.jordanrburger.scout.data.StatusState
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.RunningDto
import com.jordanrburger.scout.data.api.StatusResponse
import com.jordanrburger.scout.testutil.FakeApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRepositoryTest {

    private val api = FakeApi()
    private fun repo() = StatusRepository(api)

    @Test
    fun `refresh ok emits fresh Loaded`() = runTest {
        api.statusResult = ApiResult.Ok(StatusResponse(RunningDto(active = true, type = "consolidation")))
        val repo = repo()
        repo.refresh()
        val s = repo.state.value as StatusState.Loaded
        assertTrue(!s.stale)
        assertTrue(s.status.running.active)
    }

    @Test
    fun `refresh failure after good emits stale Loaded`() = runTest {
        val repo = repo()
        repo.refresh() // primes lastGood (default Ok)
        api.statusResult = ApiResult.Unreachable("down")
        repo.refresh()
        assertTrue((repo.state.value as StatusState.Loaded).stale)
    }

    @Test
    fun `refresh failure cold emits Error`() = runTest {
        api.statusResult = ApiResult.Unreachable("down")
        val repo = repo()
        repo.refresh()
        assertTrue(repo.state.value is StatusState.Error)
    }

    @Test
    fun `refresh 401 emits Unauthorized`() = runTest {
        api.statusResult = ApiResult.Unauthorized
        val repo = repo()
        repo.refresh()
        assertTrue(repo.state.value is StatusState.Unauthorized)
    }

    @Test
    fun `runNow and retry delegate to api`() = runTest {
        val repo = repo()
        repo.runNow("research", bypassBudget = true)
        repo.retry("scout-2026-06-13_10-56", bypassBudget = false)
        assertEquals(listOf("research" to true), api.runNowCalls)
        assertEquals(listOf("scout-2026-06-13_10-56" to false), api.retryCalls)
    }
}
