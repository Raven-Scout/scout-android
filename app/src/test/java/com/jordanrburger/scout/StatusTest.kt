package com.jordanrburger.scout

import com.jordanrburger.scout.data.api.ScoutJson
import com.jordanrburger.scout.data.api.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTest {

    private val sample = """
        {
          "running": { "active": false },
          "last_run": { "id": "scout-2026-06-13_10-56", "type": "consolidation",
                        "started_at": "2026-06-13T10:56:00", "outcome": "failure", "spend_usd": 9.0 },
          "next_run": { "slot": "dreaming-evening", "type": "dreaming",
                        "fires_at_local": "18:30", "fires_at": "2026-06-13T18:30:00" },
          "today_spend_usd": 12.5,
          "connectors": [ {"name": "Slack", "status": "ok"},
                          {"name": "Granola", "status": "dark"} ],
          "recent_runs": [ {"id": "scout-2026-06-13_10-56", "type": "consolidation",
                            "started_at": "2026-06-13T10:56:00", "outcome": "failure", "spend_usd": 9.0} ]
        }
    """.trimIndent()

    @Test
    fun `status decodes with all fields`() {
        val s = ScoutJson.decodeFromString<StatusResponse>(sample)
        assertFalse(s.running.active)
        assertNull(s.running.type)
        assertEquals(12.5, s.todaySpendUsd, 0.001)
        assertEquals("dreaming-evening", s.nextRun!!.slot)
        assertEquals("18:30", s.nextRun!!.firesAtLocal)
        assertEquals(2, s.connectors.size)
        assertEquals("dark", s.connectors[1].status)
        assertEquals("failure", s.recentRuns[0].outcome)
        assertEquals("consolidation", s.lastRun!!.type)
    }

    @Test
    fun `running active carries type and pid`() {
        val s = ScoutJson.decodeFromString<StatusResponse>(
            """{"running":{"active":true,"type":"consolidation","started_at":"2026-06-13T11:31:00","pid":5147}}"""
        )
        assertEquals("consolidation", s.running.type)
        assertEquals(5147, s.running.pid)
    }
}
