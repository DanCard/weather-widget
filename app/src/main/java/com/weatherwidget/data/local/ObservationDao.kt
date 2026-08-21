package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.weatherwidget.shared.actuals.MetarCloudBlender
import kotlinx.coroutines.flow.Flow

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
     * Returns one physical site's observations from the coarse [LocationMatch.ROOM_WHERE] box.
     * Centralizing this boundary keeps direct DAO consumers from accidentally mixing two nearby
     * widget locations now that the site is part of observation identity.
     */
    suspend fun getObservationsInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
    ): List<ObservationEntity> =
        selectNearestObservationSite(
            getObservationCandidatesInRange(startTs, endTs, lat, lon),
            lat,
            lon,
        )

    /**
     * Cloud actuals for the window, as native observation epoch ms -> visible-layer percent
     * (`cloudCoverLow ?: cloudCover`, the same expression the forecast curve draws).
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
