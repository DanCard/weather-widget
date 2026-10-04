package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface DailyHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extremes: List<DailyHistoryEntity>)

    /**
     * Optimistic, field-limited write for the blend recompute. Sets ONLY the columns the blend
     * recompute owns and is conditional on the row's `updatedAt` still matching the value the
     * caller read ([expectedUpdatedAt]) — so a concurrent writer (e.g. the NWS station pull
     * writing `actualsSource`/`apiHighTemp`) can never have its provenance clobbered by a stale
     * recompute snapshot, and a conflicting write returns 0 so the caller can skip the row.
     */
    /**
     * Writes ONLY the settled forecast overlay (see ForecastOverlaySettle). Leaves `updatedAt`
     * alone, so it never trips [updateBlendIfUnchanged]'s optimistic check and never clobbers a
     * blend written concurrently.
     */
    @Query(
        """
        UPDATE daily_history SET
            forecastHighTemp = :forecastHighTemp,
            forecastLowTemp = :forecastLowTemp,
            lastWriter = :lastWriter
        WHERE date = :date
          AND source = :source
          AND locationLat = :locationLat
          AND locationLon = :locationLon
        """,
    )
    suspend fun updateForecastOverlay(
        date: Long,
        source: String,
        locationLat: Double,
        locationLon: Double,
        forecastHighTemp: Float?,
        forecastLowTemp: Float?,
        lastWriter: String?,
    ): Int

    @Query(
        """
        UPDATE daily_history SET
            computedHighTemp = :computedHighTemp,
            computedLowTemp = :computedLowTemp,
            computedHighAt = :computedHighAt,
            computedLowAt = :computedLowAt,
            condition = :condition,
            precipAmountMm = :precipAmountMm,
            precipDayMm = :precipDayMm,
            precipNightMm = :precipNightMm,
            lastWriter = :lastWriter,
            updatedAt = :updatedAt
        WHERE date = :date
          AND source = :source
          AND locationLat = :locationLat
          AND locationLon = :locationLon
          AND updatedAt = :expectedUpdatedAt
        """,
    )
    suspend fun updateBlendIfUnchanged(
        date: Long,
        source: String,
        locationLat: Double,
        locationLon: Double,
        computedHighTemp: Float?,
        computedLowTemp: Float?,
        computedHighAt: Long?,
        computedLowAt: Long?,
        condition: String,
        precipAmountMm: Float?,
        precipDayMm: Float?,
        precipNightMm: Float?,
        lastWriter: String?,
        updatedAt: Long,
        expectedUpdatedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM daily_history
        WHERE date >= :startDate
          AND date <= :endDate
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY date ASC
        """,
    )
    suspend fun getExtremesInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<DailyHistoryEntity>

    /**
     * Measured rows for one day at ANY site. Deliberately not location-scoped: the donor pool for
     * `PreviousSiteHistory`, which shows yesterday's history from where the user was before a move.
     */
    @Query(
        """
        SELECT * FROM daily_history
        WHERE date = :date
          AND computedHighTemp IS NOT NULL
          AND computedLowTemp IS NOT NULL
        """,
    )
    suspend fun getMeasuredExtremesForDateAnySite(date: Long): List<DailyHistoryEntity>

    @Query("DELETE FROM daily_history WHERE updatedAt < :cutoffMs")
    suspend fun deleteOldExtremes(cutoffMs: Long)

    // Generic retired-product cleanup (RetiredProductCleanup); see ObservationDao.rowIdsRaw.
    @RawQuery
    suspend fun rowIdsRaw(query: SupportSQLiteQuery): List<Long>

    @Query("DELETE FROM daily_history WHERE rowid IN (:rowIds)")
    suspend fun deleteByRowIds(rowIds: List<Long>): Int
}
