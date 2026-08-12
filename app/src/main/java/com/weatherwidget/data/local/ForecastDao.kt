package com.weatherwidget.data.local

import android.util.Log
import androidx.room.*
import com.weatherwidget.data.remote.orNullIfImplausibleTempF
import com.weatherwidget.shared.actuals.DailyForecastSelector

/**
 * Every read that returns [ForecastEntity] passes through [withPlausibleTemps]. The `...Raw` methods
 * are the Room-generated queries; the same-named wrappers below them are what callers use.
 *
 * Why the whole surface and not just the snapshot query: the 2026-07-27 sentinel incident was
 * diagnosed as desktop-only because Android's DAO "always takes the newest batch". That was true of
 * the latest-forecast reads but not of the today-column snapshot, which deliberately reaches ~24h
 * backwards (`DailySnapshotSelector.selectPriorDaySnapshot`), so a poisoned row written before the
 * ingest gate existed kept rendering for another day. Guarding one query would rebuild that trap for
 * the next backward-reaching reader. See plans/260728c-sentinel-android-read-guard-and-axis-hardening.md.
 *
 * Note that `highTemp IS NOT NULL` in some queries is NOT a substitute: a `-100` sentinel is
 * perfectly non-null and passes those filters untouched.
 */
@Dao
interface ForecastDao {
    @Query("SELECT * FROM forecasts ORDER BY batchFetchedAt DESC, fetchedAt DESC LIMIT 1")
    suspend fun getLatestWeatherRaw(): ForecastEntity?

