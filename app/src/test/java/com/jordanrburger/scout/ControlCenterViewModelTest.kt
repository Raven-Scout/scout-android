package com.jordanrburger.scout

import com.jordanrburger.scout.data.StatusRepository
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.ConnectorDto
import com.jordanrburger.scout.data.api.NextRunDto
import com.jordanrburger.scout.data.api.RunningDto
import com.jordanrburger.scout.data.api.StatusResponse
import com.jordanrburger.scout.testutil.FakeApi
import com.jordanrburger.scout.testutil.MainDispatcherRule
import com.jordanrburger.scout.ui.controlcenter.ControlCenterEvent
import com.jordanrburger.scout.ui.controlcenter.ControlCenterViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ControlCenterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeApi()
    private fun vm() = ControlCenterViewModel(StatusRepository(api))

    @Test
    fun `maps loaded status into ui state`() = runTest {
        api.statusResult = ApiResult.Ok(
            StatusResponse(
                running = RunningDto(active = true, type = "consolidation"),
                nextRun = NextRunDto("dreaming-evening", "dreaming", "18:30"),
                todaySpendUsd = 12.5,
                connectors = listOf(ConnectorDto("Slack", "ok")),
            )
        )
        val vm = vm()
        vm.refresh()
        val ui = vm.ui.value
        assertTrue(ui.running.active)
        assertEquals("dreaming-evening", ui.nextRun!!.slot)
        assertEquals(12.5, ui.todaySpendUsd, 0.001)
        assertEquals(1, ui.connectors.size)
    }

    @Test
    fun `runNow delegates to repository`() = runTest {
        val vm = vm()
        vm.runNow("research", bypassBudget = false)
        assertEquals(listOf("research" to false), api.runNowCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `over_budget surfaces a BudgetPrompt event`() = runTest {
        api.runNowResult = ApiResult.Failed("over_budget")
        val vm = vm()
        val events = mutableListOf<ControlCenterEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect(events::add) }
        vm.runNow("consolidation")
        val prompt = events.filterIsInstance<ControlCenterEvent.BudgetPrompt>().single()
        assertEquals("consolidation", prompt.type)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `already_running surfaces a notice`() = runTest {
        api.runNowResult = ApiResult.Failed("already_running")
        val vm = vm()
        val events = mutableListOf<ControlCenterEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect(events::add) }
        vm.runNow("consolidation")
        assertTrue(events.any { it is ControlCenterEvent.Notice })
    }

    @Test
    fun `retry delegates to repository`() = runTest {
        val vm = vm()
        vm.retry("scout-2026-06-13_10-56")
        assertEquals(listOf("scout-2026-06-13_10-56" to false), api.retryCalls)
    }
}
