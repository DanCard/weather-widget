package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class UIUpdateIntervalStrategyTest {

    @Test
    fun `when charging delay is capped at PLUGGED_IN_MAX_DELAY_MS`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 60 * 60 * 1000L // 60 minutes away
        val timeUntilDayRolloverMillis = 80 * 60 * 1000L // 80 minutes away

        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = true,
            timeUntilDayRolloverMillis = timeUntilDayRolloverMillis
        )

        assertEquals(UIUpdateIntervalStrategy.PLUGGED_IN_MAX_DELAY_MS, delay)
    }

    @Test
    fun `when not charging delay relies on nextUpdateTime`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 15 * 60 * 1000L // 15 minutes away
        val timeUntilDayRolloverMillis = 80 * 60 * 1000L // 80 minutes away

        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilDayRolloverMillis = timeUntilDayRolloverMillis
        )

        assertEquals(15 * 60 * 1000L, delay)
    }

    @Test
    fun `day rollover overrides longer delay`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 30 * 60 * 1000L // 30 minutes away
        val timeUntilDayRolloverMillis = 5 * 60 * 1000L // 5 minutes away
        
        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilDayRolloverMillis = timeUntilDayRolloverMillis
        )

        assertEquals(5 * 60 * 1000L, delay)
    }

    @Test
    fun `delay cannot be less than MINIMUM_DELAY_MS`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 10L // 10 milliseconds away
        val timeUntilDayRolloverMillis = 80 * 60 * 1000L 
        
        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilDayRolloverMillis = timeUntilDayRolloverMillis
        )

        assertEquals(UIUpdateIntervalStrategy.MINIMUM_DELAY_MS, delay)
    }

    @Test
    fun `millisUntilNextMidnight measures to the next local midnight`() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val now = java.time.ZonedDateTime.of(2026, 10, 2, 23, 58, 0, 0, zone)
        assertEquals(2 * 60 * 1000L, UIUpdateIntervalStrategy.millisUntilNextMidnight(now))

        val justAfter = java.time.ZonedDateTime.of(2026, 10, 3, 0, 0, 1, 0, zone)
        assertEquals(24 * 60 * 60 * 1000L - 1000L, UIUpdateIntervalStrategy.millisUntilNextMidnight(justAfter))
    }

    /** The next UI repaint lands on midnight, not up to an hour later, so the daily window shifts on time. */
    @Test
    fun `an hour-long update interval is cut short to repaint at midnight`() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val now = java.time.ZonedDateTime.of(2026, 10, 2, 23, 55, 0, 0, zone)
        val nowMillis = now.toInstant().toEpochMilli()

        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nowMillis + 60 * 60 * 1000L,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilDayRolloverMillis = UIUpdateIntervalStrategy.millisUntilNextMidnight(now),
        )

        assertEquals(
            java.time.ZonedDateTime.of(2026, 10, 3, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            nowMillis + delay,
        )
    }
}
