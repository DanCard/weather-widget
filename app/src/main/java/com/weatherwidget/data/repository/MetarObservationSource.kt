package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.shared.observations.MetarObservationFetcher
import com.weatherwidget.shared.observations.METAR_STATION_CACHE_MAX_AGE_MS
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Android binding for the shared [MetarObservationFetcher]: a SharedPreferences-backed station
 * cache, the `app_logs` sink, and the conversion to Room's [ObservationEntity].
 *
 * The fetching itself lives in `:shared` because desktop needs exactly the same thing — METAR is the
 * default borrowed actuals provider, and while this logic was Android-only the desktop app had no
 * METAR rows and therefore no actual curve for any forecast-only source.
 */
class MetarObservationSource(
    context: Context,
    api: AviationWeatherApi,
    private val appLogDao: AppLogDao,
) {
    private val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")

    private val cache = object : MetarObservationFetcher.StationCache {
        // The timestamp lives under its own key rather than inside the payload so an older build's
        // cache entry reads back as "saved at 0" — stale, refetched — instead of failing to parse.
        private fun timeKey(key: String) = key.replace("metar_stations_v1_", "metar_stations_time_v1_")

        override fun read(key: String): MetarObservationFetcher.StationCache.Entry? {
            val encoded = prefs.getString(key, null) ?: return null
            return MetarObservationFetcher.StationCache.Entry(encoded, prefs.getLong(timeKey(key), 0L))
        }

        override fun write(key: String, encoded: String, savedAtMs: Long) {
            prefs.edit().putString(key, encoded).putLong(timeKey(key), savedAtMs).apply()
        }
    }

    private val fetcher = MetarObservationFetcher(api, cache) { tag, message, level ->
        appLogDao.log(tag, message, level)
    }

    suspend fun stationsForLocation(
        latitude: Double,
        longitude: Double,
        limit: Int = AviationWeatherStationFilter.DEFAULT_LIMIT,
    ): List<AviationWeatherStationFilter.RankedStation> =
        fetcher.stationsForLocation(latitude, longitude, limit)

    suspend fun fetchObservations(
        latitude: Double,
        longitude: Double,
        hours: Int = 2,
        limit: Int = AviationWeatherStationFilter.DEFAULT_LIMIT,
    ): List<ObservationEntity> =
        fetcher.fetchObservations(latitude, longitude, hours, limit).map { it.toEntity() }

    suspend fun fetchObservationsResult(
        latitude: Double,
        longitude: Double,
        hours: Int = 2,
        limit: Int = AviationWeatherStationFilter.DEFAULT_LIMIT,
    ): FetchOutcome<List<ObservationEntity>> =
        when (val outcome = fetcher.fetchObservationsResult(latitude, longitude, hours, limit)) {
            is FetchOutcome.Success -> FetchOutcome.Success(outcome.value.map { it.toEntity() })
            is FetchOutcome.NoData -> FetchOutcome.NoData
            is FetchOutcome.Failed -> outcome
        }

    private fun com.weatherwidget.data.model.ObservationReading.toEntity() = ObservationEntity(
        stationId = stationId,
        stationName = stationName,
        timestamp = timestamp,
        temperature = temperature,
        condition = condition,
        locationLat = LocationMatch.quantize(locationLat),
        locationLon = LocationMatch.quantize(locationLon),
        distanceKm = distanceKm,
        stationType = stationType,
        maxTempLast24h = maxTempLast24h,
        minTempLast24h = minTempLast24h,
        api = api,
        precipAmountMm = precipAmountMm,
        isWebFallback = isWebFallback,
        qcFailed = qcFailed,
        cloudCover = cloudCover,
        cloudCoverLow = cloudCoverLow,
        isMetar = isMetar,
        rawMetar = rawMetar,
        cloudCoverMid = cloudCoverMid,
        cloudCoverHigh = cloudCoverHigh,
        cloudBaseLowMeters = cloudBaseLowMeters,
        cloudBaseMidMeters = cloudBaseMidMeters,
        cloudBaseHighMeters = cloudBaseHighMeters,
        cloudEnvelopeBaseMeters = cloudEnvelopeBaseMeters,
        cloudEnvelopeTopMeters = cloudEnvelopeTopMeters,
        cloudVerticalKind = cloudVerticalKind,
    )

    companion object {
        /** Retained for callers that reason about the discovery cache lifetime. */
        const val STATION_CACHE_MAX_AGE_MS = METAR_STATION_CACHE_MAX_AGE_MS
    }
}
