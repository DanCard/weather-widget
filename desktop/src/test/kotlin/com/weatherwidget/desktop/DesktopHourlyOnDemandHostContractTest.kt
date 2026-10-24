package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.remote.HourlyOnDemandRun
import com.weatherwidget.shared.contract.HourlyOnDemandHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [HourlyOnDemandHostContract]; the Room half is `RoomHourlyOnDemandHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopHourlyOnDemandHostContractTest : HourlyOnDemandHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<HourlyOnDemandRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val host = DesktopHourlyOnDemandHost(DesktopWeatherDao(db), SOURCE, LAT, LON, NOW) { error("no network") }
        // A plain INSERT: the DAO's writer quantizes and stamps fetchedAt with the clock.
        return host to Seed { row ->
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { stmt ->
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.fetchedAt)
                        .forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
