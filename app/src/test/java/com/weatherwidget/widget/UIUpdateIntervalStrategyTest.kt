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
        val timeUntilSkipYesterdayMillis = 80 * 60 * 1000L // 80 minutes away

        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = true,
            timeUntilSkipYesterdayMillis = timeUntilSkipYesterdayMillis
        )

        assertEquals(UIUpdateIntervalStrategy.PLUGGED_IN_MAX_DELAY_MS, delay)
    }

    @Test
    fun `when not charging delay relies on nextUpdateTime`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 15 * 60 * 1000L // 15 minutes away
        val timeUntilSkipYesterdayMillis = 80 * 60 * 1000L // 80 minutes away

        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilSkipYesterdayMillis = timeUntilSkipYesterdayMillis
        )

        assertEquals(15 * 60 * 1000L, delay)
    }

    @Test
    fun `skip-yesterday transition overrides longer delay`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 30 * 60 * 1000L // 30 minutes away
        val timeUntilSkipYesterdayMillis = 5 * 60 * 1000L // 5 minutes away
        
        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilSkipYesterdayMillis = timeUntilSkipYesterdayMillis
        )

        assertEquals(5 * 60 * 1000L, delay)
    }

    @Test
    fun `delay cannot be less than MINIMUM_DELAY_MS`() {
        val nowMillis = 1000L
        val nextUpdateTimeMillis = nowMillis + 10L // 10 milliseconds away
        val timeUntilSkipYesterdayMillis = 80 * 60 * 1000L 
        
        val delay = UIUpdateIntervalStrategy.computeDelayMillis(
            nextUpdateTimeMillis = nextUpdateTimeMillis,
            nowMillis = nowMillis,
            isCharging = false,
            timeUntilSkipYesterdayMillis = timeUntilSkipYesterdayMillis
        )

        assertEquals(UIUpdateIntervalStrategy.MINIMUM_DELAY_MS, delay)
    }
}