package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.StationNetworkHostContract
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** SQLite half of [StationNetworkHostContract]; the Room half is `AndroidStationNetworkHostContractTest`. */
@Category(ShortDuration::class)
class DesktopStationNetworkHostContractTest : StationNetworkHostContract() {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase

    override fun open(lat: Double, lon: Double): StationNetworkRefreshRun.Host {
        path = Files.createTempFile("weather_station_network_contract", ".db")
        db = DesktopWeatherDatabase(path).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        return DesktopStationNetworkHost(
            weatherDao = dao,
            lat = lat,
            lon = lon,
            backgroundSourcesProvider = { emptyList() },
            displayedSourceIdsProvider = { emptySet() },
            fetchMetarFn = { emptyList() },
        )
    }

    override fun close() {
        db.getConnection().close()
        Files.deleteIfExists(path)
    }
}
