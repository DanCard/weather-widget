package com.weatherwidget.shared.observations

import com.weatherwidget.data.local.ObservationSiteMerge
import com.weatherwidget.data.model.WeatherSource

/**
 * The Observations screen's stations list, the whole rule: the Android Current Observations screen
 * and the desktop Observations window both run it; each supplies only its reads ([Host]).
 *
 * - **Scoped to the active site.** Rows record the location they were fetched under and the 24 h window
 *   can straddle a move, so the list reads the proximity box at the active location, keeps the
 *   displayed source's station rows, and merges the fetch-location fragments within ~1 km
 *   ([ObservationSiteMerge] — the observation rule the graph and blends use, so the list names the
 *   graph's stations); with none that close, the nearest site that has some. A neighbouring site
 *   visited earlier drops out, a short walk's fragment merges in, and a fragment holding only backfill
 *   rows cannot empty the list. Unscoped only with no location. (Desktop read every row since the
 *   cutoff, anywhere — after a move it listed the last place's stations too; Android kept only the
 *   single nearest fragment; `plans/261027-android-desktop-duplicate-code-audit-round-three.md` §1.)
 * - **Recent, else the newest stored** ([StaleObservationFallback]): never a fetch trigger.
 * - The latest reading per station, nearest first; a non-NWS source with no stations falls back to
 *   its own main reading ([Host.latestMainReading]).
 */
object StationsList {
    enum class Subtitle { NONE_FOUND, STALE, NEARBY_STATIONS, LATEST_READING }

    data class Result<R>(
        val rows: List<R>,
        val subtitle: Subtitle,
        /** Set with [Subtitle.STALE]: the age of the newest row shown. */
        val staleAgeMs: Long?,
        /** The scoping diagnostic for [line]: location, box rows, sites, site rows. */
        val diagnostic: String,
    )

    interface Host<R> {
        fun stationId(row: R): String
        fun api(row: R): String
        fun timestamp(row: R): Long
        fun distanceKm(row: R): Float
        fun latitude(row: R): Double
        fun longitude(row: R): Double
        fun fetchedAt(row: R): Long

        /** Every row fetched within the proximity box at ([lat], [lon]) with `timestamp >= sinceMs`, uncollapsed. */
        suspend fun near(sinceMs: Long, lat: Double, lon: Double): List<R>

        /** Every row with `timestamp >= sinceMs`, anywhere: only when there is no location. */
        suspend fun anywhere(sinceMs: Long): List<R>

        /** [source]'s own newest main (non-station) reading today, for a source with no stations. */
        suspend fun latestMainReading(source: WeatherSource): R?
    }

    suspend fun <R> load(host: Host<R>, source: WeatherSource, location: Pair<Double, Double>?, nowMs: Long): Result<R> {
        val diagnostic = StringBuilder()
        fun isForSource(row: R) = ObservationSourceMatcher.matchesStationsList(host.stationId(row), host.api(row), source)

        suspend fun stationsSince(sinceMs: Long): List<R> {
            val rows = if (location != null) {
                val (lat, lon) = location
                val box = host.near(sinceMs, lat, lon)
                val site = ObservationSiteMerge.merge(
                    box.filter(::isForSource), lat, lon,
                    host::latitude, host::longitude, host::stationId, host::timestamp, host::api, host::fetchedAt,
                )
                // Permanent, and load-bearing: `loc` alone would have identified the 2026-08-15
                // stale-location incident in a single grep.
                diagnostic
                    .append(" loc=$lat,$lon")
                    .append(" boxRows=${box.size}")
                    .append(" sites=${box.map { "${host.latitude(it)}/${host.longitude(it)}" }.distinct()}")
                    .append(" siteRows=${site.size}")
                site
            } else {
                host.anywhere(sinceMs).also { diagnostic.append(" loc=none boxRows=${it.size}") }
            }
            return select(rows, source, host::stationId, host::api, host::timestamp, host::distanceKm)
        }

        val recent = stationsSince(nowMs - StaleObservationFallback.RECENT_WINDOW_MS)
        val fallback = StaleObservationFallback.resolve(
            recent = recent,
            older = if (recent.isEmpty()) stationsSince(0L) else emptyList(),
            nowMs = nowMs,
            timestampOf = host::timestamp,
        )
        val rows = when {
            fallback.rows.isNotEmpty() || source == WeatherSource.NWS -> fallback.rows
            else -> listOfNotNull(host.latestMainReading(source))
        }
        val subtitle = when {
            rows.isEmpty() -> Subtitle.NONE_FOUND
            fallback.ageMs != null && fallback.rows.isNotEmpty() -> Subtitle.STALE
            source == WeatherSource.NWS -> Subtitle.NEARBY_STATIONS
            else -> Subtitle.LATEST_READING
        }
        return Result(rows, subtitle, fallback.ageMs.takeIf { subtitle == Subtitle.STALE }, diagnostic.toString())
    }

    /**
     * The stations of [rows] for [source]: synthetic rows (blend, backfill) out, the feed that supplies
     * [source]'s actuals in ([ObservationSourceMatcher.matchesStationsList]), the latest reading per
     * station, nearest first. Also desktop's now-dot overlay, over the rows it already holds.
     */
    fun <R> select(
        rows: List<R>,
        source: WeatherSource,
        stationIdOf: (R) -> String,
        apiOf: (R) -> String,
        timestampOf: (R) -> Long,
        distanceKmOf: (R) -> Float,
    ): List<R> =
        rows.filter { ObservationSourceMatcher.matchesStationsList(stationIdOf(it), apiOf(it), source) }
            .groupBy(stationIdOf)
            .map { (_, stationRows) -> stationRows.maxBy(timestampOf) }
            .sortedBy(distanceKmOf)

    /** The `loadObservations:` diagnostic line, both platforms. */
    fun <R> line(host: Host<R>, source: WeatherSource, result: Result<R>): String =
        "loadObservations: currentSource=${source.id}${result.diagnostic}" +
            " staleAgeMs=${result.staleAgeMs ?: "none"} items=${result.rows.map(host::stationId)}"

    /** The subtitle in English, equal to Android's default `obs_subtitle_*` strings (desktop shows it). */
    fun englishSubtitle(result: Result<*>, sourceDisplayName: String): String = when (result.subtitle) {
        Subtitle.NONE_FOUND -> "No recent observations found for $sourceDisplayName."
        Subtitle.STALE ->
            "No recent $sourceDisplayName observations — showing the latest, " +
                "${StaleObservationFallback.formatAge(result.staleAgeMs ?: 0L)} ago."
        Subtitle.NEARBY_STATIONS -> "Real-time data from nearby stations"
        Subtitle.LATEST_READING -> "Latest reading from $sourceDisplayName"
    }
}
