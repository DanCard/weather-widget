package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.StationsListHostContract
import com.weatherwidget.shared.observations.StationsList
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [StationsListHostContract]; the Room half is `RoomStationsListHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopStationsListHostContractTest : StationsListHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<StationsList.Host<*>, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val host = DesktopStationsListHost(dao, LAT, LON)
        return host to Seed { stationId, api, timestamp, lat ->
            dao.upsertObservations(
                listOf(
                    DesktopObservationEntity(
                        stationId = stationId, stationName = stationId, timestamp = timestamp, temperature = 60f,
                        condition = "Clear", locationLat = lat, locationLon = -122.089, api = api, fetchedAt = timestamp,
                    ),
                ),
            )
        }
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
