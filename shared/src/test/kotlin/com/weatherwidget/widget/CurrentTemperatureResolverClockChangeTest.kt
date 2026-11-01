package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.WeatherTimeUtils
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Clock changes, spring-forward included further down.
 *
 * 2026-11-01 in America/Los_Angeles (the zone Gradle pins for tests): 01:00 happens twice, at 08:00Z
 * (PDT) and 09:00Z (PST). A LocalDateTime cannot tell them apart, so every hour lookup has to stay
 * on the instant. `loadCached resolves correct temperature delta` failed from 02:00 to 03:00 that
 * morning: its "hour ago" row at 09:00Z was looked up at 08:00Z and the delta was lost.
 */
@Category(ShortDuration::class)
class CurrentTemperatureResolverClockChangeTest {
    private val source = WeatherSource.NWS

    private fun at(utc: String) = Instant.parse(utc).toEpochMilli()

    private fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    private fun row(utc: String, temp: Float, fetchedAt: Long = at("2026-11-01T07:00:00Z")) = HourlyForecast(
        dateTime = at(utc),
        temperature = temp,
        condition = "Clear",
        source = source.id,
        fetchedAt = fetchedAt,
    )

    private val hours = listOf(
        row("2026-11-01T08:00:00Z", 60f),
        row("2026-11-01T09:00:00Z", 62f),
        row("2026-11-01T10:00:00Z", 64f),
        row("2026-11-01T11:00:00Z", 66f),
    )

    @Test
    fun `an observation in the repeated hour measures against that hour's forecast`() {
        val nowMs = at("2026-11-01T10:30:00Z")

        val result = CurrentTemperatureResolver.resolve(
            now = local(nowMs),
            displaySource = source,
            hourlyForecasts = hours,
            lastObservedTemp = 65f,
            observedAt = at("2026-11-01T09:00:00Z"),
            nowMs = nowMs,
        )

        assertEquals(3f, result.appliedDelta!!, 0.01f)
    }

    @Test
    fun `now in the repeated hour estimates that hour`() {
        val nowMs = at("2026-11-01T09:30:00Z")

        val result = CurrentTempRun.header(
            hourly = hours,
            displaySource = source,
            nowLocal = local(nowMs),
            observedTemp = null,
            observedAt = null,
            nowMs = nowMs,
        )

        assertEquals(63f, result.estimatedTemp!!, 0.01f)
    }

    @Test
    fun `the observation bracket is the repeated hour itself`() {
        val newestFetch = at("2026-11-01T10:00:00Z")
        val rows = listOf(
            row("2026-11-01T08:00:00Z", 60f),
            row("2026-11-01T09:00:00Z", 62f, fetchedAt = newestFetch),
            row("2026-11-01T10:00:00Z", 64f, fetchedAt = newestFetch),
        )

        assertNull(
            CurrentTemperatureResolver.newestRunIfObservationBracketIsOlder(
                rows,
                source.id,
                at("2026-11-01T09:30:00Z"),
            ),
        )
    }

    @Test
    fun `the resolution window is 15 real hours across the repeated hour`() {
        val window = CurrentTemperatureResolver.resolutionWindowMs(at("2026-11-01T10:10:00Z"))

        assertEquals(at("2026-10-31T22:00:00Z"), window.first)
        assertEquals(at("2026-11-01T13:00:00Z"), window.last)
    }

    @Test
    fun `quick resolve in the repeated hour estimates that hour`() {
        val nowMs = at("2026-11-01T09:30:00Z")

        val quick = CurrentTemperatureResolver.resolveQuick(
            now = local(nowMs),
            displaySource = source,
            hourlyForecasts = hours,
            lastObservedTemp = null,
            nowMs = nowMs,
        )

        assertEquals(63f, quick.estimatedTemp!!, 0.01f)
    }

    @Test
    fun `an input window around the repeated hour keeps both of them`() {
        val nowMs = at("2026-11-01T09:30:00Z")
        val window = CurrentTemperatureResolver.resolutionWindowMs(nowMs)

        assertTrue(at("2026-11-01T08:00:00Z") in window && at("2026-11-01T09:00:00Z") in window)
    }

    // 2027-03-14 in America/Los_Angeles: 02:00 does not exist. 01:00 PST is 09:00Z, 03:00 PDT is 10:00Z.

    private val springHours = listOf(
        row("2027-03-14T08:00:00Z", 50f, fetchedAt = at("2027-03-14T07:00:00Z")),
        row("2027-03-14T09:00:00Z", 52f, fetchedAt = at("2027-03-14T07:00:00Z")),
        row("2027-03-14T10:00:00Z", 54f, fetchedAt = at("2027-03-14T07:00:00Z")),
        row("2027-03-14T11:00:00Z", 56f, fetchedAt = at("2027-03-14T07:00:00Z")),
    )

    @Test
    fun `before the skipped hour the next forecast is the hour after the gap`() {
        val nowMs = at("2027-03-14T09:30:00Z") // 01:30 PST, half way to 03:00 PDT

        val result = CurrentTempRun.header(
            hourly = springHours,
            displaySource = source,
            nowLocal = local(nowMs),
            observedTemp = null,
            observedAt = null,
            nowMs = nowMs,
        )

        assertEquals(53f, result.estimatedTemp!!, 0.01f)
    }

    @Test
    fun `an observation just before the gap is measured across it`() {
        val nowMs = at("2027-03-14T10:30:00Z") // 03:30 PDT

        val result = CurrentTemperatureResolver.resolve(
            now = local(nowMs),
            displaySource = source,
            hourlyForecasts = springHours,
            lastObservedTemp = 56f,
            observedAt = at("2027-03-14T09:30:00Z"),
            nowMs = nowMs,
        )

        assertEquals(3f, result.appliedDelta!!, 0.01f)
        assertEquals(58f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `a wall clock read just after the change resolves the same as the instant`() {
        val nowMs = at("2027-03-14T10:00:00Z")

        assertEquals(nowMs, WeatherTimeUtils.liveEpochMs(local(nowMs)))
    }

    @Test
    fun `the resolution window is 15 real hours across the skipped hour`() {
        val window = CurrentTemperatureResolver.resolutionWindowMs(at("2027-03-14T10:10:00Z"))

        assertEquals(at("2027-03-13T22:00:00Z"), window.first)
        assertEquals(at("2027-03-14T13:00:00Z"), window.last)
    }

    // ---- liveEpochMs ----

    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `live now takes the repeated hour the clock is in`() {
        val wallClock = LocalDateTime.of(2026, 11, 1, 1, 30)

        assertEquals(
            at("2026-11-01T08:30:00Z"),
            WeatherTimeUtils.liveEpochMs(wallClock, zone, clockMs = at("2026-11-01T08:30:05Z")),
        )
        assertEquals(
            at("2026-11-01T09:30:00Z"),
            WeatherTimeUtils.liveEpochMs(wallClock, zone, clockMs = at("2026-11-01T09:30:05Z")),
        )
    }

    @Test
    fun `live now captured just before the second 1am stays in the first`() {
        val wallClock = LocalDateTime.of(2026, 11, 1, 1, 59, 59)

        assertEquals(
            at("2026-11-01T08:59:59Z"),
            WeatherTimeUtils.liveEpochMs(wallClock, zone, clockMs = at("2026-11-01T09:00:01Z")),
        )
    }

    @Test
    fun `live now in a skipped hour moves forward`() {
        assertEquals(
            at("2027-03-14T10:30:00Z"),
            WeatherTimeUtils.liveEpochMs(LocalDateTime.of(2027, 3, 14, 2, 30), zone),
        )
    }
}
