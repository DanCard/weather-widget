package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.ViewingForecastHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [ViewingForecastHostContract]; the prefs half is `PrefsViewingForecastHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopViewingForecastHostContractTest : ViewingForecastHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Fixture {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        lateinit var fixture: Fixture
        val host = DesktopViewingForecastHost(dao, "NWS", PowerDetector.PowerState(false, 85), mutableMapOf()) {
            fixture.fetches += "NWS"
        }
        fixture = object : Fixture(host) {
            // As DesktopWeatherRepository's success row; the DAO stamps the clock.
            override fun recordFetch(sourceId: String, atMs: Long) = dao.log("REFRESH", "source=$sourceId hourly=1 daily=1", "INFO")

            // Desktop keeps one record: every successful check writes the same row.
            override fun recordCheck(sourceId: String, atMs: Long) = recordFetch(sourceId, atMs)
        }
        return fixture
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
