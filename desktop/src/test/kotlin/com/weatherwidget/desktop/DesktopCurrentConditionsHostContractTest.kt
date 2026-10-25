package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.CurrentConditionsHostContract
import com.weatherwidget.shared.observations.CurrentConditionsRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop half of [CurrentConditionsHostContract]; the Android half is `AndroidCurrentConditionsHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopCurrentConditionsHostContractTest : CurrentConditionsHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): CurrentConditionsRun.Host {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        return DesktopCurrentConditionsHost(
            visibleSources = emptyList(), displayedSourceIds = emptySet(), memory = DesktopCurrentConditionsMemory(),
            weatherDao = DesktopWeatherDao(db), fetch = { error("no network") }, recordFailure = { _, _ -> },
        )
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
