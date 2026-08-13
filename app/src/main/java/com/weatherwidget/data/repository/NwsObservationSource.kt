package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.data.remote.shouldTouchObservationFetchedAt
import com.weatherwidget.shared.observations.LatestObservationMerge
import com.weatherwidget.shared.observations.ObservationFallbackPolicy
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.OffsetDateTime
import javax.inject.Singleton

private const val TAG = "NwsObservationSource"
private const val STATION_CACHE_MAX_AGE_MS = 86_400_000L

internal data class LatestStationObservation(
    val chosen: ObservationEntity?,
    val qcFlagged: List<ObservationEntity>,
    val shouldTouchFetchedAt: Boolean,
    val nwsFailureReason: String?,
    val synopticFailureReason: String?,
)

internal data class HistoricalStationObservations(
    val entities: List<ObservationEntity>,
    val usedWebFallback: Boolean,
)

@Singleton
class NwsObservationSource(
    context: Context,
    private val nwsApi: NwsApi,
    private val appLogDao: AppLogDao,
    private val synopticApi: SynopticApi? = null,
) {
    private val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")

    suspend fun stationsForLocation(
        latitude: Double,
        longitude: Double,
    ): List<NwsApi.StationInfo> {
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
            return emptyList()
        }
        return stationsFromUrl(gridPoint.observationStationsUrl.orEmpty())
    }

    internal suspend fun stationsFromUrl(stationsUrl: String): List<NwsApi.StationInfo> {
        if (stationsUrl.isEmpty()) return emptyList()

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
            return cachedStations
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
                return cachedStations
            }
            appLogDao.log(
                "NWS_STATION_LIST_FAIL",
                "urlHash=${stationsUrl.hashCode()} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "Failed to fetch NWS station list", e)
            return emptyList()
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
        return fetched
    }

    internal suspend fun fetchLatest(
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        stationIndex: Int,
    ): LatestStationObservation {
        val nwsOutcome = nwsApi.getLatestObservationDetailedResult(stationInfo.id)
        val apiObservation = nwsOutcome.valueOrNull()
        val nowMs = System.currentTimeMillis()
        val apiObservedAtMs = apiObservation.observedAtMillis()
        val fetchWebForUse = ObservationFallbackPolicy.shouldFetchWeb(stationIndex)
        val logWebMetrics = ObservationFallbackPolicy.shouldLogWebMetrics(stationIndex)

        var chosenIsWeb = false
        var synopticOutcome: FetchOutcome<List<NwsApi.Observation>>? = null
        var flaggedEntities = emptyList<ObservationEntity>()
        val chosen = if (synopticApi != null && (fetchWebForUse || logWebMetrics)) {
            val windowMinutes = if (fetchWebForUse) {
                ObservationFallbackPolicy.webFallbackWindowMinutes(apiObservedAtMs, nowMs)
            } else {
                ObservationFallbackPolicy.METRICS_WINDOW_MINUTES
            }
            synopticOutcome = synopticApi.fetchSynopticObservations(
                stationInfo.id,
                windowMinutes,
                stationInfo.name,
            )
            val webReadings = synopticOutcome.valueOrNull().orEmpty()
            val merge = LatestObservationMerge.preferNewest(
                apiLatest = apiObservation,
                apiNewestMs = apiObservedAtMs,
                webReadings = webReadings,
                isQcFailed = { it.qcFailed },
                observedAtMillis = { it.observedAtMillis() },
            )
            val apiNewestMs = merge.apiNewestMs
            val webNewestMs = merge.webNewestMs
            val deltaMinutes = if (apiNewestMs != null && webNewestMs != null) {
                (webNewestMs - apiNewestMs) / 60_000L
            } else {
                null
            }
            val webUsableLatest = webReadings.lastOrNull { !it.qcFailed }
            appLogDao.log(
                "OBS_WEB_API_DELTA",
                "station=${stationInfo.id} index=$stationIndex tier=${if (fetchWebForUse) "use" else "metrics"} " +
                    "apiNewestMs=${merge.apiNewestMs} webNewestMs=${merge.webNewestMs} " +
                    "deltaMin=$deltaMinutes apiTempC=${apiObservation?.temperatureCelsius} " +
                    "webTempC=${webUsableLatest?.temperatureCelsius} " +
                    "webQcFailed=${webReadings.any { it.qcFailed }} " +
                    "chosen=${if (merge.chosenIsWeb) "web" else "api"}",
                "INFO",
            )
            if (fetchWebForUse) {
                flaggedEntities = webReadings
                    .filter { it.qcFailed }
                    .map { toEntity(it, stationInfo, latitude, longitude, isWebFallback = true) }
                chosenIsWeb = merge.chosenIsWeb
                merge.chosen
            } else {
                apiObservation
            }
        } else {
            apiObservation
        }

        return LatestStationObservation(
            chosen = chosen?.let {
                toEntity(it, stationInfo, latitude, longitude, isWebFallback = chosenIsWeb)
            },
            qcFlagged = flaggedEntities,
            shouldTouchFetchedAt = chosen == null && shouldTouchObservationFetchedAt(
                nwsOutcome,
                synopticOutcome,
            ),
            nwsFailureReason = (nwsOutcome as? FetchOutcome.Failed)?.reason,
            synopticFailureReason = (synopticOutcome as? FetchOutcome.Failed)?.reason,
        )
    }

    /**
     * Raw `api.weather.gov/stations/{id}/observations?start=&end=` series, with no Synoptic
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
        val nwsObservations = try {
            nwsApi.getObservations(stationInfo.id, startTime, endTime)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "NWS_HISTORY_FETCH_FAIL",
                "station=${stationInfo.id} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.w(TAG, "NWS history fetch failed for ${stationInfo.id}", e)
            emptyList()
        }
        val newestObservationMs = nwsObservations.mapNotNull { it.observedAtMillis() }.maxOrNull()
        val useWebFallback = synopticApi != null &&
            ObservationFallbackPolicy.shouldUseWebFallback(
                stationIndex,
                newestObservationMs,
                System.currentTimeMillis(),
            )
        if (!useWebFallback) {
            return HistoricalStationObservations(
                nwsObservations.map { toEntity(it, stationInfo, latitude, longitude) },
                usedWebFallback = false,
            )
        }

        val reason = ObservationFallbackPolicy.fallbackReason(nwsObservations.size)
        appLogDao.log(fallbackLogTag, "station=${stationInfo.id} reason=$reason", "INFO")
        val webOutcome = synopticApi.fetchSynopticObservations(
            stationInfo.id,
            webWindowMinutes,
            stationInfo.name,
        )
        val webReadings = webOutcome.valueOrNull()
        return if (webReadings != null) {
            HistoricalStationObservations(
                webReadings.map {
                    toEntity(it, stationInfo, latitude, longitude, isWebFallback = true)
                },
                usedWebFallback = true,
            )
        } else {
            HistoricalStationObservations(
                nwsObservations.map { toEntity(it, stationInfo, latitude, longitude) },
                usedWebFallback = false,
            )
        }
    }

    internal fun toEntity(
        observation: NwsApi.Observation,
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        isWebFallback: Boolean = false,
    ): ObservationEntity {
        val distanceMeters = FloatArray(1)
        Location.distanceBetween(
            latitude,
            longitude,
            stationInfo.lat,
            stationInfo.lon,
            distanceMeters,
        )
        return ObservationEntity(
            stationId = stationInfo.id,
            stationName = observation.stationName.ifEmpty { stationInfo.name },
            timestamp = OffsetDateTime.parse(observation.timestamp).toInstant().toEpochMilli(),
            temperature = (observation.temperatureCelsius * 1.8f) + 32f,
            condition = observation.textDescription,
            locationLat = LocationMatch.quantize(latitude),
            locationLon = LocationMatch.quantize(longitude),
            distanceKm = distanceMeters[0] / 1000f,
            stationType = stationInfo.type.name,
            maxTempLast24h = observation.maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            minTempLast24h = observation.minTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            api = WeatherSource.NWS.id,
            precipAmountMm = observation.precipLastHourMm,
            isWebFallback = isWebFallback,
            qcFailed = observation.qcFailed,
        )
    }

    private fun NwsApi.Observation?.observedAtMillis(): Long? =
        this?.let {
            runCatching { OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull()
        }
}
