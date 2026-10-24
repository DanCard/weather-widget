package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.PriorDayCloudHostContract
import com.weatherwidget.shared.graph.PriorDayCloudRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [PriorDayCloudHostContract]; the Room half is `RoomPriorDayCloudHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopPriorDayCloudHostContractTest : PriorDayCloudHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Db {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return object : Db {
            override fun host(latitude: Double, longitude: Double, lastAttempt: PriorDayCloudRun.LastAttempt) =
                DesktopPriorDayCloudHost(dao, latitude, longitude, lastAttempt) { _, _ -> error("no fetch") }

            override suspend fun read(latitude: Double, longitude: Double, startMs: Long, endMs: Long) =
                dao.getPriorDayCloudForecast(latitude, longitude, startMs, endMs)
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
