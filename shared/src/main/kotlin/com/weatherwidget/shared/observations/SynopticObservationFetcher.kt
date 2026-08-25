package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.shared.util.Log

private const val TAG = "SynopticObsFetcher"

/**
 * Fetches Synoptic / MesoWest observations and returns them as [ObservationReading]s
 * under `api = "SYNOPTIC"`.
 */
class SynopticObservationFetcher(
    private val api: SynopticApi,
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
) {
    suspend fun fetchObservations(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = DEFAULT_RADIUS_MILES,
        hours: Int = 2,
        limit: Int = DEFAULT_LIMIT,
    ): List<ObservationReading> =
        fetchObservationsResult(latitude, longitude, radiusMiles, hours, limit).valueOrNull().orEmpty()

    suspend fun fetchObservationsResult(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = DEFAULT_RADIUS_MILES,
        hours: Int = 2,
        limit: Int = DEFAULT_LIMIT,
    ): FetchOutcome<List<ObservationReading>> {
        if (!api.isConfigured) return FetchOutcome.Failed("synoptic: no token configured")
        val recentMinutes = (hours * 60L).coerceAtLeast(120L)
        return when (val outcome = api.fetchRadiusTimeseries(latitude, longitude, radiusMiles, recentMinutes)) {
            is FetchOutcome.Success -> {
                val stations = outcome.value.sortedBy { it.distanceKm }.take(limit)
                val readings = mutableListOf<ObservationReading>()
                for (station in stations) {
                    for (obs in station.observations) {
                        val reading = NwsObservationMapper.toReading(
                            observation = obs,
                            station = station.info,
                            siteLat = latitude,
                            siteLon = longitude,
                            isWebFallback = false,
                            api = WeatherSource.SYNOPTIC.id,
                        )
                        readings.add(reading)
                    }
                }
                log(
                    "SYNOPTIC_FETCH",
                    "lat=$latitude lon=$longitude stations=${stations.size} hours=$hours " +
                        "rows=${outcome.value.sumOf { it.observations.size }} stored=${readings.size} " +
                        "ids=${stations.joinToString(",") { it.info.id }}",
                    "INFO",
                )
                if (readings.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(readings)
            }
            is FetchOutcome.NoData -> {
                log("SYNOPTIC_FETCH_EMPTY", "lat=$latitude lon=$longitude", "INFO")
                FetchOutcome.NoData
            }
            is FetchOutcome.Failed -> {
                Log.w(TAG, "Synoptic radius fetch failed: ${outcome.reason}")
                log("SYNOPTIC_FETCH_FAIL", "lat=$latitude lon=$longitude error=${outcome.reason}", "WARN")
                outcome
            }
        }
    }

    companion object {
        const val DEFAULT_RADIUS_MILES = 25.0
        const val DEFAULT_LIMIT = 10
    }
}
