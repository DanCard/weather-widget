package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.data.remote.shouldTouchObservationFetchedAt
import com.weatherwidget.shared.observations.MetarSkyCover
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
    /**
     * The full NWS API observation, stored *alongside* the chosen Synoptic row when the web reading
     * won [LatestObservationMerge.preferNewest] on temperature freshness.
     *
     * The merge is a TEMPERATURE decision, but it swaps the whole row: Synoptic republishes 20-60
     * minutes ahead of the API yet carries no 24h extremes and no precipitation (see `SynopticApi` —
     * both are null). A plain swap therefore discards what the API row knew. Measured 2026-08-21:
     * KNUQ (Moffett, 3.8 km, the nearest official station) stored 23 consecutive cloud-less rows
     * while its own METARs read `OVC 400` throughout, leaving the entire cloud blend to KSJC 15.9 km
     * away.
     *
     * Sky condition used to be on that list of things Synoptic "carries none of". It was never true
     * — Synoptic returns the raw report in `metar_set_1` and the parser simply ignored it, which is
     * the other half of the same KNUQ measurement. `SynopticApi` now parses it, so a web row carries
     * its own cloud. This row still earns its place for the extremes and precipitation, and as the
     * API's independent account of the same hour.
     *
     * This is a real observation row, not a cloud-only stub, deliberately: it keeps the API row's
     * own timestamp (the cloud value must bucket to the API observation hour, not the web row's
     * newer time) and its temperature / 24h extremes / precipitation, all of which the web swap was
     * discarding just as surely as the sky. The temperature never wins a "latest" read (the web row
     * is strictly newer), and the row's own primary key — `(stationId, timestamp, locationLat,
     * locationLon)` — means a historical backfill that files the same report REPLACEs rather than
     * duplicates it, so nothing is double-counted.
     *
     * Null when the API row was chosen anyway, when there is no API row, or when it carried no
     * sky condition to preserve.
     */
    val cloudCarrier: ObservationEntity?,
    val shouldTouchFetchedAt: Boolean,
    val nwsFailureReason: String?,
    val synopticFailureReason: String?,
)

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
        var cloudCarrierEntity: ObservationEntity? = null
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
                // A web win is a TEMPERATURE decision, but the swap throws away the whole API row
                // — sky condition, 24h extremes, and precipitation alike, none of which Synoptic
                // carries. Keep the API row as a second observation (its own timestamp keeps it a
                // distinct primary key) so those fields survive; only the temperature stays with the
                // fresher web row.
                if (merge.chosenIsWeb && apiObservation != null &&
                    MetarSkyCover.lowPercent(apiObservation.cloudLayers) != null
                ) {
                    cloudCarrierEntity =
                        toEntity(apiObservation, stationInfo, latitude, longitude, isWebFallback = false)
                }
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
            cloudCarrier = cloudCarrierEntity,
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
        // Held alongside the list because the catch below flattens a failure into an empty result:
        // downstream, "the request died" and "the station reported nothing" look identical, and the
        // fallback reason used to assert the second one for both.
        var apiFailure: String? = null
        val nwsObservations = try {
            nwsApi.getObservations(stationInfo.id, startTime, endTime)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            apiFailure = e::class.simpleName ?: "Exception"
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
                apiFailure = apiFailure,
            )
        }

        val reason = ObservationFallbackPolicy.fallbackReason(
            nwsObservations.size,
            apiFetchFailed = apiFailure != null,
        )
        val reasonDetail = apiFailure?.let { "$reason:$it" } ?: reason
        appLogDao.log(fallbackLogTag, "station=${stationInfo.id} reason=$reasonDetail", "INFO")
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
                apiFailure = apiFailure,
            )
        } else {
            HistoricalStationObservations(
                nwsObservations.map { toEntity(it, stationInfo, latitude, longitude) },
                usedWebFallback = false,
                apiFailure = apiFailure,
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
        )
    }

    private fun NwsApi.Observation?.observedAtMillis(): Long? =
        this?.let {
            runCatching { OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull()
        }
}
