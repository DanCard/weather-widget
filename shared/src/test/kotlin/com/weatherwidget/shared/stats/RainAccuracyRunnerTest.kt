package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ObservedPrecip
import com.weatherwidget.shared.contract.RainAccuracyScenario
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class RainAccuracyRunnerTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 5, 20)
    private fun ms(hour: Int, minute: Int = 0): Long = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun obs(hour: Int, mm: Float?, station: String = "KSFO", api: String = "NWS", km: Float = 0f) =
        ObservationReading(
            stationId = station, stationName = station, timestamp = ms(hour, 53), temperature = 60f,
            condition = "Rain", locationLat = 37.0, locationLon = -122.0, distanceKm = km, api = api, precipAmountMm = mm,
        )

    @Test
    fun `the scenario grades both sources against the shared gauge series`() = runBlocking {
        val report = RainAccuracyScenario.run(RainAccuracyScenario.FakeHost())
        report.days.forEach { println("RAIN_ROW $it") }
        RainAccuracyScenario.assertExpected(report)
    }

    @Test
    fun `newest snapshot per hour wins, and a null newest leaves the hour out`() {
        val rows = listOf(
            RainAccuracyRunner.PrecipSnapshot(ms(10), timestampToGroupPredictions = 100, precipAmountMm = 1f),
            RainAccuracyRunner.PrecipSnapshot(ms(10), timestampToGroupPredictions = 200, precipAmountMm = 4f),
            RainAccuracyRunner.PrecipSnapshot(ms(11), timestampToGroupPredictions = 100, precipAmountMm = 1f),
            RainAccuracyRunner.PrecipSnapshot(ms(11), timestampToGroupPredictions = 200, precipAmountMm = null),
        )
        assertEquals(mapOf(day.atTime(10, 0) to 4f), RainAccuracyRunner.latestSnapshotPrecipByHour(rows, zone))
    }

    @Test
    fun `measured rain is gauges only - never a model's own analysis or the NWS blend`() {
        val rows = listOf(
            obs(9, 2f, station = "KSFO"),
            obs(9, 9f, station = "NWS_BLEND"),
            obs(9, 7f, station = "OPEN_METEO_MAIN", api = WeatherSource.OPEN_METEO.id),
            obs(10, 5f, station = "WEATHER_API_MAIN", api = WeatherSource.WEATHER_API.id),
        )
        val measured = RainAccuracyRunner.measuredByHour(rows, emptyList(), zone)
        assertEquals(mapOf(day.atTime(9, 0) to 2f), measured)
        assertEquals(listOf("KSFO"), ObservedPrecip.measuredRows(rows).map { it.stationId })
    }

    @Test
    fun `stations are not added - the nearest gauge with an amount wins`() {
        val rows = listOf(
            obs(9, 3f, station = "KNUQ", km = 3.8f),
            obs(9, 3f, station = "KPAO", km = 6.1f),
            obs(9, 5f, station = "KSJC", km = 15.9f),
        )
        assertEquals(mapOf(day.atTime(9, 0) to 3f), RainAccuracyRunner.measuredByHour(rows, emptyList(), zone))
    }

    @Test
    fun `day and night buckets split at 8a and 8p on paired hours only`() {
        val pred = mapOf(day.atTime(7, 0) to 1f, day.atTime(8, 0) to 2f, day.atTime(19, 0) to 3f, day.atTime(20, 0) to 4f, day.atTime(21, 0) to 6f)
        val actual = mapOf(day.atTime(7, 0) to 0f, day.atTime(8, 0) to 2f, day.atTime(19, 0) to 1f, day.atTime(20, 0) to 4f)
        val row = RainAccuracyRunner.dailyRow(day, WeatherSource.NWS, pred, actual, RainScore.pair(pred, actual), closest = false)
        assertEquals(5f, row.predDayMm!!, 0f)
        assertEquals(3f, row.actualDayMm!!, 0f)
        assertEquals("21:00 is unpaired", 5f, row.predNightMm!!, 0f)
        assertEquals(4, row.pairedHours)
        assertTrue(!row.counted)

        val dayOnly = mapOf(day.atTime(10, 0) to 2f)
        val r = RainAccuracyRunner.dailyRow(day, WeatherSource.NWS, dayOnly, dayOnly, RainScore.pair(dayOnly, dayOnly), false)
        assertNull("no night hour -> null, not 0", r.predNightMm)
    }

    @Test
    fun `summary - today waits for midnight, a short day says so, a scored day stars the closest`() = runBlocking {
        val report = RainAccuracyScenario.run(RainAccuracyScenario.FakeHost())
        val today = RainAccuracyScenario.today

        assertEquals(RainAccuracySummary.DayLine.Today, RainAccuracySummary.dayLine(report, today, today))
        assertNull("future", RainAccuracySummary.dayLine(report, today.plusDays(1), today))
        assertNull("before the window", RainAccuracySummary.dayLine(report, RainAccuracyScenario.d10.minusDays(1), today))
        assertEquals(
            RainAccuracySummary.DayLine.Insufficient(RainAccuracyScenario.d10, 5),
            RainAccuracySummary.dayLine(report, RainAccuracyScenario.d10, today),
        )

        val d13 = RainAccuracySummary.dayLine(report, RainAccuracyScenario.d13, today) as RainAccuracySummary.DayLine.Result
        assertEquals(listOf(RainAccuracyScenario.A), d13.entries.filter { it.closest }.map { it.source })
        assertTrue(
            RainAccuracySummary.scoreLines(report).all { it is RainAccuracySummary.ScoreLine.TooFewRainDays },
        )
    }

    @Test
    fun `a date with forecasts but no measured hour lists no rows`() = runBlocking {
        val forecastOnly = object : RainAccuracyRunner.Host {
            override suspend fun forecastSnapshots(
                source: WeatherSource, startMs: Long, endMs: Long, bucketStartMs: Long, bucketEndMs: Long, lat: Double, lon: Double,
            ) = (0..23).map { RainAccuracyRunner.PrecipSnapshot(startMs + it * 3_600_000L, bucketStartMs, 0f) }
            override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double) = emptyList<ObservationReading>()
            override suspend fun archivedActuals(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceId: String) =
                emptyList<com.weatherwidget.data.model.HourlyActual>()
        }
        val report = RainAccuracyRunner(forecastOnly).run(listOf(WeatherSource.NWS), 37.0, -122.0, days = 3, zone = zone, today = day)
        assertTrue(report.days.toString(), report.days.isEmpty())
        assertEquals(0, report.scores.single().countedDays)
    }
}
