package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.HourlySnapshotHostContract
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [HourlySnapshotHostContract]; the Room half is `RoomHourlySnapshotHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopHourlySnapshotHostContractTest : HourlySnapshotHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<HourlySnapshotRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val host = DesktopHourlySnapshotHost(DesktopWeatherDao(db), SOURCE, LAT, LON, config = null, nowMs = T0)
        return host to Seed { row, label ->
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { stmt ->
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, label, row.fetchedAt)
                        .forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
