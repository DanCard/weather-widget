package com.weatherwidget.data.local

import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyActualsHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.ObservedPrecip
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** An archived hour must be what the views drew for it: each case compares against the real reader. */
@Category(ShortDuration::class)
class HourlyActualsArchiveRunTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val lat = 37.42
    private val lon = -122.08
    private val hour = 3_600_000L
    private val nowMs = LocalDate.of(2026, 11, 1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 10, 15)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private inner class FakeHost(
        val rows: List<ObservationReading>,
        val forecasts: List<HourlyForecast> = emptyList(),
    ) : HourlyActualsArchiveRun.Host {
        val stored = linkedMapOf<Pair<Long, String>, HourlyActual>()

        override suspend fun latestArchivedHour() = stored.values.maxOfOrNull { it.hourMs }
        override suspend fun oldestObservation() = rows.minOfOrNull { it.timestamp }
        override suspend fun observationSites(startMs: Long, endMs: Long) =
            rows.filter { it.timestamp in startMs until endMs }
                .groupingBy { it.locationLat to it.locationLon }.eachCount()
        override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double) =
            rows.filter { it.timestamp in startMs until endMs }
        override suspend fun hourlyForecasts(startMs: Long, endMs: Long, lat: Double, lon: Double) = forecasts
        override fun personalStationWeight() = 1.0
        override suspend fun insertHourlyActuals(rows: List<HourlyActual>) {
            rows.forEach { stored.putIfAbsent(it.hourMs to it.source, it) }
        }
    }

    private fun reading(
        station: String,
        timestamp: Long,
        temperature: Float = 60f,
        api: String = "NWS",
        distanceKm: Float = 3f,
        precipMm: Float? = null,
        rawMetar: String? = null,
    ) = ObservationReading(
        stationId = station, stationName = station, timestamp = timestamp, temperature = temperature,
        condition = "observed", locationLat = lat, locationLon = lon, distanceKm = distanceKm,
        stationType = StationType.OFFICIAL, api = api, precipAmountMm = precipMm, rawMetar = rawMetar,
    )

    /** Three days of readings every 20 minutes around the archived day, drifting so hours differ. */
    private fun stationRows(station: String, base: Float, distanceKm: Float) =
        (-24 * 3 until 48 * 3).map { i ->
            reading(station, dayStart + i * hour / 3, base + (i % 72) / 3f, distanceKm = distanceKm)
        }

    private fun archive(host: FakeHost) = runBlocking { HourlyActualsArchiveRun.run(host, nowMs, zone) }

    @Test
    fun `archived temperature is the graph's top-of-hour mark`() {
        val rows = stationRows("KNUQ", 55f, 3f) + stationRows("KSJC", 65f, 15f)
        val host = FakeHost(rows)
        archive(host)

        val live = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = emptyList(), observations = rows, centerTime = day.atTime(23, 0),
            displaySourceId = "NWS", userLat = lat, userLon = lon, backHours = 23, forwardHours = 0,
            contextLookbackHours = 47, contextLookaheadHours = 24,
            now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone), zoneId = zone,
        ).points.filter { it.isActual }.associate { it.timeMs to it.actualTemp }

        val archived = host.stored.values.filter { it.source == "NWS" && it.hourMs in dayStart until dayStart + 24 * hour }
        assertEquals(24, archived.size)
        archived.forEach { assertEquals(live[it.hourMs], it.temperature) }
        assertTrue("hours must differ", archived.mapNotNull { it.temperature }.toSet().size > 1)
    }

    @Test
    fun `rain equals the reader's hourly amount, not a sampled report`() {
        // Five-minute series: an hour's amount is the sum of its rows, which no single row holds.
        val rows = (0 until 36).map {
            reading(
                "TOMORROW_IO_5M_HISTORY", dayStart + 12 * hour + it * 5 * 60_000L,
                api = "TOMORROW_IO", distanceKm = 0f, precipMm = 0.25f,
            )
        }
        val host = FakeHost(rows)
        archive(host)

        val expected = ObservedPrecip.byHour(ObservedPrecip.rowsFor(rows, WeatherSource.TOMORROW_IO), zone)
        val archived = HourlyActualsHistory.rainByHour(host.stored.values.filter { it.source == "TOMORROW_IO" }, zone)
        assertEquals(expected.filterKeys { it.toLocalDate() == day }, archived)
        assertEquals(3f, archived[day.atTime(12, 0)]!!, 0.001f)
    }

    @Test
    fun `a measured-dry hour stays 0 after its METAR is gone, and an unknown hour stays null`() {
        val metar = "KNUQ 151955Z 31009KT 10SM CLR 20/10 A3001 RMK AO2 SLP161 T02000100"
        val rows = (10..13).map {
            reading("KNUQ", dayStart + it * hour - 5 * 60_000L, rawMetar = metar)
        } + reading("KNUQ", dayStart + 16 * hour - 5 * 60_000L)
        val host = FakeHost(rows)
        archive(host)

        val byHour = host.stored.values.filter { it.source == "NWS" }.associateBy { it.hourMs }
        val live = ObservedPrecip.byHour(ObservedPrecip.rowsFor(rows, WeatherSource.NWS), zone)
        assertEquals(0f, live.values.first(), 0f)
        assertEquals(
            live.filterKeys { it.toLocalDate() == day }.size,
            byHour.values.count { it.precipAmountMm == 0f },
        )
        assertNull(byHour[dayStart + 16 * hour]?.precipAmountMm)
    }

    @Test
    fun `days are archived only once wholly past the age, and raw rows go only behind them`() {
        val host = FakeHost(stationRows("KNUQ", 55f, 3f))
        val result = archive(host)

        val archiveAge = (nowMs - result.throughMs).toDouble() / (24 * hour)
        assertTrue("through is $archiveAge days old", archiveAge >= RetentionPolicy.HOURLY_ACTUALS_ARCHIVE_DAYS)
        assertTrue(host.stored.values.all { it.hourMs < result.throughMs })
    }

    @Test
    fun `a second pass rewrites nothing, even when the blend would now differ`() {
        val rows = stationRows("KNUQ", 55f, 3f)
        val host = FakeHost(rows)
        archive(host)
        val first = host.stored.toMap()

        val changed = FakeHost(rows.map { it.copy(temperature = it.temperature + 10f) })
        changed.stored.putAll(first)
        archive(changed)

        assertEquals(first.filterKeys { it.first < dayStart + 24 * hour }, changed.stored.filterKeys { it.first < dayStart + 24 * hour })
    }

    @Test
    fun `the graph draws an archived hour where no raw rows remain, and a blended point beats it`() {
        val mark = dayStart + 12 * hour
        val empty = mark + hour
        val rows = stationRows("KNUQ", 55f, 3f).filter { it.timestamp <= mark }

        val points = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = emptyList(), observations = rows, centerTime = day.atTime(13, 0),
            displaySourceId = "NWS", userLat = lat, userLon = lon, backHours = 1, forwardHours = 0,
            contextLookbackHours = 25, contextLookaheadHours = 1,
            now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone), zoneId = zone,
            archivedTemps = mapOf(mark to 99f, empty to 42f),
        ).points.associateBy { it.timeMs }

        assertTrue("live wins", points.getValue(mark).actualTemp != 99f)
        assertEquals(42f, points.getValue(empty).actualTemp!!, 0f)
        assertTrue(points.getValue(empty).isObservedActual)
    }

    @Test
    fun `a borrowing source archives its provider's temperature line`() {
        ActualsProviderResolver.installLocationSource { lat to lon }
        try {
            val host = FakeHost(stationRows("KNUQ", 55f, 3f))
            archive(host)

            val nws = host.stored.values.filter { it.source == "NWS" }.associateBy { it.hourMs }
            val silurian = host.stored.values.filter { it.source == "SILURIAN" }
            assertTrue("Silurian has no temperature of its own", silurian.isNotEmpty())
            silurian.forEach { assertEquals(nws[it.hourMs]?.temperature, it.temperature) }
        } finally {
            ActualsProviderResolver.resetLocationSource()
        }
    }

    @Test
    fun `live hours win over archived ones`() {
        assertEquals(mapOf(1L to 10, 2L to 20), HourlyActualsHistory.merge(mapOf(1L to 10), mapOf(1L to 99, 2L to 20)))
    }

    @Test
    fun `the archive waits for the daily recompute, and raw rows cover its context`() {
        val recomputeDays = com.weatherwidget.shared.actuals.DailyBlendRecomputeRun.MAX_DAYS_BACK
        assertTrue(RetentionPolicy.HOURLY_ACTUALS_ARCHIVE_DAYS >= recomputeDays + 2)
        assertTrue(RetentionPolicy.OBSERVATION_ARCHIVE_MARGIN_DAYS >= 2)
    }
}
