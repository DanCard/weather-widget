package com.weatherwidget.data.remote

import com.weatherwidget.data.remote.ForecastSchedule.Due
import com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon
import com.weatherwidget.data.remote.ForecastSchedule.Reason
import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastScheduleTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_000 * hour

    @Test
    fun `due at last plus interval less the grace, now when never fetched, never when off`() {
        assertEquals(now - hour + 4 * hour - 2 * minute, ForecastSchedule.dueAtMs(now - hour, 4 * hour, now))
        assertEquals(now, ForecastSchedule.dueAtMs(null, 4 * hour, now))
        assertNull(ForecastSchedule.dueAtMs(now - hour, null, now))
        assertTrue(ForecastSchedule.isDue(now - 4 * hour + minute, 4 * hour, now))
        assertFalse(ForecastSchedule.isDue(now - 4 * hour + 3 * minute, 4 * hour, now))
        assertFalse(ForecastSchedule.isDue(null, null, now))
    }

    @Test
    fun `the earliest due across sources is next`() {
        val next = ForecastSchedule.next(
            listOf(Due("NWS", Horizon.LONG, now + 3 * hour), Due("OPEN_METEO", Horizon.SHORT, now + hour), Due("NWS", Horizon.SHORT, now + 2 * hour)),
        )
        assertEquals(Due("OPEN_METEO", Horizon.SHORT, now + hour), next)
        assertNull(ForecastSchedule.next(emptyList()))
    }

    @Test
    fun `sleep until due, capped at 2 h, floored at 1 min`() {
        assertEquals(ForecastSchedule.Sleep(37 * minute, Reason.DUE, Due("NWS", Horizon.SHORT, now + 37 * minute)),
            ForecastSchedule.sleep(Due("NWS", Horizon.SHORT, now + 37 * minute), now, fetchFailed = false))
        assertEquals(2 * hour, ForecastSchedule.sleep(Due("NWS", Horizon.LONG, now + 5 * hour), now, false).ms)
        assertEquals(Reason.CAP, ForecastSchedule.sleep(null, now, false).reason)
        assertEquals(minute, ForecastSchedule.sleep(Due("NWS", Horizon.LONG, now - hour), now, false).ms)
    }

    @Test
    fun `after a failed fetch a still-due source waits 15 min, a later one its own time`() {
        val stillDue = ForecastSchedule.sleep(Due("NWS", Horizon.LONG, now - minute), now, fetchFailed = true)
        assertEquals(15 * minute, stillDue.ms)
        assertEquals(Reason.RETRY, stillDue.reason)
        assertEquals(40 * minute, ForecastSchedule.sleep(Due("NWS", Horizon.LONG, now + 40 * minute), now, true).ms)
    }

    @Test
    fun `log line names the reason and the next horizon`() {
        assertEquals("sleepMin=37 reason=DUE next=NWS:SHORT",
            ForecastSchedule.sleep(Due("NWS", Horizon.SHORT, now + 37 * minute), now, false).logLine())
        assertEquals("sleepMin=120 reason=CAP", ForecastSchedule.sleep(null, now, false).logLine())
    }

    @Test
    fun `cadence tier changes only at the high-battery threshold the horizon cadence reads`() {
        assertEquals(BatteryTier.cadenceTier(false, 95), BatteryTier.cadenceTier(false, 70))
        assertTrue(BatteryTier.cadenceTier(false, 70) != BatteryTier.cadenceTier(false, 69))
        assertEquals(BatteryTier.cadenceTier(false, 69), BatteryTier.cadenceTier(false, 10))
        assertEquals(BatteryTier.cadenceTier(true, 10), BatteryTier.cadenceTier(false, 95))
    }

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a part held by a quota block is next due when the block ends`() {
        val now = 1_000_000_000L
        val hour = 3_600_000L
        val overdue = now - hour
        assertEquals(now + 3 * hour, ForecastSchedule.heldPast(overdue, now + 3 * hour))
        assertEquals("a block ending before the due time changes nothing", now + 5 * hour, ForecastSchedule.heldPast(now + 5 * hour, now + 3 * hour))
        assertEquals(overdue, ForecastSchedule.heldPast(overdue, null))
        assertNull("no cadence stays no cadence", ForecastSchedule.heldPast(null, now + hour))
    }

    @Test
    fun `blocked products are the ones refused past now`() {
        SourceQuotaBlocks.reset()
        try {
            val now = 1_000_000_000L
            SourceQuotaBlocks.block("GOOGLE_WEATHER", com.weatherwidget.data.model.ForecastProduct.HOURLY, now + 60_000L)
            SourceQuotaBlocks.block("GOOGLE_WEATHER", com.weatherwidget.data.model.ForecastProduct.DAILY, now - 1L)
            assertEquals(setOf(com.weatherwidget.data.model.ForecastProduct.HOURLY), SourceQuotaBlocks.blockedProducts("GOOGLE_WEATHER", now))
            assertTrue(SourceQuotaBlocks.blockedProducts("NWS", now).isEmpty())
            assertTrue(SourceQuotaBlocks.blockedProducts("GOOGLE_WEATHER", now + 60_000L).isEmpty())
        } finally {
            SourceQuotaBlocks.reset()
        }
    }
}
