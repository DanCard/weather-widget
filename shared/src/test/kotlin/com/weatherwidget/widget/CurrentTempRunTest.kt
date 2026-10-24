package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/** The header's current temperature, both platforms (plans/261024-shared-current-temp-run.md). */
@Category(ShortDuration::class)
class CurrentTempRunTest {
    private val zone = ZoneId.systemDefault()
    private val now = epoch("2026-10-24T14:20:00")

    @Before
    fun resetCache() {
        ActualsAggregator.blendCache.clear()
    }

    private fun run(hourly: List<HourlyForecast>, observations: List<ObservationReading>) = CurrentTempRun.run(
        hourly = hourly,
        observations = observations,
        displaySource = WeatherSource.NWS,
        latitude = LAT,
        longitude = LON,
        nowMs = now,
        personalStationWeight = 1.0,
        zoneId = zone,
    )

    @Test
    fun `a blended observation anchors the header`() {
        val result = run(flatForecast(60f), listOf(observation("2026-10-24T14:00:00", 63f)))
        assertNotNull(result.observedAt)
        assertNotNull(result.appliedDelta)
        assertEquals(63f, result.displayTemp!!, 0.5f)
    }

    @Test
    fun `a reading the blend rejects never anchors the header`() {
        // QC-failed: the blend drops it. The raw-row fallback (removed, user 2026-10-24) showed
        // exactly such a reading; now there is no observation and the header is the forecast.
        val result = run(flatForecast(58f), listOf(observation("2026-10-24T14:00:00", 90f, qcFailed = true)))
        assertNull(result.observedTemp)
        assertNull(result.appliedDelta)
        assertEquals(58f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `no reading in the window is the bare forecast`() {
        // A reading 13 h old is outside the 12 h window; nothing carries its delta forward.
        val result = run(flatForecast(58f), listOf(observation("2026-10-24T01:00:00", 70f)))
        assertNull(result.observedTemp)
        assertNull(result.appliedDelta)
        assertEquals(58f, result.displayTemp!!, 0.01f)
    }

    private fun flatForecast(temperature: Float): List<HourlyForecast> {
        val start = LocalDateTime.parse("2026-10-23T12:00:00")
        return (0..36).map {
            HourlyForecast(
                dateTime = start.plusHours(it.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = temperature,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                fetchedAt = now,
            )
        }
    }

    private fun observation(time: String, temperature: Float, qcFailed: Boolean = false) = ObservationReading(
        stationId = "KNUQ",
        stationName = "KNUQ",
        timestamp = epoch(time),
        temperature = temperature,
        condition = "observed",
        locationLat = LAT,
        locationLon = LON,
        distanceKm = 2f,
        api = WeatherSource.NWS.id,
        stationType = StationType.OFFICIAL,
        qcFailed = qcFailed,
    )

    private fun epoch(value: String): Long = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }

    @Test
    fun `the input window reaches the yesterday window of the oldest reading the blend accepts`() {
        // The blend at now can anchor on a reading as old as the resolution window's start; its
        // yesterday delta then needs readings down to 24 h + 90 min before that. 36 h fell short
        // once the newest reading was over 10.5 h old.
        val nowLocal = LocalDateTime.of(2026, 10, 24, 14, 20)
        val oldestAnchor = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal).start
            .atZone(zone).toInstant().toEpochMilli()
        val input = CurrentTempRun.inputWindow(nowLocal, zone)
        val yesterdayWindowStart = oldestAnchor -
            YesterdayDeltaCalculator.YESTERDAY_OFFSET_MS - YesterdayDeltaCalculator.DEFAULT_TOLERANCE_MS
        assertEquals(yesterdayWindowStart, input.first)
        assertEquals(37.5, (nowLocal.atZone(zone).toInstant().toEpochMilli() - input.first) / 3_600_000.0, 0.5)
    }
}
