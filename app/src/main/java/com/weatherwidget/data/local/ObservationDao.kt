package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        ORDER BY timestamp DESC
    """,
    )
    suspend fun getRecentObservationsNear(
        sinceMs: Long,
        lat: Double,
        lon: Double,
    ): List<ObservationEntity>

    @Query("SELECT MAX(fetchedAt) FROM observations")
    fun observeLatestFetchedAt(): Flow<Long?>

    @Query(
        """
        SELECT * FROM observations
        WHERE timestamp >= :startTs
          AND timestamp < :endTs
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY timestamp ASC
    """,
    )
    suspend fun getObservationsInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double
    ): List<ObservationEntity>

    @Query("DELETE FROM observations WHERE timestamp < :cutoffMs")
    suspend fun deleteOldObservations(cutoffMs: Long)

    @Query("SELECT * FROM observations WHERE stationId = :stationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForStation(stationId: String): ObservationEntity?

    // fetchedAt records the last completed fetch *attempt* for the station, not just the last
    // stored data: a fetch that completes but yields nothing storable (station publishing
    // null-temperature reports) touches the newest row so the observations UI can distinguish a
    // silent station (Reported old, Fetched fresh) from a stalled pipeline (both old).
    @Query(
        """
        UPDATE observations SET fetchedAt = :nowMs
        WHERE stationId = :stationId
          AND timestamp = (SELECT MAX(timestamp) FROM observations WHERE stationId = :stationId)
    """,
    )
    suspend fun touchLatestFetchedAt(stationId: String, nowMs: Long)

    @Query("""
        SELECT * FROM observations
        WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
          AND ${LocationMatch.ROOM_WHERE}
          AND timestamp > :sinceMs
        ORDER BY timestamp DESC
    """)
    suspend fun getLatestMainObservationsExcludingNws(lat: Double, lon: Double, sinceMs: Long): List<ObservationEntity>

    @Query("""
        SELECT * FROM observations
        WHERE api = 'NWS'
          AND ${LocationMatch.ROOM_WHERE}
          AND fetchedAt > :sinceMs
        ORDER BY stationId, timestamp DESC
    """)
    suspend fun getLatestNwsObservationsByStationAllTime(lat: Double, lon: Double, sinceMs: Long): List<ObservationEntity>

    @Query("""
        SELECT * FROM observations
        WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
          AND ${LocationMatch.ROOM_WHERE}
          AND timestamp > :sinceMs
        ORDER BY timestamp DESC
    """)
    suspend fun getLatestMainObservations(lat: Double, lon: Double, sinceMs: Long): List<ObservationEntity>
}
