package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.DaySourceComparisonHostContract
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop SQLite half of [DaySourceComparisonHostContract], through `DesktopDaySourceComparisonHost`
 * (`DesktopWeatherDao.getForecastRowsForDate`). The Room half is `RoomDaySourceComparisonHostContractTest`.
 */
@Category(ShortDuration::class)
class DesktopDaySourceComparisonHostContractTest : DaySourceComparisonHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(sources: List<WeatherSource>): Pair<DaySourceComparison.Host, Seed> {
        val path = Files.createTempFile("weather_day_source_compare", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        return DesktopDaySourceComparisonHost(DesktopWeatherDao(db), sources, emptyMap()) to DesktopSeed(db)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    /** Plain INSERTs: the DAO's writers quantize, dedupe and stamp times. */
    private class DesktopSeed(private val db: DesktopWeatherDatabase) : Seed {
        override fun forecast(row: ForecastSeed, targetDate: Long, dateOfPrediction: Long) = insert(
            "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                "isClimateNormal, source, precipProbability, precipAmountMm, lastConfirmedAt, fetchedAt, noonCloudPercent, " +
                "hourlyDayPrecipMax, hourlyNightPrecipMax, hourlyDayPrecipPeakAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            targetDate, dateOfPrediction, row.lat, row.lon, row.high, row.low, "Cloudy",
            if (row.isClimateNormal) 1 else 0, row.source, row.precipProbability, row.amountMm, row.lastConfirmedAt, row.fetchedAt,
            row.noonCloud, row.dayMax, row.nightMax, row.peakAt,
        )

        override fun hourly(row: HourlyForecast) = insert(
            "INSERT OR REPLACE INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, cloudCover, fetchedAt, timestampToGroupPredictions) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.precipProbability,
            row.cloudCover, row.fetchedAt, row.fetchedAt,
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
