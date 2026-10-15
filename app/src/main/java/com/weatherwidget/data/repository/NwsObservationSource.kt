package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.shared.observations.NwsStationHistoryFetcher
import com.weatherwidget.shared.observations.NwsStationLatestFetcher
import com.weatherwidget.util.SharedPreferencesUtil
import javax.inject.Singleton

private const val TAG = "NwsObservationSource"
private const val STATION_CACHE_MAX_AGE_MS = 86_400_000L

internal data class HistoricalStationObservations(
    val entities: List<ObservationEntity>,
    val usedWebFallback: Boolean,
    /**
     * Simple name of the exception the NWS API call threw, or null when it answered. Carried out so
     * the per-station log line can say "we never got an answer" instead of implying an empty
     * station — the two are indistinguishable from [entities] alone.
     */
    val apiFailure: String? = null,
)

@Singleton
class NwsObservationSource(
    context: Context,
    private val nwsApi: NwsApi,
    private val appLogDao: AppLogDao,
    /** The web leg: NWS's own METAR text file (no token). Null = API only. */
    private val metarTextApi: NwsMetarTextApi? = null,
) {
    private val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")

    suspend fun stationsForLocation(
        latitude: Double,
        longitude: Double,
    ): List<NwsApi.StationInfo> = stationsForLocationOutcome(latitude, longitude).valueOrNull().orEmpty()

    /**
     * Like [stationsForLocation], but a lookup that never got an answer (gridpoint or station-list
     * transport failure with no cached list) is [FetchOutcome.Failed], not an empty list. The
     * hourly backfill retries on Failed; an empty answer is a real "no stations here".
     */
    suspend fun stationsForLocationOutcome(
        latitude: Double,
        longitude: Double,
    ): FetchOutcome<List<NwsApi.StationInfo>> {
        val gridPoint = try {
            nwsApi.getGridPoint(latitude, longitude)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NWS grid point for ($latitude, $longitude)", e)
            appLogDao.log(
                "NWS_GRIDPOINT_FAIL",
                "lat=$latitude lon=$longitude error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            return FetchOutcome.failed(e)
        }
        return stationsFromUrlOutcome(gridPoint.observationStationsUrl.orEmpty())
    }

    internal suspend fun stationsFromUrl(stationsUrl: String): List<NwsApi.StationInfo> =
        stationsFromUrlOutcome(stationsUrl).valueOrNull().orEmpty()

    private suspend fun stationsFromUrlOutcome(stationsUrl: String): FetchOutcome<List<NwsApi.StationInfo>> {
        if (stationsUrl.isEmpty()) return FetchOutcome.NoData

        // Cache keyed by the stations URL's hashCode: compact and stable for a single location's
        // gridpoint. Collision risk is negligible (a handful of URLs per install); a wrong hit
        // would at worst serve a stale station list until the 24h cache expiry refreshes it.
        val stationsKey = "observation_stations_v4_${stationsUrl.hashCode()}"
        val timeKey = "observation_stations_time_v4_${stationsUrl.hashCode()}"
        val cachedString = prefs.getString(stationsKey, null)
        val cachedStations = cachedString
            ?.split("|")
            ?.mapNotNull(NwsApi.Companion::decodeStationInfo)
            .orEmpty()
        val lastUpdateMs = prefs.getLong(timeKey, 0L)
        if (cachedString != null && System.currentTimeMillis() - lastUpdateMs < STATION_CACHE_MAX_AGE_MS) {
            return FetchOutcome.Success(cachedStations)
        }

        val fetched = try {
            nwsApi.getObservationStations(stationsUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedStations.isNotEmpty()) {
                appLogDao.log(
                    "NWS_STATION_CACHE_STALE",
                    "urlHash=${stationsUrl.hashCode()} count=${cachedStations.size} " +
                        "error=${e::class.simpleName}:${e.message}",
                    "WARN",
                )
                Log.w(TAG, "Station refresh failed; using ${cachedStations.size} cached stations", e)
                return FetchOutcome.Success(cachedStations)
            }
            appLogDao.log(
                "NWS_STATION_LIST_FAIL",
                "urlHash=${stationsUrl.hashCode()} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "Failed to fetch NWS station list", e)
            return FetchOutcome.failed(e)
        }

        if (fetched.isNotEmpty()) {
            prefs.edit()
                .putString(
                    stationsKey,
                    fetched.joinToString("|", transform = NwsApi.Companion::encodeStationInfo),
                )
                .putLong(timeKey, System.currentTimeMillis())
                .apply()
        }
        return FetchOutcome.Success(fetched)
    }

    /**
     * One station's latest observation, merged with its web rows: the step desktop runs too
     * (`:shared` [NwsStationLatestFetcher]; plans/261013-shared-current-conditions-fetch.md Phase 2).
     */
    internal val latestFetcher = NwsStationLatestFetcher(
        latestObservation = { stationId -> nwsApi.getLatestObservationDetailedResult(stationId) },
        log = { tag, message, level -> appLogDao.log(tag, message, level) },
    )

    /**
     * Raw `api.weather.gov/stations/{id}/observations?start=&end=` series, with no web
     * fallback and no storage. [fetchHistorical] deliberately substitutes web readings when the
     * API looks stale; the NWS *daily extreme* must not, or the value stops being NWS's own.
     */
    internal suspend fun fetchApiObservationsOnly(
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        startTime: String,
        endTime: String,
    ): List<ObservationEntity> =
        nwsApi.getObservations(stationInfo.id, startTime, endTime)
            .map { toEntity(it, stationInfo, latitude, longitude) }

    /** One station's history window with the web fallback: the step desktop runs too (`:shared`). */
    private val historyFetcher = NwsStationHistoryFetcher(
        observations = { station, start, end -> nwsApi.getObservations(station.id, start, end) },
        log = { tag, message, level -> appLogDao.log(tag, message, level) },
    )

    internal suspend fun fetchHistorical(
        stationInfo: NwsApi.StationInfo,
        stationIndex: Int,
        latitude: Double,
        longitude: Double,
        startTime: String,
        endTime: String,
        webWindowMinutes: Long,
        fallbackLogTag: String,
    ): HistoricalStationObservations {
        // The web fallback is NWS's own latest-METAR file, as on desktop: one reading, the newest
        // (plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md; it was Synoptic, a token service).
        val web: (suspend () -> List<com.weatherwidget.data.model.ObservationReading>?)? = metarTextApi?.let { api ->
            {
                com.weatherwidget.shared.observations.NwsMetarTextWeb.fetchReading(api, stationInfo, latitude, longitude)
                    .valueOrNull()
                    ?.map { com.weatherwidget.shared.observations.NwsStationLatestFetcher.asNwsWebRow(it, stationInfo, latitude, longitude) }
            }
        }
        val history = historyFetcher.fetch(
            stationInfo, stationIndex, latitude, longitude, startTime, endTime, web, fallbackLogTag,
        )
        return HistoricalStationObservations(
            history.rows.map { it.toEntity() },
            usedWebFallback = history.usedWebFallback,
            apiFailure = history.apiFailure,
        )
    }

    internal fun toEntity(
        observation: NwsApi.Observation,
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        isWebFallback: Boolean = false,
    ): ObservationEntity {
        // Shared mapping (units, name fallback, hardened timestamp parse, METAR→low cloud rule);
        // Android adds only its storage-keying concern — the fetch site snapped to the shared
        // write grid, so one physical site is keyed identically regardless of who resolved it.
        val reading = com.weatherwidget.shared.observations.NwsObservationMapper.toReading(
            observation, stationInfo, latitude, longitude, isWebFallback,
        )
        return ObservationEntity(
            stationId = reading.stationId,
            stationName = reading.stationName,
            timestamp = reading.timestamp,
            temperature = reading.temperature,
            condition = reading.condition,
            locationLat = LocationMatch.quantize(reading.locationLat),
            locationLon = LocationMatch.quantize(reading.locationLon),
            distanceKm = reading.distanceKm,
            stationType = reading.stationType,
            maxTempLast24h = reading.maxTempLast24h,
            minTempLast24h = reading.minTempLast24h,
            api = reading.api,
            precipAmountMm = reading.precipAmountMm,
            isWebFallback = reading.isWebFallback,
            qcFailed = reading.qcFailed,
            cloudCover = reading.cloudCover,
            cloudCoverLow = reading.cloudCoverLow,
            isMetar = reading.isMetar,
            rawMetar = reading.rawMetar,
            cloudCoverMid = reading.cloudCoverMid,
            cloudCoverHigh = reading.cloudCoverHigh,
            cloudBaseLowMeters = reading.cloudBaseLowMeters,
            cloudBaseMidMeters = reading.cloudBaseMidMeters,
            cloudBaseHighMeters = reading.cloudBaseHighMeters,
            cloudEnvelopeBaseMeters = reading.cloudEnvelopeBaseMeters,
            cloudEnvelopeTopMeters = reading.cloudEnvelopeTopMeters,
            cloudVerticalKind = reading.cloudVerticalKind,
        )
    }
}
