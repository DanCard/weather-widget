package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.NowcastRefreshHostContract
import com.weatherwidget.shared.nowcast.NowcastRefreshRun
import com.weatherwidget.shared.nowcast.NowcastStore
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [NowcastRefreshHostContract]; the Room half is `RoomNowcastHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopNowcastHostContractTest : NowcastRefreshHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<NowcastRefreshRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dir = Files.createTempDirectory("nowcast_contract").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val host = DesktopNowcastHost(
            DesktopWeatherDao(db), LAT, LON, openMeteoDisplayed = true, usableSourceIds = emptySet(),
            store = NowcastStore(dir.toFile()), fetch = { error("no network") },
        )
        // A plain INSERT: the DAO's writer quantizes and stamps fetchedAt with the clock.
        return host to Seed { row ->
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { stmt ->
                    listOf(row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.precipProbability, row.fetchedAt)
                        .forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
