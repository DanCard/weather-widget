package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.shared.contract.ObservationWriteHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [ObservationWriteHostContract]; the Room half is `RoomObservationWriteHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopObservationWriteHostContractTest : ObservationWriteHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Db {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return object : Db {
            override fun host(lat: Double, lon: Double) = DesktopObservationWriteHost(dao, lat, lon)

            override suspend fun rows() = dao.getRecentObservations(0L).map { it.toReading() }
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
