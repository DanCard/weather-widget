package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherBbox
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.util.Log

private const val TAG = "MetarObservationFetcher"

/** Matches `NwsObservationSource.STATION_CACHE_MAX_AGE_MS`; airports do not move. */
const val METAR_STATION_CACHE_MAX_AGE_MS = 86_400_000L

/**
 * Fetches raw airport METARs from `aviationweather.gov` and returns them as [ObservationReading]s
 * under `api = "METAR"`.
 *
 * Two things distinguish this from the NWS observation path:
 *
 * - **It works outside the United States.** NWS discovery goes through `/points`, which fails
 *   abroad, leaving a non-US user with no station observations from any source. Discovery here is a
 *   latitude/longitude box, which works anywhere.
 * - **One request covers every station.** `?ids=A,B,C,D,E` returns all of them with history; the
 *   NWS path issues one request per station per cycle.
 *
 * Rows are stored under their own provenance and never merged into NWS's — see
 * `plans/260823-aviationweather-metar-transport.md` D0/D5. Nothing falls back across sources
 * (`no_cross_source_fallback`).
 *
 * Lives in `:shared` because METAR is the **default borrowed actuals provider**
 * ([ActualsProviderResolver.DEFAULT_PROVIDER]), so a platform that cannot fetch it draws no actual
 * curve at all for every forecast-only source. That is precisely what desktop did while this class
 * was Android-only: it held 51,991 NWS rows and not one METAR row, and Open-Meteo's mercury line was
 * simply absent. The two platform-specific pieces — where the discovery cache lives and where log
 * lines go — are injected rather than ported.
 */
class MetarObservationFetcher(
    private val api: AviationWeatherApi,
    private val cache: StationCache,
    // Suspending because Android's sink is a Room DAO (`AppLogDao.log`); every call site here is
    // already inside a suspend function, and a non-suspending sink satisfies it unchanged.
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
) {

    /**
     * The 24-hour station-discovery cache, keyed by quantized site.
     *
     * An interface rather than a map because the two platforms disagree about durability: Android
     * persists to SharedPreferences, desktop keeps it in memory (its config file is JSON with known
     * write races — see `desktop_config_write_races` — and re-discovering once per process start is
     * cheaper than another writer on that file).
     */
    interface StationCache {
        /** Encoded payload and the epoch-millis it was written, or null when absent. */
        fun read(key: String): Entry?
        fun write(key: String, encoded: String, savedAtMs: Long)

        data class Entry(val encoded: String, val savedAtMs: Long)
    }

    /**
     * The METAR-reporting stations nearest [latitude]/[longitude], cached for 24 hours.
     *
     * Walks [AviationWeatherBbox]'s expansion ladder until enough stations are found, so a rural
     * location searches wider rather than coming back empty. A partial result at the widest box is
     * returned as-is: three stations is worse than five but far better than none.
     */
    suspend fun stationsForLocation(
        latitude: Double,
        longitude: Double,
        limit: Int = AviationWeatherStationFilter.DEFAULT_LIMIT,
    ): List<AviationWeatherStationFilter.RankedStation> {
        val key = cacheKey(latitude, longitude)
        val entry = cache.read(key)
        val cached = entry?.encoded?.let(MetarStationCacheCodec::decode).orEmpty()
        val age = System.currentTimeMillis() - (entry?.savedAtMs ?: 0L)
        if (cached.isNotEmpty() && age < METAR_STATION_CACHE_MAX_AGE_MS) return cached.take(limit)

        var lastFailure: String? = null
        var step = 0
        while (true) {
            val bbox = AviationWeatherBbox.forLocation(latitude, longitude, step)
            when (val outcome = api.fetchStations(bbox)) {
                is FetchOutcome.Success -> {
                    val ranked = AviationWeatherStationFilter.nearest(outcome.value, latitude, longitude, limit)
                    // Stop as soon as the box holds enough, or once it cannot grow any further.
                    if (ranked.size >= limit || AviationWeatherBbox.isMaxStep(step)) {
                        if (ranked.isEmpty()) {
                            log("METAR_STATIONS_EMPTY", "lat=$latitude lon=$longitude bbox=$bbox step=$step", "WARN")
                            return cached.take(limit)
                        }
                        cache.write(key, MetarStationCacheCodec.encode(ranked), System.currentTimeMillis())
                        log(
                            "METAR_STATIONS",
                            "lat=$latitude lon=$longitude step=$step count=${ranked.size} " +
                                "ids=${ranked.joinToString(",") { it.info.id }}",
                            "INFO",
                        )
                        return ranked
                    }
                }
                is FetchOutcome.NoData -> Unit // empty box: widen and try again
                is FetchOutcome.Failed -> lastFailure = outcome.reason
            }
            if (AviationWeatherBbox.isMaxStep(step)) break
            step++
        }

        // Serving a stale list beats serving none: airports do not move, and the alternative is a
        // cycle with no observations at all. Mirrors NWS_STATION_CACHE_STALE.
        if (cached.isNotEmpty()) {
            log(
                "METAR_STATION_CACHE_STALE",
                "lat=$latitude lon=$longitude count=${cached.size} error=$lastFailure",
                "WARN",
            )
            return cached.take(limit)
        }
        log("METAR_STATION_LIST_FAIL", "lat=$latitude lon=$longitude error=$lastFailure", "WARN")
        return emptyList()
    }

    /**
     * Observations for the last [hours] hours across every nearby station, in **one** request.
     *
     * [latitude]/[longitude] are the widget's coordinates, not the station's: observation identity
     * includes the fetch site, and callers quantize to the shared grid on write so one physical
     * place is always keyed identically.
     */
    suspend fun fetchObservations(
        latitude: Double,
        longitude: Double,
        hours: Int = 2,
        limit: Int = AviationWeatherStationFilter.DEFAULT_LIMIT,
    ): List<ObservationReading> {
        val stations = stationsForLocation(latitude, longitude, limit)
        if (stations.isEmpty()) return emptyList()

        val byId = stations.associateBy { it.info.id }
        return when (val outcome = api.fetchMetars(stations.map { it.info.id }, hours)) {
            is FetchOutcome.Success -> {
                val readings = outcome.value.mapNotNull { row ->
                    // A station the request did not ask for cannot be distance-ranked, so it has no
                    // IDW weight and must not be stored — dropping it is the honest outcome.
                    val station = byId[row.stationId] ?: return@mapNotNull null
                    MetarObservationMapper.toReading(row, station, latitude, longitude)
                }
                log(
                    "METAR_FETCH",
                    "lat=$latitude lon=$longitude stations=${stations.size} hours=$hours " +
                        "rows=${outcome.value.size} stored=${readings.size}",
                    "INFO",
                )
                readings
            }
            is FetchOutcome.NoData -> {
                log(
                    "METAR_FETCH_EMPTY",
                    "lat=$latitude lon=$longitude ids=${stations.joinToString(",") { it.info.id }}",
                    "INFO",
                )
                emptyList()
            }
            is FetchOutcome.Failed -> {
                Log.w(TAG, "METAR fetch failed: ${outcome.reason}")
                log("METAR_FETCH_FAIL", "lat=$latitude lon=$longitude error=${outcome.reason}", "WARN")
                emptyList()
            }
        }
    }

    companion object {
        /**
         * Keyed on the quantized site so GPS jitter cannot fragment the cache the way it once
         * fragmented the coordinate-keyed tables.
         */
        fun cacheKey(latitude: Double, longitude: Double): String {
            val keyLat = com.weatherwidget.data.local.LocationMatch.quantize(latitude)
            val keyLon = com.weatherwidget.data.local.LocationMatch.quantize(longitude)
            return "metar_stations_v1_${keyLat}_$keyLon"
        }
    }
}

