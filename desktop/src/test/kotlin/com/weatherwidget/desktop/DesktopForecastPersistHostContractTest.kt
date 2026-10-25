package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.ForecastPersistHostContract
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [ForecastPersistHostContract]; the Room half is `RoomForecastPersistHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopForecastPersistHostContractTest : ForecastPersistHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): (String, Double, Double) -> ForecastPersistRun.Host<*> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return { source, lat, lon -> DesktopForecastPersistHost(dao, source, lat, lon) { LABEL } }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
