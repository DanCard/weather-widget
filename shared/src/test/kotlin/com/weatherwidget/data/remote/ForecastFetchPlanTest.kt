package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** [ForecastFetchPlan]: the one plan step and `FETCH_PLAN` line, both platforms. */
@Category(ShortDuration::class)
class ForecastFetchPlanTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun decision(agoH: Long?) = ForecastHorizonCadence.decide(
        isCharging = true,
        batteryLevel = 100,
        isScreenOn = true,
        lastFetchMs = agoH?.let { now - it * hour },
        nowMs = now,
    )

    @Test
    fun recentTryHoldsOnlyThatSource() {
        val plan = ForecastFetchPlan.plan(listOf("NWS", "OPEN_WEATHER_MAP"), now, decide = { decision(null) })
        assertEquals(setOf("NWS", "OPEN_WEATHER_MAP"), plan.due.keys)

        val held = plan.withoutRecentlyTried(
            lastTriedMs = { if (it == "NWS") now - 1_000 else 0L },
            nowMs = now,
            minIntervalMs = 600_000L,
        )

        assertEquals(setOf("OPEN_WEATHER_MAP"), held.due.keys)
        assertNull(held["NWS"]!!.horizon)
        assertEquals("next due is not moved", plan["NWS"]!!.nextDue, held["NWS"]!!.nextDue)
        assertTrue(held.line, held.line.startsWith("parts=OPEN_WEATHER_MAP:"))
    }

    @Test
    fun tryAtTheIntervalIsNoLongerHeld() {
        val plan = ForecastFetchPlan.plan(listOf("NWS"), now, decide = { decision(null) })
        assertEquals(setOf("NWS"), plan.withoutRecentlyTried({ now - 600_000L }, now, 600_000L).due.keys)
    }

    @Test
    fun nothingDueStillLogsEveryCandidatesDecision() {
        val plan = ForecastFetchPlan.plan(listOf("NWS", "OPEN_METEO"), now, decide = { decision(1) })
        assertTrue(plan.due.isEmpty())
        assertTrue(plan.line, plan.line.startsWith("parts=none horizons=NWS:"))
        assertTrue(plan.line, plan.line.contains("OPEN_METEO:"))
    }

    @Test
    fun onlyCandidatesAreJudged() {
        // The platform drops gated-off sources before planning; they never reach the line.
        val plan = ForecastFetchPlan.plan(listOf("NWS"), now, decide = { decision(null) })
        assertEquals(mapOf("NWS" to ForecastParts.BOTH), plan.due)
        assertTrue(plan.line, !plan.line.contains("OPEN_METEO"))
        assertEquals(ForecastHorizonCadence.Horizon.LONG, plan["NWS"]!!.horizon)
    }

    @Test
    fun aRoutineFourHourFetchIsLong() {
        // Was SHORT (3 days) before 2026-10-25 (performance/261025-every-forecast-fetch-long.md).
        val plan = ForecastFetchPlan.plan(listOf("NWS", "OPEN_METEO"), now, decide = { decision(4) })
        assertEquals(setOf("NWS", "OPEN_METEO"), plan.due.keys)
        assertEquals(ForecastHorizonCadence.Horizon.LONG, plan["NWS"]!!.horizon)
        assertEquals(ForecastHorizonCadence.Horizon.LONG, plan["OPEN_METEO"]!!.horizon)
        assertTrue(plan.line, plan.line.contains("NWS:LONG(every=4h/4h)"))
    }

    @Test
    fun aForcedSourceTakesItsPartsWhateverTheCadence() {
        val plan = ForecastFetchPlan.plan(listOf("NWS"), now, decide = { decision(1) }, forced = { ForecastParts.BOTH })
        assertEquals(ForecastParts.BOTH, plan.due["NWS"])
        assertNull("no horizon was due", plan["NWS"]!!.horizon)
    }

    @Test
    fun withoutADecisionTheStalenessRuleDecidesAndNothingIsScheduled() {
        val plan = ForecastFetchPlan.plan(listOf("NWS"), now, decide = { null }, dueWithoutDecision = { true })
        assertEquals(ForecastParts.BOTH, plan.due["NWS"])
        assertNull(plan["NWS"]!!.nextDue)
        assertEquals("parts=NWS:BOTH horizons=unjudged", plan.line)
    }
}
