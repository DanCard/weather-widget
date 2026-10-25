package com.weatherwidget.shared.contract

import com.weatherwidget.shared.observations.CurrentConditionsRun
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [CurrentConditionsRun.Host] keeps between runs, on Android's prefs Host and desktop's in-memory
 * Host (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): the last run per quantized site
 * (a jitter shares it, a move does not inherit it) and the last attempt per feed. Whether a feed is due
 * is the run's ([CurrentConditionsRun.lowPriorityDue]); the fetch is network and not pinned here.
 */
abstract class CurrentConditionsHostContract {
    /** A Host with nothing stored yet. */
    protected abstract fun open(): CurrentConditionsRun.Host

    @Test
    fun `lastRunMs - per quantized site, never run is 0`() {
        val host = open()
        assertEquals(0L, host.lastRunMs(LAT, LON))

        host.setLastRunMs(LAT, LON, T)

        assertEquals(T, host.lastRunMs(LAT + 0.0003, LON - 0.0003)) // the same quantized site
        assertEquals(0L, host.lastRunMs(LAT + 0.009, LON)) // 1 km north: a move starts fresh
        host.setLastRunMs(LAT, LON, T + 60_000L)
        assertEquals(T + 60_000L, host.lastRunMs(LAT, LON))
    }

    @Test
    fun `lastAttemptMs - per feed, never attempted is 0, the newest mark wins`() {
        val host = open()
        assertEquals(0L, host.lastAttemptMs("TOMORROW_IO"))

        host.markAttempt("TOMORROW_IO", T)
        host.markAttempt("WEATHER_API", T + 1)
        host.markAttempt("TOMORROW_IO", T - 3_600_000L) // a clock set back: stored as given

        assertEquals(T - 3_600_000L, host.lastAttemptMs("TOMORROW_IO"))
        assertEquals(T + 1, host.lastAttemptMs("WEATHER_API"))
        assertEquals(0L, host.lastAttemptMs("OPEN_WEATHER_MAP"))
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val T = 1_792_900_800_000L
    }
}
