package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.TomorrowIoRealtimeReading
import com.weatherwidget.shared.observations.CloudHourBucket

/** Source-of-truth provenance for Tomorrow.io actual temperature and cloud readings. */
object TomorrowIoActuals {
    const val RECENT_HISTORY_STATION_ID = "TOMORROW_IO_RECENT_HISTORY"
    const val RECENT_HISTORY_STATION_NAME = "Tmrw: Recent History"
    const val REALTIME_STATION_ID = "TOMORROW_IO_REALTIME"
    const val REALTIME_STATION_NAME = "Tmrw: Realtime"
    // This id is in-memory only for the normalized single-feed temperature series.
    // Persisted rows retain the two explicit provenance ids above.
    const val MERGED_SERIES_STATION_ID = "Tmrw"
    const val MERGED_SERIES_STATION_NAME = "Tmrw: Actuals"

    fun isAllowedStation(stationId: String): Boolean =
        stationId == RECENT_HISTORY_STATION_ID || stationId == REALTIME_STATION_ID

    fun isRealtime(stationId: String): Boolean = stationId == REALTIME_STATION_ID

    /**
     * Resolve the two stored products into one logical series. Every realtime reading is retained;
     * the hourly recent-history row is used only when its nearest-hour bucket contains no realtime
     * sample. The station id is normalized only in memory so the temperature blender cannot treat
     * the two products as competing physical stations; persisted provenance remains untouched.
     */
    fun forTemperatureSeries(readings: List<ObservationReading>): List<ObservationReading> =
        preferRealtimeWithinHour(readings).map { row ->
            row.copy(
                stationId = MERGED_SERIES_STATION_ID,
                stationName = MERGED_SERIES_STATION_NAME,
            )
        }

    /** Return accepted Tomorrow rows with realtime replacing recent history in overlapping hours. */
    fun preferRealtimeWithinHour(readings: List<ObservationReading>): List<ObservationReading> =
        readings.asSequence()
            .filter { it.api == WeatherSource.TOMORROW_IO.id && isAllowedStation(it.stationId) }
            .groupBy { CloudHourBucket.startMsOf(it.timestamp) }
            .toSortedMap()
            .values
            .flatMap { rows ->
                rows.filter { isRealtime(it.stationId) }.ifEmpty {
                    rows.filter { it.stationId == RECENT_HISTORY_STATION_ID }
                }
            }
            .sortedWith(compareBy({ it.timestamp }, { it.stationId }))

    fun toObservation(
        reading: TomorrowIoRealtimeReading,
        latitude: Double,
        longitude: Double,
        fetchedAt: Long = System.currentTimeMillis(),
    ): ObservationReading =
        ObservationReading(
            stationId = REALTIME_STATION_ID,
            stationName = REALTIME_STATION_NAME,
            timestamp = reading.observedAt,
            temperature = reading.temperature,
            condition = reading.condition,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = "OFFICIAL",
            api = WeatherSource.TOMORROW_IO.id,
            fetchedAt = fetchedAt,
            cloudCover = reading.cloudCover,
            cloudEnvelopeBaseMeters = reading.cloudEnvelopeBaseMeters,
            cloudEnvelopeTopMeters = reading.cloudEnvelopeTopMeters,
            cloudVerticalKind = if (
                reading.cloudEnvelopeBaseMeters != null || reading.cloudEnvelopeTopMeters != null
            ) {
                CloudVerticalKind.TOTAL_ENVELOPE
            } else {
                CloudVerticalKind.NONE
            },
        )
}
