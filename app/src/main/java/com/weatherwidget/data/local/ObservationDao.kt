package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.weatherwidget.shared.actuals.MetarCloudBlender
import kotlinx.coroutines.flow.Flow

/**
 * A [ObservationDao.readObservationsInRange] slower than this writes one OBS_RANGE_READ line. Logcat
 * only (Log.i) — this read happens many times per paint, so it must never touch `app_logs`, whose
 * write would land on the very path being measured.
 */
private const val OBS_RANGE_READ_SLOW_MS = 300L

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(observations: List<ObservationEntity>)

    @Query("SELECT * FROM observations WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity>

    // Location-scoped variant of getRecentObservations. Each row stores the device location it was
    // fetched under (locationLat/locationLon), so filtering by the current location keeps stale
    // observations from a previously-visited place (e.g. Austin rows lingering in the 24h window
    // after the device moved to the Bay Area) out of the current location's list.
    @Query(
        """
        SELECT * FROM observations
        WHERE timestamp >= :sinceMs
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY timestamp DESC, stationId ASC, locationLat ASC, locationLon ASC
    """,
    )
    suspend fun getRecentObservationsNear(
        sinceMs: Long,
        lat: Double,
        lon: Double,
    ): List<ObservationEntity>

    @Query("SELECT MAX(fetchedAt) FROM observations")
    fun observeLatestFetchedAt(): Flow<Long?>

    // ORDER BY must be TOTAL: all four primary-key fields are included so the raw candidate order is
    // fully determined. `ORDER BY timestamp` alone does NOT — several stations report on the same
    // timestamps (AW020/KSJC both cover 23:05-03:25), and the same station/timestamp can now coexist
    // at multiple fetch sites.
    //
    // That mattered because row order leaks into the blend: ActualTemperatureSeriesBuilder does
    // `filtered.groupBy { stationId }`, so byStation's ITERATION order follows row order, which then
    // decides dominantStationByDay's maxWith tie-break (gating the lone-station skip) and anchorStation
    // ("first station that resolves"). Same rows in a different order produced a different observed
    // curve: identical inputs (rows=1660, stations=6, blendedPoints=1041) rendered two different
    // series, alternating between two pixel-identical states and making the graph's high/low labels
    // blink on a window whose hours had no new data. See ActualsRowOrderDeterminismTest.
    @Query(
        """
        SELECT * FROM observations
        WHERE timestamp >= :startTs
          AND timestamp < :endTs
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY timestamp ASC, stationId ASC, locationLat ASC, locationLon ASC
    """,
    )
    suspend fun getObservationCandidatesInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double
    ): List<ObservationEntity>

    /**
     * [getObservationCandidatesInRange] restricted to [apis].
     *
     * A pure filter, never a reordering: the `ORDER BY` is byte-identical to the unscoped query's
     * for the reason its comment gives — row order leaks into the blend's `groupBy { stationId }`,
     * which decides `dominantStationByDay`'s tie-break and `anchorStation`, and the same rows in a
     * different order rendered two alternating series. See `ActualsRowOrderDeterminismTest`.
     *
     * Exists because the blend already discards most of what the unscoped query returns.
     * `ObservationSourceMatcher.matchesActualSource` rejects every row whose `api` is not the
     * display source's resolved provider, but the DAO took no source, so that filter could only run
     * after the rows had crossed the CursorWindow. Measured 2026-09-06 on the Samsung, one 132h
     * window held 34,726 SYNOPTIC rows (70%, and 1.35 MB of text) against 7,862 NWS rows — all of
     * the former read off a cold disk and dropped by the first filter in the blend.
     */
    @Query(
        """
        SELECT * FROM observations
        WHERE timestamp >= :startTs
          AND timestamp < :endTs
          AND ${LocationMatch.ROOM_WHERE}
          AND api IN (:apis)
        ORDER BY timestamp ASC, stationId ASC, locationLat ASC, locationLon ASC
    """,
    )
    suspend fun getObservationCandidatesInRangeForApis(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
        apis: Collection<String>,
    ): List<ObservationEntity>

    /**
     * Returns the observations from the coarse [LocationMatch.ROOM_WHERE] box that describe the
     * user's current sky, merged across nearby device-site fragments and deduplicated.
     *
     * Was a single-site collapse ([selectNearestObservationSite]). That deleted the rows of any
     * fragment more than [LocationMatch.SAME_SITE_TOLERANCE_DEG] away, which on 2026-08-27 meant an
     * ~800 m walk cost both the cloud and temperature actual lines a 75-minute hole over data that
     * was in the database. A device site records where the phone was standing, not where the
     * weather is. See [ObservationSiteMerge].
     */
    suspend fun getObservationsInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
        apis: Collection<String>? = null,
    ): List<ObservationEntity> = readObservationsInRange(startTs, endTs, lat, lon, apis).rows

    /**
     * [getObservationsInRange] plus the [ObservationPoolDiagnostics] that say why the returned pool
     * is as old as it is.
     *
     * Derived from the SAME single query — the candidate list is already in hand here, and it is the
     * only place it ever is. A caller wanting these numbers after the fact would have to re-run the
     * box query, which on the reporting device took 1.4 s; that cost is why the 2026-09-03 stale
     * dominant-station report had to be reconstructed from `fetchedAt` archaeology on a pulled
     * database instead of being read off a log line.
     */
    suspend fun readObservationsInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
        /**
         * `observations.api` values worth reading, or null for every api.
         *
         * Pass the caller's resolved provider set — `ActualsProviderResolver.providerIdFor(source)`,
         * never a literal, because a source can be configured to take another feed's actuals (this
         * device has `actuals_provider_SILURIAN = SYNOPTIC`, so Silurian's curve is built entirely
         * from Synoptic rows). Only scope a read whose every consumer filters by that same rule; the
         * daily recompute, which computes history for all sources at once, must stay unscoped.
         */
        apis: Collection<String>? = null,
    ): ObservationRangeRead {
        val sqlStartMs = android.os.SystemClock.elapsedRealtime()
        val candidates =
            if (apis == null) {
                getObservationCandidatesInRange(startTs, endTs, lat, lon)
            } else {
                getObservationCandidatesInRangeForApis(startTs, endTs, lat, lon, apis)
            }
        val afterSqlMs = android.os.SystemClock.elapsedRealtime()
        val merged =
            ObservationSiteMerge.merge(
                rows = candidates,
                lat = lat,
                lon = lon,
                latOf = ObservationEntity::locationLat,
                lonOf = ObservationEntity::locationLon,
                stationOf = ObservationEntity::stationId,
                timestampOf = ObservationEntity::timestamp,
                apiOf = ObservationEntity::api,
                fetchedAtOf = ObservationEntity::fetchedAt,
            )
        // This read is the single largest cost on both the sync and the tap-facing paint path
        // (measured 2026-09-06 on the Samsung: obsQueryMs=5462 of a 7975ms paint). Split it so a
        // slow read says whether the time is SQLite or the site merge. The pool summary is NOT
        // counted here any more — it is deferred to first access; see [ObservationRangeRead].
        val endMs = android.os.SystemClock.elapsedRealtime()
        if (endMs - sqlStartMs >= OBS_RANGE_READ_SLOW_MS) {
            android.util.Log.i(
                "OBS_RANGE_READ",
                "spanH=${(endTs - startTs) / 3_600_000} total=${endMs - sqlStartMs}ms " +
                    "sql=${afterSqlMs - sqlStartMs}ms merge=${endMs - afterSqlMs}ms " +
                    "candidates=${candidates.size} merged=${merged.size} " +
                    "apis=${apis?.joinToString("|") ?: "ALL"}",
            )
        }
        return ObservationRangeRead(rows = merged) {
            ObservationPoolDiagnostics.summarize(
                candidates = candidates,
                merged = merged,
                latOf = ObservationEntity::locationLat,
                lonOf = ObservationEntity::locationLon,
                timestampOf = ObservationEntity::timestamp,
            )
        }
    }

    /**
     * A cheap content signature for the observations covering [startTs, endTs) at this location:
     * row count, newest observation time, and the summed temperature in centidegrees.
     *
     * Used to decide whether a past day's extremes can possibly have moved since they were last
     * computed. One indexed aggregate; no rows are materialized.
     *
     * **Deliberately NOT `MAX(fetchedAt)`.** That was the first attempt and it never fired once:
     * observations are written `INSERT OR REPLACE`, so every deep fetch re-stamps rows it already
     * has, and `touchLatestFetchedAt` bumps the stamp even for a fetch that stored nothing. Measured
     * 2026-09-06 on a *fully settled* day, 7,315 of 7,633 rows carried a `fetchedAt` more than an
     * hour after their own `timestamp`, and the day's newest stamp was the current minute. The
     * signal says "we looked", not "something changed".
     *
     * What is summed here is exactly what the reduction reads: `COUNT` catches an inserted row
     * wherever it lands, `MAX(timestamp)` catches a new latest reading, and the value sums catch a
     * station revising a row in place — the case the first two miss, because the primary key is
     * unchanged. Sums are rounded to integers so float noise cannot manufacture a difference.
     *
     * **Adding a column the daily reduction consumes means adding it here.** Precip proved that the
     * hard way: measured precip arrives by REPLACE on an existing key with temperature, count and
     * newest timestamp all unchanged, so a temperature-only signature declared the day settled and
     * dropped the rainfall (`ObservationRepositoryDailyMergeTest` caught it immediately). The columns
     * below are the ones `persistExtremes` can write from: temps, precip, cloud/condition, and the
     * QC flag that decides whether a row counts at all.
     */
    @Query(
        """
        SELECT COUNT(*) || '|' || COALESCE(MAX(timestamp), 0) || '|' ||
               COALESCE(SUM(CAST(ROUND(temperature * 100) AS INTEGER)), 0) || '|' ||
               COALESCE(SUM(CAST(ROUND(precipAmountMm * 100) AS INTEGER)), 0) || '|' ||
               COALESCE(SUM(cloudCover), 0) || '|' ||
               COALESCE(SUM(cloudCoverLow), 0) || '|' ||
               COALESCE(SUM(qcFailed), 0)
        FROM observations
        WHERE timestamp >= :startTs
          AND timestamp < :endTs
          AND ${LocationMatch.ROOM_WHERE}
        """,
    )
    suspend fun observationSignatureInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
    ): String?

    /**
     * Cloud actuals for the window, as native observation epoch ms -> visible-layer percent
     * ([com.weatherwidget.shared.util.VisibleCloudCover], the same resolver the forecast curve uses).
     *
     * Delegates the source-aware branch selection to the shared
     * [MetarCloudBlender.fromSiteRows]; this DAO contributes only the site-collapsed read (the
     * coarse box can gather a jitter fragment or a neighbouring town).
     */
    suspend fun getCloudActuals(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
        sourceId: String,
    ): MetarCloudBlender.Result =
        MetarCloudBlender.fromSiteRows(
            startMs = startTs,
            endMs = endTs,
            sourceId = sourceId,
        ) { readStart, readEnd ->
            getObservationsInRange(readStart, readEnd, lat, lon).map { it.toReading() }
        }

    @Query("DELETE FROM observations WHERE timestamp < :cutoffMs")
    suspend fun deleteOldObservations(cutoffMs: Long)

    @Query(
        "DELETE FROM observations " +
            "WHERE api = 'TOMORROW_IO' " +
            "AND stationId NOT IN ('TOMORROW_IO_RECENT_HISTORY', 'TOMORROW_IO_REALTIME')",
    )
    suspend fun deleteLegacyTomorrowIoObservations(): Int

    @Query("DELETE FROM observations WHERE api = 'OPEN_METEO'")
    suspend fun deleteOpenMeteoModelObservations(): Int

    @Query(
        """
        SELECT * FROM observations
        WHERE stationId = :stationId
          AND locationLat = :lat
          AND locationLon = :lon
        ORDER BY timestamp DESC
        LIMIT 1
    """,
    )
    suspend fun getLatestForStation(
        stationId: String,
        lat: Double,
        lon: Double,
    ): ObservationEntity?

    // fetchedAt records the last completed fetch *attempt* for the station, not just the last
    // stored data: a fetch that completes but yields nothing storable (station publishing
    // null-temperature reports) touches the newest row so the observations UI can distinguish a
    // silent station (Reported old, Fetched fresh) from a stalled pipeline (both old).
    @Query(
        """
        UPDATE observations SET fetchedAt = :nowMs
        WHERE stationId = :stationId
          AND locationLat = :lat
          AND locationLon = :lon
          AND timestamp = (
              SELECT MAX(timestamp) FROM observations
              WHERE stationId = :stationId
                AND locationLat = :lat
                AND locationLon = :lon
          )
    """,
    )
    suspend fun touchLatestFetchedAt(
        stationId: String,
        lat: Double,
        lon: Double,
        nowMs: Long,
    )

    @Query("""
        SELECT * FROM observations
        WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
          AND ${LocationMatch.ROOM_WHERE}
          AND timestamp > :sinceMs
        ORDER BY timestamp DESC, stationId ASC, locationLat ASC, locationLon ASC
    """)
    suspend fun getLatestMainObservationCandidatesExcludingNws(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity>

    suspend fun getLatestMainObservationsExcludingNws(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity> =
        selectNearestObservationSite(
            getLatestMainObservationCandidatesExcludingNws(lat, lon, sinceMs),
            lat,
            lon,
        )

    @Query("""
        SELECT * FROM observations
        WHERE api = 'NWS'
          AND ${LocationMatch.ROOM_WHERE}
          AND fetchedAt > :sinceMs
        ORDER BY stationId ASC, timestamp DESC, locationLat ASC, locationLon ASC
    """)
    suspend fun getLatestNwsObservationCandidatesByStationAllTime(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity>

    suspend fun getLatestNwsObservationsByStationAllTime(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity> =
        selectNearestObservationSite(
            getLatestNwsObservationCandidatesByStationAllTime(lat, lon, sinceMs),
            lat,
            lon,
        )

    @Query("""
        SELECT * FROM observations
        WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
          AND ${LocationMatch.ROOM_WHERE}
          AND timestamp > :sinceMs
        ORDER BY timestamp DESC, stationId ASC, locationLat ASC, locationLon ASC
    """)
    suspend fun getLatestMainObservationCandidates(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity>

    suspend fun getLatestMainObservations(
        lat: Double,
        lon: Double,
        sinceMs: Long,
    ): List<ObservationEntity> =
        selectNearestObservationSite(
            getLatestMainObservationCandidates(lat, lon, sinceMs),
            lat,
            lon,
        )
}

/**
 * An observation range read together with the numbers explaining its freshness.
 * See [ObservationDao.readObservationsInRange].
 *
 * [diagnostics] is computed ON FIRST ACCESS, not on read. Summarizing the pool costs real work
 * proportional to the candidate list, and measured 2026-09-06 on the Samsung it was ~1.6s of every
 * ~4.4s read — 40% of the single most expensive operation in the app. Exactly ONE caller reads it
 * (`TemperatureStateResolver`); the other 23 go through [ObservationDao.getObservationsInRange],
 * which takes `.rows` and drops the summary on the floor. Deferring it is observationally inert:
 * the one consumer still gets the same numbers, everyone else stops paying for them.
 */
class ObservationRangeRead(
    val rows: List<ObservationEntity>,
    summarize: () -> ObservationPoolDiagnostics.Summary,
) {
    /** Eager overload — for tests and any caller that already holds a computed summary. */
    constructor(
        rows: List<ObservationEntity>,
        diagnostics: ObservationPoolDiagnostics.Summary,
    ) : this(rows, { diagnostics })

    val diagnostics: ObservationPoolDiagnostics.Summary by lazy(summarize)
}
