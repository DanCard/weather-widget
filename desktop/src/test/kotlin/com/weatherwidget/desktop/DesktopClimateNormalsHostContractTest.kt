package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.ClimateNormalsHostContract
import com.weatherwidget.shared.util.ClimateNormalsRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [ClimateNormalsHostContract]; the Room half is `RoomClimateNormalsHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopClimateNormalsHostContractTest : ClimateNormalsHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): ClimateNormalsRun.Host {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        return DesktopClimateNormalsHost(DesktopWeatherDao(db), NO_FETCH)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
