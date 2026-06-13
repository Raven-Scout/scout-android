package com.jordanrburger.scout

import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.NotificationRepository
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.domain.SectionKind
import com.jordanrburger.scout.testutil.FakeApi
import com.jordanrburger.scout.testutil.FakeCapturedDao
import com.jordanrburger.scout.testutil.FakePendingDao
import com.jordanrburger.scout.testutil.FakeSnapshots
import com.jordanrburger.scout.testutil.InMemorySettings
import com.jordanrburger.scout.testutil.MainDispatcherRule
import com.jordanrburger.scout.testutil.captured
import com.jordanrburger.scout.ui.actionitems.ActionItemsViewModel
import com.jordanrburger.scout.ui.captures.CapturesViewModel
import com.jordanrburger.scout.ui.settings.SettingsViewModel
import com.jordanrburger.scout.ui.settings.TestConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeApi()
    private fun actionItemsVm() = ActionItemsViewModel(
        ActionItemsRepository(api, FakeSnapshots(), FakePendingDao(), now = { 1000L })
    )

    @Test
    fun `maps loaded state to ui sections with parsed segments`() = runTest {
        val vm = actionItemsVm()
        vm.refresh()
        val ui = vm.ui.value
        assertEquals(listOf(SectionKind.URGENT), ui.sections.map { it.kind })
        val item = ui.sections[0].items[0]
        assertEquals("A1", item.prefix)
        assertEquals("item A1", item.segments.joinToString("") { it.text })
        assertTrue(!ui.stale)
    }

    @Test
    fun `markDone delegates to repository`() = runTest {
        val vm = actionItemsVm()
        vm.refresh()
        vm.markDone("A1")
        assertEquals(listOf("A1"), api.markedDone)
    }

    @Test
    fun `bridge failure surfaces a notice`() = runTest {
        val vm = actionItemsVm()
        vm.refresh()
        api.markDoneResult = ApiResult.Failed("nope")
        val notices = mutableListOf<String>()
        vm.markDone("A1") { notices.add(it) }
        assertEquals(1, notices.size)
    }

    @Test
    fun `stale loaded sets offline banner flag`() = runTest {
        val vm = actionItemsVm()
        vm.refresh() // primes snapshot
        api.todayResult = ApiResult.Unreachable("down")
        vm.refresh()
        assertTrue(vm.ui.value.stale)
    }

    @Test
    fun `captures hides ongoing notifications by default`() = runTest {
        val dao = FakeCapturedDao()
        dao.insert(captured("a", 1))
        dao.insert(captured("b", 2, ongoing = true))
        val vm = CapturesViewModel(NotificationRepository(api, dao))
        assertEquals(listOf("a"), vm.ui.value.map { it.key })
        vm.setHideOngoing(false)
        assertEquals(listOf("b", "a"), vm.ui.value.map { it.key })
    }

    @Test
    fun `pairFrom parses saves and tests connection`() = runTest {
        val settings = InMemorySettings()
        val vm = SettingsViewModel(settings, api)
        assertTrue(vm.pairFrom("scout://pair?u=http%3A%2F%2F10.0.0.199%3A8787&s=zzz"))
        assertEquals("http://10.0.0.199:8787", settings.bridgeUrl.value)
        assertEquals("zzz", settings.secret.value)
        assertTrue(vm.testState.value is TestConnectionState.Success)
        assertFalse(vm.pairFrom("garbage payload"))
        assertEquals("zzz", settings.secret.value) // untouched by bad payload
    }

    @Test
    fun `settings saves and tests connection`() = runTest {
        val settings = InMemorySettings()
        val vm = SettingsViewModel(settings, api)
        vm.save("http://192.168.1.10:8787", "topsecret")
        assertEquals("http://192.168.1.10:8787", settings.bridgeUrl.value)
        assertEquals("topsecret", settings.secret.value)
        vm.testConnection()
        assertTrue(vm.testState.value is TestConnectionState.Success)
        api.healthResult = ApiResult.Unauthorized
        vm.testConnection()
        assertTrue(vm.testState.value is TestConnectionState.Failure)
    }
}
