package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.WeatherApiHistoryHostContract
import com.weatherwidget.shared.history.WeatherApiHistoryRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [WeatherApiHistoryHostContract]; the Room half is `RoomWeatherApiHistoryHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopWeatherApiHistoryHostContractTest : WeatherApiHistoryHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<WeatherApiHistoryRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val host = DesktopWeatherApiHistoryHost(dao, LAT, LON, nowMs = DAY_START + DAY) { error("no network") }
        return host to Seed { row ->
            dao.upsertObservations(
                listOf(
                    DesktopObservationEntity(
                        stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                        temperature = row.temperature, condition = row.condition,
                        locationLat = row.locationLat, locationLon = row.locationLon, fetchedAt = row.fetchedAt, api = row.api,
                    ),
                ),
            )
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
