package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals

/**
 * Converts provider rows into the deterministic logical-station timeline consumed by every
 * temperature blend and graph.
 *
 * Provider adapters retain their native observation timestamps and persisted provenance. This
 * normalizer resolves true duplicates before they reach the graph. Every provider preserves its
 * native timestamps. Tomorrow.io accepts only its five-minute analysis product; retired realtime
 * and hourly-history rows cannot re-enter the graph from an older database.
 */
object ObservationTimelineNormalizer {

    private data class TimelineKey(
        val stationId: String,
        val timestamp: Long,
    )

    fun normalize(
        readings: List<ObservationReading>,
        providerId: String,
    ): List<ObservationReading> =
        readings.asSequence()
            .filter { !it.qcFailed && it.api == providerId }
            .filter { providerId != WeatherSource.TOMORROW_IO.id || TomorrowIoActuals.isAllowedStation(it.stationId) }
            .groupBy { TimelineKey(logicalStationId(it, providerId), it.timestamp) }
            .values
            .mapNotNull { collisions -> collisions.maxWithOrNull(collisionPreference) }
            .map { normalizeLogicalStation(it, providerId) }
            .sortedWith(outputOrder)

    private fun logicalStationId(reading: ObservationReading, providerId: String): String =
        if (providerId == WeatherSource.TOMORROW_IO.id) {
            TomorrowIoActuals.MERGED_SERIES_STATION_ID
        } else {
            reading.stationId
        }

    private fun normalizeLogicalStation(
        reading: ObservationReading,
        providerId: String,
    ): ObservationReading =
        if (providerId == WeatherSource.TOMORROW_IO.id) {
            reading.copy(
                stationId = TomorrowIoActuals.MERGED_SERIES_STATION_ID,
                stationName = TomorrowIoActuals.MERGED_SERIES_STATION_NAME,
            )
        } else {
            reading
        }

    private val collisionPreference =
        compareBy<ObservationReading> { it.fetchedAt }
            .thenBy { it.stationId }
            .thenBy { it.stationName }
            .thenBy { it.temperature }
            .thenBy { it.condition }
            .thenBy { it.locationLat }
            .thenBy { it.locationLon }
            .thenBy { it.distanceKm }
            .thenBy { it.stationType }

    private val outputOrder =
        compareBy<ObservationReading> { it.timestamp }
            .thenBy { it.stationId }
            .thenBy { it.locationLat }
            .thenBy { it.locationLon }
            .thenBy { it.fetchedAt }
            .thenBy { it.temperature }
}
