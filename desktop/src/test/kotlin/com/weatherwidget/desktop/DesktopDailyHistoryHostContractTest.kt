package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun
import com.weatherwidget.shared.contract.DailyHistoryHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [DailyHistoryHostContract]; the Room half is `RoomDailyHistoryHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopDailyHistoryHostContractTest : DailyHistoryHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<DailyHistoryRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        return DesktopDailyHistoryHost(DesktopWeatherDao(db)) to RawSeed(db)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    /** Plain INSERTs: the DAO's writers quantize, round and stamp `fetchedAt` with the clock. */
    private class RawSeed(private val db: DesktopWeatherDatabase) : Seed {
        override fun forecast(row: DailyHistoryMaintenance.ForecastHistoryRow, dateOfPrediction: Long) = insert(
            "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                "isClimateNormal, source, precipAmountMm, batchFetchedAt, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateMs, dateOfPrediction, row.locationLat, row.locationLon, row.highTemp, row.lowTemp, row.condition,
            if (row.isClimateNormal) 1 else 0, row.source, row.precipAmountMm, row.batchFetchedAt, row.fetchedAt,
        )

        override fun hourly(row: HourlyForecast) = insert(
            "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "precipProbability, cloudCover, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source,
            row.precipProbability, row.cloudCover, row.fetchedAt,
        )

        override fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long) = insert(
            "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "timestampToGroupPredictions, precipProbability, cloudCover, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source,
            snapshotBucketMs, row.precipProbability, row.cloudCover, row.fetchedAt,
        )

        private fun insert(sql: String, vararg args: Any?) {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    args.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }
}
