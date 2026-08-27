package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.TomorrowIoRealtimeReading
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoActualsTest {

    private val hour = 1_700_000_000_000L / 3_600_000L * 3_600_000L

    private fun row(stationId: String, timestamp: Long, temperature: Float) =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = 37.42,
            locationLon = -122.08,
            distanceKm = 0f,
            stationType = "OFFICIAL",
            api = WeatherSource.TOMORROW_IO.id,
            fetchedAt = timestamp,
        )

    @Test
    fun `realtime replaces recent history only in its overlapping hour`() {
        val result = TomorrowIoActuals.preferRealtimeWithinHour(
            listOf(
                row(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, hour, 60f),
                row(TomorrowIoActuals.REALTIME_STATION_ID, hour + 15 * 60_000L, 64f),
                row(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, hour + 3_600_000L, 66f),
                row("TOMORROW_IO_MAIN", hour + 2 * 3_600_000L, 99f),
            ),
        )

        assertEquals(listOf(64f, 66f), result.map { it.temperature })
        assertEquals(
            listOf(
                TomorrowIoActuals.REALTIME_STATION_ID,
                TomorrowIoActuals.RECENT_HISTORY_STATION_ID,
            ),
            result.map { it.stationId },
        )
    }

    @Test
    fun `temperature merge presents both products as one logical station`() {
        val result = TomorrowIoActuals.forTemperatureSeries(
            listOf(
                row(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, hour, 60f),
                row(TomorrowIoActuals.REALTIME_STATION_ID, hour + 3_600_000L, 64f),
            ),
        )

        assertEquals(listOf(60f, 64f), result.map { it.temperature })
        assertEquals(1, result.map { it.stationId }.distinct().size)
    }

    @Test
    fun `isSyntheticBackfillStation recognises all tomorrow io actuals ids`() {
        val matcher = com.weatherwidget.shared.observations.ObservationSourceMatcher
        val sourceId = WeatherSource.TOMORROW_IO.id

        org.junit.Assert.assertTrue(matcher.isSyntheticBackfillStation(TomorrowIoActuals.REALTIME_STATION_ID, sourceId))
        org.junit.Assert.assertTrue(matcher.isSyntheticBackfillStation(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, sourceId))
        org.junit.Assert.assertTrue(matcher.isSyntheticBackfillStation(TomorrowIoActuals.MERGED_SERIES_STATION_ID, sourceId))
        org.junit.Assert.assertTrue(matcher.isSyntheticBackfillStation("TOMORROW_IO_MAIN", sourceId))
        org.junit.Assert.assertFalse(matcher.isSyntheticBackfillStation("KNUQ", sourceId))
    }

    @Test
    fun `realtime mapping preserves total cover and cloud envelope`() {
        val result = TomorrowIoActuals.toObservation(
            TomorrowIoRealtimeReading(
                temperature = 68f,
                condition = "Partly Cloudy",
                observedAt = hour,
                cloudCover = 56,
                cloudEnvelopeBaseMeters = 1_609,
                cloudEnvelopeTopMeters = 4_828,
            ),
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(56, result.cloudCover)
        assertEquals(1_609, result.cloudEnvelopeBaseMeters)
        assertEquals(4_828, result.cloudEnvelopeTopMeters)
        assertEquals(CloudVerticalKind.TOTAL_ENVELOPE, result.cloudVerticalKind)
    }
}