/**
 * Serialises the discovered station list into the one string that backs the 24-hour discovery cache.
 *
 * Extracted from the fetcher so the round-trip is testable without a platform storage layer — the
 * project's no-mocking strategy prefers pulling the logic out to pulling a framework in.
 *
 * Format: `id \t name \t lat \t lon \t distanceKm \t elevation`, rows joined by `|`. Both
 * delimiters are stripped from the name on write, because a station called `Paris/Le Bourge Arpt,
 * ID, FR` is fine but one containing a tab would silently shift every field after it.
 */
object MetarStationCacheCodec {

    private const val ROW = "|"
    private const val FIELD = "\t"

    fun encode(stations: List<AviationWeatherStationFilter.RankedStation>): String =
        stations.joinToString(ROW) { s ->
            listOf(
                s.info.id,
                s.info.name.replace('\t', ' ').replace('|', ' '),
                s.info.lat.toString(),
                s.info.lon.toString(),
                s.distanceKm.toString(),
                s.elevationMeters?.toString().orEmpty(),
            ).joinToString(FIELD)
        }

    /**
     * Rows that cannot be read are skipped, not defaulted. A station with an unparseable coordinate
     * has no usable IDW weight, and inventing one would be worse than losing the row — the cache
     * refreshes within 24 hours regardless.
     */
    fun decode(encoded: String): List<AviationWeatherStationFilter.RankedStation> =
        encoded.split(ROW).mapNotNull { row ->
            val f = row.split(FIELD)
            if (f.size < 5) return@mapNotNull null
            val lat = f[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[3].toDoubleOrNull() ?: return@mapNotNull null
            val distance = f[4].toDoubleOrNull() ?: return@mapNotNull null
            if (f[0].isBlank()) return@mapNotNull null
            AviationWeatherStationFilter.RankedStation(
                info = NwsApi.StationInfo(
                    id = f[0],
                    name = f[1],
                    lat = lat,
                    lon = lon,
                    // Every station in this feed is an airport reporting station; typing one
                    // PERSONAL would wrongly apply the personal-station discount in the blend.
                    type = NwsApi.StationType.OFFICIAL,
                ),
                distanceKm = distance,
                elevationMeters = f.getOrNull(5)?.toDoubleOrNull(),
            )
        }
}
