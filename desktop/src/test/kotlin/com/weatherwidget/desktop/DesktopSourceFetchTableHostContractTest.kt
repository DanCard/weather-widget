package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.SourceFetchTable
import com.weatherwidget.shared.contract.SourceFetchTableHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [SourceFetchTableHostContract]; the Room half is `RoomSourceFetchTableHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopSourceFetchTableHostContractTest : SourceFetchTableHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Db {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return object : Db {
            override fun host() = DesktopSourceFetchHost(
                calls = SourceFetchTable.Calls(nws = { _, _, _ -> RawFetch() }, openMeteo = { _, _, _, _ -> RawFetch() }),
                weatherDao = dao,
                needsHistory = { false },
                nowcastStore = null,
            )

            override suspend fun writeHourly(sourceId: String, lat: Double, lon: Double, rows: List<HourlyForecast>) =
                DesktopForecastPersistHost(dao, sourceId, lat, lon) { null }.writeHourly(rows)
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
