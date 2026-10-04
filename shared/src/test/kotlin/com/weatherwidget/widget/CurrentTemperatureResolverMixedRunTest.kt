package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The correction `observed − estimate(observed at)` must be measured against the same forecast run
 * as `estimate(now)`. Desktop, 2026-10-03, OWM displayed: OWM's free forecast never refreshes a slot
 * once it has passed, so 13:00-16:00 came from a 10:35 run that ran ~10° cool and 17:00+ from the
 * 16:42 run. The resolver showed 100.37°F (estimate 90.67 + delta 9.70, estAtObs 80.66) against a
 * Synoptic observation of 90.36. Plan: plans/261003-current-temp-delta-same-forecast-run.md.
 */
@Category(ShortDuration::class)
class CurrentTemperatureResolverMixedRunTest {
    private val zone = ZoneId.systemDefault()
    private val day = LocalDateTime.of(2026, 10, 3, 0, 0)
    private fun ms(hour: Int, minute: Int = 0) = day.withHour(hour).withMinute(minute).atZone(zone).toInstant().toEpochMilli()

    private val staleRunAt = ms(10, 35)
    private val freshRunAt = ms(16, 42)

    private fun owm(hour: Int, temp: Float, fetchedAt: Long) = HourlyForecast(
        dateTime = ms(hour), temperature = temp, condition = "Clear",
        source = WeatherSource.OPEN_WEATHER_MAP.id, fetchedAt = fetchedAt,
    )

    /** The desktop's rows at 16:42: past slots from the 10:35 run, 17:00+ from the 16:42 run. */
    private val desktopRows = listOf(
        owm(5, 64f, staleRunAt), owm(6, 63.5f, staleRunAt), owm(7, 63f, staleRunAt), owm(8, 64f, staleRunAt),
        owm(9, 67f, staleRunAt), owm(10, 70f, staleRunAt), owm(11, 73f, staleRunAt), owm(12, 75.5f, staleRunAt),
        owm(13, 77.7f, staleRunAt), owm(14, 77.3f, staleRunAt), owm(15, 79.2f, staleRunAt), owm(16, 81.1f, staleRunAt),
        owm(17, 94.7f, freshRunAt), owm(18, 92.4f, freshRunAt), owm(19, 90.1f, freshRunAt), owm(20, 87f, freshRunAt),
    )

    private fun resolve(rows: List<HourlyForecast>, now: LocalDateTime, obs: Float, obsAt: Long) =
        CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            hourlyForecasts = rows,
            lastObservedTemp = obs,
            observedAt = obsAt,
            storedDeltaState = null,
            currentLat = 37.417,
            currentLon = -122.089,
        )

    @Test
    fun `a correction measured on a stale run is not added to a fresh one`() {
        val result = resolve(desktopRows, now = day.withHour(16).withMinute(42).withSecond(10), obs = 90.36f, obsAt = ms(15, 47))

        val display = result.displayTemp!!
        // Old code: 100.37. Both estimates now come from the 16:42 run (17:00 held flat before it).
        assertEquals("display tracks the observation", 90.36f, display, 1.0f)
        assertTrue("never hotter than any input (obs 90.36, forecast max 94.7): $display", display < 94.7f)
    }

    @Test
    fun `emulator case - 110 degrees becomes the observation`() {
        val result = resolve(desktopRows, now = day.withHour(16).withMinute(49), obs = 95.36f, obsAt = ms(15, 48))

        assertEquals(95.36f, result.displayTemp!!, 1.0f)
    }

    @Test
    fun `when the observation is bracketed by the newest run, nothing changes`() {
        // One run for every hour (the usual NWS shape).
        val oneRun = desktopRows.map { it.copy(fetchedAt = freshRunAt) }

        assertNull(CurrentTemperatureResolver.newestRunIfObservationBracketIsOlder(oneRun, WeatherSource.OPEN_WEATHER_MAP.id, ms(15, 47)))
    }

    // NWS refreshes each hour while it is current, so consecutive hours come from different fetches
    // that agree. The newest run must not replace them: here the older run matches the observation
    // exactly, and the falling 20:00 -> 21:00 trend has to survive.
    @Test
    fun `agreeing runs across a fetch boundary keep the forecast trend`() {
        val rows = listOf(owm(20, 74f, ms(19, 30)), owm(21, 70f, ms(20, 30)))

        val result = resolve(rows, now = day.withHour(20).withMinute(30), obs = 73f, obsAt = ms(20, 15))

        assertTrue("trend down from the observation: ${result.displayTemp}", result.displayTemp!! < 73f)
    }

    @Test
    fun `newest run is chosen only when it is closer to the observation`() {
        assertTrue(CurrentTemperatureResolver.prefersNewestRun(observed = 90.36f, olderRunEstimate = 80.66f, newestRunEstimate = 94.7f))
        assertTrue(!CurrentTemperatureResolver.prefersNewestRun(observed = 73f, olderRunEstimate = 73f, newestRunEstimate = 70f))
    }

    @Test
    fun `the newest run is held flat before its first slot and interpolated after`() {
        val run = CurrentTemperatureResolver.newestRunIfObservationBracketIsOlder(desktopRows, WeatherSource.OPEN_WEATHER_MAP.id, ms(15, 47))!!

        assertEquals(listOf(ms(17), ms(18), ms(19), ms(20)), run.map { it.dateTime })
        assertEquals(94.7f, CurrentTemperatureResolver.estimateFromRun(run, ms(15, 47)), 0.001f)
        assertEquals(93.55f, CurrentTemperatureResolver.estimateFromRun(run, ms(17, 30)), 0.01f)
    }
}
