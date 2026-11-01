package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.contract.StitchedHourlyReadContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop SQLite half of [StitchedHourlyReadContract], through `DesktopWeatherDao.getHourlyWithHistory`
 * (the repository's and Observations window's read). The Room half is
 * `RoomStitchedHourlyReadContractTest` (`:app`).
 */
@Category(ShortDuration::class)
class DesktopStitchedHourlyReadContractTest : StitchedHourlyReadContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<Reader, Seed> {
        val path = Files.createTempFile("weather_stitched_read_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        val reader = object : Reader {
            override suspend fun read(startMs: Long, endMs: Long, nowMs: Long) =
                dao.getHourlyWithHistory(LAT, LON, SOURCE, startMs, endMs, nowMs = nowMs)

            override suspend fun readAllSources(startMs: Long, endMs: Long, nowMs: Long) =
                dao.getHourlySettledAllSources(LAT, LON, startMs, endMs, nowMs)
        }
        return reader to DesktopSeed(db)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    /** Plain INSERTs: the DAO's writers quantize and stamp `fetchedAt`. */
    private class DesktopSeed(private val db: DesktopWeatherDatabase) : Seed {
        override fun hourly(row: HourlyForecast) = insert(
            "INSERT OR REPLACE INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, cloudCover, fetchedAt, timestampToGroupPredictions) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.cloudCover, row.fetchedAt, row.fetchedAt,
        )

        override fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long) = insert(
            "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "cloudCover, timestampToGroupPredictions, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.cloudCover,
            snapshotBucketMs, row.fetchedAt,
        )

        private fun insert(sql: String, vararg args: Any?) {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
                    stmt.executeUpdate()
                }
            }
        }
    }
}
