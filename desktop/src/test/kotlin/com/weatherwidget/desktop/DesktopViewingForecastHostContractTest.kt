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
        val host = DesktopViewingForecastHost(dao, "NWS", LAT, LON, PowerDetector.PowerState(false, 85), mutableMapOf()) {
            fixture.fetches += "NWS"
        }
        fixture = object : Fixture(host) {
            // As DesktopForecastHorizon's per-site marker, which freshness reads; the DAO stamps the clock.
            override fun recordFetch(sourceId: String, atMs: Long) = dao.log(
                DesktopWeatherDao.FORECAST_FETCH_MARKER_TAG,
                DesktopWeatherDao.forecastFetchMarkerPrefix(sourceId, LAT, LON),
                "INFO",
            )

            // Desktop keeps one record: every successful check writes the same row.
            override fun recordCheck(sourceId: String, atMs: Long) = recordFetch(sourceId, atMs)
        }
        return fixture
    }

    private companion object {
        const val LAT = 37.42
        const val LON = -122.08
    }

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