    suspend fun getLatestWeather(): ForecastEntity? = getLatestWeatherRaw()?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts
        WHERE source = :source
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getLatestForecastBySourceRaw(
        source: String,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    suspend fun getLatestForecastBySource(
        source: String,
        lat: Double,
        lon: Double,
    ): ForecastEntity? = getLatestForecastBySourceRaw(source, lat, lon)?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts
        WHERE source = :source
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getLatestWeatherBySourceRaw(source: String): ForecastEntity?

    suspend fun getLatestWeatherBySource(source: String): ForecastEntity? =
        getLatestWeatherBySourceRaw(source)?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY dateOfPrediction DESC, batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getForecastForDateRaw(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    suspend fun getForecastForDate(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity? = getForecastForDateRaw(targetDate, lat, lon)?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND dateOfPrediction = :dateOfPrediction
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getSpecificForecastRaw(
        targetDate: Long,
        dateOfPrediction: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    suspend fun getSpecificForecast(
        targetDate: Long,
        dateOfPrediction: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity? =
        getSpecificForecastRaw(targetDate, dateOfPrediction, lat, lon)?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND dateOfPrediction = :dateOfPrediction
        AND ${LocationMatch.ROOM_WHERE}
        AND source = :source
        ORDER BY fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getForecastForDateBySourceRaw(
        targetDate: Long,
        dateOfPrediction: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): ForecastEntity?

    suspend fun getForecastForDateBySource(
        targetDate: Long,
        dateOfPrediction: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): ForecastEntity? =
        getForecastForDateBySourceRaw(targetDate, dateOfPrediction, lat, lon, source)?.withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getForecastsInRangeAllSitesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    suspend fun getForecastsInRangeAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity> =
        getForecastsInRangeAllSitesRaw(startDate, endDate, lat, lon).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND source IN (:sources)
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getForecastsInRangeForSourcesAllSitesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity>

    suspend fun getForecastsInRangeForSourcesAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity> =
        getForecastsInRangeForSourcesAllSitesRaw(startDate, endDate, lat, lon, sources).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE source = :source
        AND ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeBySourceAllSitesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity>

    suspend fun getLatestForecastsInRangeBySourceAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity> =
        getLatestForecastsInRangeBySourceAllSitesRaw(startDate, endDate, lat, lon, source).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE source IN (:sources)
        AND ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND highTemp IS NOT NULL
        AND lowTemp IS NOT NULL
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
            AND f2.highTemp IS NOT NULL
            AND f2.lowTemp IS NOT NULL
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeForSourcesAllSitesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<ForecastEntity>

    suspend fun getLatestForecastsInRangeForSourcesAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<ForecastEntity> =
        getLatestForecastsInRangeForSourcesAllSitesRaw(startDate, endDate, lat, lon, sources).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND highTemp IS NOT NULL
        AND lowTemp IS NOT NULL
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
            AND f2.highTemp IS NOT NULL
            AND f2.lowTemp IS NOT NULL
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeAllSitesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    suspend fun getLatestForecastsInRangeAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity> =
        getLatestForecastsInRangeAllSitesRaw(startDate, endDate, lat, lon).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        ORDER BY targetDate ASC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getAllForecastsInRangeRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    suspend fun getAllForecastsInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity> =
        getAllForecastsInRangeRaw(startDate, endDate, lat, lon).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND source IN (:sources)
        ORDER BY targetDate ASC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getAllForecastsInRangeForSourcesRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity>

    suspend fun getAllForecastsInRangeForSources(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity> =
        getAllForecastsInRangeForSourcesRaw(startDate, endDate, lat, lon, sources).withPlausibleTemps()

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source = :source
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        ORDER BY targetDate ASC, dateOfPrediction DESC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getForecastsInRangeBySourceRaw(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity>

    suspend fun getForecastsInRangeBySource(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity> =
        getForecastsInRangeBySourceRaw(startDate, endDate, lat, lon, source).withPlausibleTemps()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(forecast: ForecastEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forecasts: List<ForecastEntity>)

    @Query("SELECT COUNT(*) FROM forecasts")
    suspend fun getCount(): Int

    @Query("DELETE FROM forecasts WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldForecasts(cutoffTime: Long)

    @Query("DELETE FROM forecasts WHERE fetchedAt < :cutoffTime AND source = :source")
    suspend fun deleteOldForecastsBySource(cutoffTime: Long, source: String)

    /**
     * Permanent, idempotent purge of climate-normal gap rows — they're synthesized at read time now
     * (see ClimateGapFiller) and never (re)persisted, but leftover rows from before that change need
     * a one-time cleanup during the upgrade window.
     */
    @Query("DELETE FROM forecasts WHERE source = :source OR isClimateNormal = 1")
    suspend fun deleteClimateNormalRows(source: String)

    /**
     * Deletes every forecast row filed at one physical site. Written for
     * [LegacyDefaultLocationMigration][com.weatherwidget.widget.LegacyDefaultLocationMigration]: prefs
     * are not the only place the retired Google-HQ sentinel lives — a month of forecast rows carries
     * those coordinates too, and `ActiveLocationResolver.resolve()` reads them back through the
     * location-blind [getLatestWeather] and re-persists what it finds. Clearing the prefs alone left
     * the sentinel to resurrect itself on the very next worker run.
     *
     * Scoped by [LocationMatch.ROOM_SAME_SITE_WHERE] (±0.002°), never the ±0.1° read box.
     *
     * @return rows deleted, for the `LOCATION_MIGRATION` breadcrumb.
     */
    @Query("DELETE FROM forecasts WHERE ${LocationMatch.ROOM_SAME_SITE_WHERE}")
    suspend fun deleteForecastsAtSite(lat: Double, lon: Double): Int

    /**
     * Removes existing rows for the given targets whose fetchedAt falls in a snapshot bucket window
     * [bucketStart, bucketEnd). Used to cap daily forecast-history cadence (4h primary / 8h other):
     * delete the earlier in-bucket snapshot before inserting the latest, so at most one row per
     * bucket survives while the newest row keeps its real fetchedAt.
     */
    @Query(
        """
        DELETE FROM forecasts
        WHERE source = :source
        AND locationLat = :lat AND locationLon = :lon
        AND targetDate IN (:targetDates)
        AND fetchedAt >= :bucketStart AND fetchedAt < :bucketEnd
    """,
    )
    suspend fun deleteForecastsInBucket(
        source: String,
        lat: Double,
        lon: Double,
        targetDates: List<Long>,
        bucketStart: Long,
        bucketEnd: Long,
    )

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY dateOfPrediction ASC, batchFetchedAt ASC, fetchedAt ASC
    """,
    )
    suspend fun getForecastEvolutionRaw(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    suspend fun getForecastEvolution(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity> = getForecastEvolutionRaw(targetDate, lat, lon).withPlausibleTemps()
}

private const val SANITIZE_TAG = "ForecastDao"

/**
 * Read-side plausibility guard: an implausible stored temperature is reported as missing rather than
 * as weather. Sibling of the ingest gate in `NwsTemperaturePlausibility` — the ingest filter can only
 * protect rows written after it landed, so rows already in the 1-month retention window need this.
 *
 * A sentinel reaching a renderer is worse than a gap: it drags bar geometry and axis scaling
 * off-screen while the label beside it shows a perfectly healthy number.
 */
internal fun ForecastEntity.withPlausibleTemps(): ForecastEntity {
    val high = highTemp.orNullIfImplausibleTempF()
    val low = lowTemp.orNullIfImplausibleTempF()
    if (high == highTemp && low == lowTemp) return this
    // Rare by construction (only genuinely poisoned rows), so this stays a permanent breadcrumb
    // rather than log spam — the next occurrence should be one logcat query away.
    Log.w(
        SANITIZE_TAG,
        "withPlausibleTemps: rejected stored temp targetDate=$targetDate source=$source" +
            " high=$highTemp->$high low=$lowTemp->$low fetchedAt=$fetchedAt",
    )
    return copy(highTemp = high, lowTemp = low)
}

internal fun List<ForecastEntity>.withPlausibleTemps(): List<ForecastEntity> =
    map { it.withPlausibleTemps() }

/**
 * Site-collapsing wrappers over the `...AllSites` range queries. Those queries keep the freshest
 * batch per EXACT (locationLat, locationLon) key inside the [LocationMatch.ROOM_WHERE] box, so a
 * coordinate-jitter hop leaves the abandoned key's last batch alive alongside the live site — and
 * whichever row happens to sort first gets displayed (seen on-device as a days-stale "tomorrow"
 * high). The wrappers collapse to one row per (targetDate, source): same-site preferred, freshest
 * batch wins. History-preserving queries (`getAllForecastsInRange*`, `getForecastsInRangeBySource`,
 * `getForecastEvolution`) intentionally stay uncollapsed — they feed snapshot/evolution views.
 */
private fun collapseSites(rows: List<ForecastEntity>, lat: Double, lon: Double): List<ForecastEntity> =
    DailyForecastSelector.selectFreshestPerDaySource(
        rows,
        centerLat = lat,
        centerLon = lon,
        targetDate = { it.targetDate },
        source = { it.source },
        locationLat = { it.locationLat },
        locationLon = { it.locationLon },
        batchFetchedAt = { it.batchFetchedAt },
        fetchedAt = { it.fetchedAt },
    )

suspend fun ForecastDao.getForecastsInRange(startDate: Long, endDate: Long, lat: Double, lon: Double): List<ForecastEntity> =
    collapseSites(getForecastsInRangeAllSites(startDate, endDate, lat, lon), lat, lon)

suspend fun ForecastDao.getForecastsInRangeForSources(startDate: Long, endDate: Long, lat: Double, lon: Double, sources: List<String>): List<ForecastEntity> =
    collapseSites(getForecastsInRangeForSourcesAllSites(startDate, endDate, lat, lon, sources), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRangeBySource(startDate: Long, endDate: Long, lat: Double, lon: Double, source: String): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeBySourceAllSites(startDate, endDate, lat, lon, source), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRangeForSources(startDate: Long, endDate: Long, lat: Double, lon: Double, sources: List<String>): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeForSourcesAllSites(startDate, endDate, lat, lon, sources), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRange(startDate: Long, endDate: Long, lat: Double, lon: Double): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeAllSites(startDate, endDate, lat, lon), lat, lon)
