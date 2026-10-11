package com.weatherwidget.data.remote

import com.weatherwidget.data.remote.ForecastSchedule.Due
import com.weatherwidget.data.remote.ForecastSchedule.Part
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
    fun `the earliest part across sources is next`() {
        val next = ForecastSchedule.next(
            listOf(Due("NWS", Part.DAILY, now + 3 * hour), Due("OPEN_METEO", Part.HOURLY, now + hour), Due("NWS", Part.HOURLY, now + 2 * hour)),
        )
        assertEquals(Due("OPEN_METEO", Part.HOURLY, now + hour), next)
        assertNull(ForecastSchedule.next(emptyList()))
    }

    @Test
    fun `sleep until due, capped at 2 h, floored at 1 min`() {
        assertEquals(ForecastSchedule.Sleep(37 * minute, Reason.DUE, Due("NWS", Part.HOURLY, now + 37 * minute)),
            ForecastSchedule.sleep(Due("NWS", Part.HOURLY, now + 37 * minute), now, fetchFailed = false))
        assertEquals(2 * hour, ForecastSchedule.sleep(Due("NWS", Part.DAILY, now + 5 * hour), now, false).ms)
        assertEquals(Reason.CAP, ForecastSchedule.sleep(null, now, false).reason)
        assertEquals(minute, ForecastSchedule.sleep(Due("NWS", Part.DAILY, now - hour), now, false).ms)
    }

    @Test
    fun `after a failed fetch a still-due source waits 15 min, a later one its own time`() {
        val stillDue = ForecastSchedule.sleep(Due("NWS", Part.DAILY, now - minute), now, fetchFailed = true)
        assertEquals(15 * minute, stillDue.ms)
        assertEquals(Reason.RETRY, stillDue.reason)
        assertEquals(40 * minute, ForecastSchedule.sleep(Due("NWS", Part.DAILY, now + 40 * minute), now, true).ms)
    }

    @Test
    fun `log line names the reason and the next part`() {
        assertEquals("sleepMin=37 reason=DUE next=NWS:HOURLY",
            ForecastSchedule.sleep(Due("NWS", Part.HOURLY, now + 37 * minute), now, false).logLine())
        assertEquals("sleepMin=120 reason=CAP", ForecastSchedule.sleep(null, now, false).logLine())
    }

    @Test
    fun `cadence tier changes only at the thresholds the cadences read`() {
        assertEquals(BatteryTier.cadenceTier(false, 79), BatteryTier.cadenceTier(false, 71))
        assertTrue(BatteryTier.cadenceTier(false, 80) != BatteryTier.cadenceTier(false, 79))
        assertTrue(BatteryTier.cadenceTier(false, 70) != BatteryTier.cadenceTier(false, 71))
        assertTrue(BatteryTier.cadenceTier(false, 50) != BatteryTier.cadenceTier(false, 51))
        assertEquals(BatteryTier.cadenceTier(true, 10), BatteryTier.cadenceTier(false, 95))
    }
}
