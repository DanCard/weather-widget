package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.shared.contract.ElapsedForecastBackfillStoreContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [ElapsedForecastBackfillStoreContract]; the Room half is `RoomElapsedHistoryStoreContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopElapsedHistoryStoreContractTest : ElapsedForecastBackfillStoreContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<ElapsedForecastBackfill.Store, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val store = DesktopElapsedHistoryStore(DesktopWeatherDao(db), LAT, LON, SOURCE, BUCKET)
        // A plain INSERT: the DAO's writer quantizes and stamps fetchedAt with the clock.
        return store to Seed { row, bucket ->
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { stmt ->
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, bucket, row.fetchedAt)
                        .forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
