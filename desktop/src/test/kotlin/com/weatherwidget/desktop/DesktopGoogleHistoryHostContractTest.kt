package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.GoogleHistoryRun
import com.weatherwidget.shared.contract.GoogleHistoryHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [GoogleHistoryHostContract]; the Room half is `RoomGoogleHistoryHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopGoogleHistoryHostContractTest : GoogleHistoryHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<GoogleHistoryRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        // Plain INSERTs: the DAO's writers quantize and stamp fetchedAt with the clock.
        fun insert(sql: String, values: List<Any?>) = db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                values.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                stmt.executeUpdate()
            }
        }
        return DesktopGoogleHistoryHost(DesktopWeatherDao(db), LAT, LON) to object : Seed {
            override fun live(row: HourlyForecast) {
                insert(
                    "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.precipProbability, row.fetchedAt),
                )
            }

            override fun history(row: HourlyForecast, bucket: Long) {
                insert(
                    "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, precipProbability, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, bucket, row.precipProbability, row.fetchedAt),
                )
            }
        }
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
