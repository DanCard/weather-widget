package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.RainPeriodSelection
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The desktop seam that was missing: mapping the forecast curve plus observation rows onto the
 * shared row type, with actuals withheld for hours that have not elapsed.
 *
 * Exercises [buildRainHours] together with [RainPeriodSelection] — the two halves whose absence
 * produced an orange "Actual: .003in" label sitting an hour past NOW on 2026-09-01.
 *
 * See plans/260901-share-rain-period-selection.md.
 */
@Category(ShortDuration::class)
class DesktopRainHourNowGateTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val midnight = LocalDateTime.of(2026, 9, 1, 0, 0)

    private fun epoch(hour: Int): Long =
        midnight.plusHours(hour.toLong()).atZone(zone).toInstant().toEpochMilli()

    private fun forecastHour(hour: Int, amountMm: Float?) = HourlyForecast(
        dateTime = epoch(hour),
        temperature = 60f,
        condition = "Rain",
        precipAmountMm = amountMm,
        source = WeatherSource.NWS.id,
    )

    private fun observation(hour: Int, amountMm: Float?, minute: Int = 0) = ObservationReading(
        stationId = "KNUQ",
        stationName = "Moffett",
        timestamp = epoch(hour) + minute * 60_000L,
        temperature = 60f,
        condition = "Rain",
        locationLat = 37.4,
        locationLon = -122.1,
        api = WeatherSource.NWS.id,
        precipAmountMm = amountMm,
    )

    @Test
    fun `hours at and after now carry no actual`() {
        // NOW is 07:00. Observations exist for 06:00 and -- as a stand-in for a mis-filed row -- for
        // 09:00. Only the elapsed one may reach the graph.
        val points = (0 until 12).map { forecastHour(it, 2f) }
        val observations = listOf(observation(6, 1f), observation(9, 4f))

        val rows = buildRainHours(points, observations, now = epoch(7))

        assertEquals("Elapsed hour keeps its measurement", 1f, rows[6].actualPrecipAmountMm)
        assertNull("The NOW hour itself has not elapsed", rows[7].actualPrecipAmountMm)
        assertNull("A future hour must never carry an actual", rows[9].actualPrecipAmountMm)
        assertTrue("The forecast series is untouched by the gate", rows.all { it.precipAmountMm == 2f })
    }

    @Test
    fun `no actual label is produced in the future`() {
        // End to end over the seam: this is the screenshot case. Rain forecast across the whole
        // window, one elapsed observation, NOW at 07:00.
        val points = (0 until 24).map { forecastHour(it, 2f) }
        val observations = listOf(observation(6, 1f))

        val periods = RainPeriodSelection.selectPeriods(
            hours = buildRainHours(points, observations, now = epoch(7)),
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = 10f,
        )

        // The 8a-8p day run is entirely future: it must carry no actual label at all. That run is
        // exactly what was labelled "Actual: .003in" in the report. A segment that merely CONTAINS
        // the current hour is legitimate, so the night run 0..7 may exist -- with an elapsed total.
        assertTrue(
            "No actual period may be anchored in a fully-future region, got " +
                periods.actual.map { it.startIndex..it.endIndex },
            periods.actual.none { it.startIndex >= 7 },
        )
        assertEquals(
            "The actual total is the single elapsed observation, not the forecast's 2mm/h",
            1f,
            periods.actual.single().totalAmountMm,
        )
        assertTrue("The forecast still labels the future day run", periods.forecast.any { it.startIndex >= 8 })
    }

    @Test
    fun `a source with no historical precipitation contributes no actuals`() {
        // Silurian: historicalDataKind NONE. Even with observation rows present under its own api
        // id, none may be relabelled as measured precipitation -- the exact source in the report.
        val silurianRows = listOf(
            observation(3, 5f).copy(api = WeatherSource.SILURIAN.id, stationId = "SILURIAN_MAIN"),
        )

        val rows = actualPrecipRowsForSource(silurianRows, WeatherSource.SILURIAN.id)

        assertTrue("Silurian has no observation product to draw from", rows.isEmpty())
    }

    @Test
    fun `several readings inside one hour are summed`() {
        // Sub-hourly feeds report more than once an hour; the hourly actual is their total, not the
        // last one to land.
        val points = (0 until 6).map { forecastHour(it, 1f) }
        val observations = listOf(
            observation(2, 0.4f, minute = 5),
            observation(2, 0.6f, minute = 35),
        )

        val rows = buildRainHours(points, observations, now = epoch(5))

        assertEquals(1f, rows[2].actualPrecipAmountMm!!, 0.0001f)
    }

    @Test
    fun `an hour with no observation carries null rather than zero`() {
        // Null and 0f differ: 0f would let a dry elapsed hour keep a segment alive at zero total,
        // whereas null means "not measured".
        val points = (0 until 6).map { forecastHour(it, 1f) }

        val rows = buildRainHours(points, observations = emptyList(), now = epoch(5))

        assertTrue("Unmeasured hours are null, not 0", rows.all { it.actualPrecipAmountMm == null })
    }
}
