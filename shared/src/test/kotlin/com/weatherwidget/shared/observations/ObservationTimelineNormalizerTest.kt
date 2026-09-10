package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.graph.HourDataAssembler
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class ObservationTimelineNormalizerTest {

    @Test
    fun `pixel regression excludes realtime and retains five minute history`() {
        val rows = listOf(
            row(TomorrowIoActuals.REALTIME_STATION_ID, minute(10, 24), 74.60f),
            row(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, minute(10, 0), 77.07f),
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 20), 78.16f),
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 25), 78.05f),
        )

        val result = normalize(rows)

        assertEquals(listOf(minute(10, 20), minute(10, 25)), result.map { it.timestamp })
        assertEquals(listOf(78.16f, 78.05f), result.map { it.temperature })
        assertEquals(listOf(TomorrowIoActuals.MERGED_SERIES_STATION_ID), result.map { it.stationId }.distinct())
    }

    @Test
    fun `five minute timestamps remain exact without local rounding`() {
        val result = normalize(
            listOf(
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 20), 78.16f),
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 25), 78.05f),
            ),
        )

        assertEquals(listOf(minute(10, 20), minute(10, 25)), result.map { it.timestamp })
    }

    @Test
    fun `same product and timestamp prefers latest fetch`() {
        val timestamp = minute(10, 0)
        val result = normalize(
            listOf(
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, timestamp, 74f, fetchedAt = minute(10, 1)),
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, timestamp, 76f, fetchedAt = minute(10, 20)),
            ),
        )

        assertEquals(76f, result.single().temperature, 0.001f)
    }

    @Test
    fun `legacy products cannot replace five minute history at the same timestamp`() {
        val timestamp = minute(10, 0)
        val result = normalize(
            listOf(
                row(TomorrowIoActuals.REALTIME_STATION_ID, timestamp, 74f, fetchedAt = minute(10, 1)),
                row(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, timestamp, 77.07f, fetchedAt = minute(11, 8)),
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, timestamp, 78.07f, fetchedAt = minute(10, 2)),
            ),
        )

        assertEquals(78.07f, result.single().temperature, 0.001f)
    }

    @Test
    fun `input order cannot change normalized output`() {
        val rows = listOf(
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 20), 78.16f),
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 25), 78.05f),
        )

        assertEquals(normalize(rows), normalize(rows.reversed()))
    }

    @Test
    fun `physical stations sharing a timestamp remain separate`() {
        val timestamp = minute(10, 0)
        val rows = listOf(
            row("KNUQ", timestamp, 80.6f, api = WeatherSource.NWS.id),
            row("KPAO", timestamp, 82.1f, api = WeatherSource.NWS.id),
        )

        val result = ObservationTimelineNormalizer.normalize(rows, WeatherSource.NWS.id)

        assertEquals(listOf("KNUQ", "KPAO"), result.map { it.stationId })
    }

    @Test
    fun `unrelated and qc failed rows do not enter provider timeline`() {
        val result = normalize(
            listOf(
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 0), 75f),
                row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, minute(10, 30), 99f, qcFailed = true),
                row("OPEN_METEO_MAIN", minute(10, 15), 81f, api = WeatherSource.OPEN_METEO.id),
                row("TOMORROW_IO_MAIN", minute(10, 20), 88f),
            ),
        )

        assertEquals(listOf(75f), result.map { it.temperature })
    }

    @Test
    fun `equivalent single-feed providers produce the same shared graph points`() {
        val zone = ZoneId.of("UTC")
        val center = LocalDateTime.parse("2026-09-10T10:00:00")
        val observedTimes = listOf(
            center.minusMinutes(26).atZone(zone).toInstant().toEpochMilli(),
            center.atZone(zone).toInstant().toEpochMilli(),
        )
        val tomorrowRows = listOf(
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, observedTimes[0], 72.70f),
            row(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, observedTimes[1], 75.60f),
        )
        val openMeteoRows = listOf(
            row("OPEN_METEO_1", observedTimes[0], 72.70f, api = WeatherSource.OPEN_METEO.id),
            row("OPEN_METEO_1", observedTimes[1], 75.60f, api = WeatherSource.OPEN_METEO.id),
        )

        fun build(provider: WeatherSource, observations: List<ObservationReading>) =
            ActualTemperatureSeriesBuilder.build(
                hourlyForecasts = (-1..2).map { offset ->
                    HourlyForecast(
                        dateTime = center.plusHours(offset.toLong()).atZone(zone).toInstant().toEpochMilli(),
                        temperature = 74f + offset,
                        condition = "Clear",
                        source = provider.id,
                    )
                },
                observations = observations,
                centerTime = center,
                displaySourceId = provider.id,
                userLat = 37.42,
                userLon = -122.08,
                backHours = 1,
                forwardHours = 2,
                contextLookbackHours = 2,
                contextLookaheadHours = 2,
                now = center.plusHours(1),
                zoneId = zone,
            )

        val tomorrowSeries = build(WeatherSource.TOMORROW_IO, tomorrowRows)
        val openMeteoSeries = build(WeatherSource.OPEN_METEO, openMeteoRows)

        assertEquals(openMeteoSeries.points, tomorrowSeries.points)
        assertEquals(
            HourDataAssembler.assembleHourData(openMeteoSeries, zone),
            HourDataAssembler.assembleHourData(tomorrowSeries, zone),
        )
    }

    private fun normalize(rows: List<ObservationReading>): List<ObservationReading> =
        ObservationTimelineNormalizer.normalize(rows, WeatherSource.TOMORROW_IO.id)

    private fun row(
        stationId: String,
        timestamp: Long,
        temperature: Float,
        fetchedAt: Long = timestamp,
        api: String = WeatherSource.TOMORROW_IO.id,
        qcFailed: Boolean = false,
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = timestamp,
            temperature = temperature,
            condition = "observed",
            locationLat = 37.42,
            locationLon = -122.08,
            distanceKm = 0f,
            stationType = "OFFICIAL",
            api = api,
            fetchedAt = fetchedAt,
            qcFailed = qcFailed,
        )

    private fun minute(hour: Int, minute: Int): Long = BASE_MS + (hour * 60L + minute) * 60_000L

    private companion object {
        const val BASE_MS = 1_800_000_000_000L
    }
}
