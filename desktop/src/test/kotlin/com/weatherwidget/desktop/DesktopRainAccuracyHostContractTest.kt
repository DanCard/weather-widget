package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.RainAccuracyHostContract
import com.weatherwidget.shared.contract.RainAccuracyScenario
import com.weatherwidget.shared.stats.RainAccuracyRunner
import com.weatherwidget.stats.desktop.DesktopRainAccuracyHost
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files

/** Desktop SQLite half of [RainAccuracyHostContract]; the Room half is `RoomRainAccuracyHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopRainAccuracyHostContractTest : RainAccuracyHostContract() {
    private val path = Files.createTempFile("weather_rain_contract", ".db")
    private lateinit var dao: DesktopWeatherDao

    override fun open(): RainAccuracyRunner.Host {
        dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return DesktopRainAccuracyHost(dao)
    }

    override suspend fun seedSnapshots(source: WeatherSource, bucketMs: Long, rows: List<RainAccuracyScenario.Hour>) =
        dao.upsertHourlyForecastHistory(
            RainAccuracyScenario.LAT,
            RainAccuracyScenario.LON,
            source.id,
            bucketMs,
            rows.map {
                HourlyForecast(
                    dateTime = it.dateTimeMs,
                    temperature = 50f,
                    condition = "Rain",
                    precipAmountMm = it.precipAmountMm,
                    source = source.id,
                    fetchedAt = bucketMs,
                    locationLat = RainAccuracyScenario.LAT,
                    locationLon = RainAccuracyScenario.LON,
                )
            },
        )

    override suspend fun seedObservations(rows: List<ObservationReading>) {
        dao.upsertObservations(
            rows.map {
                DesktopObservationEntity(
                    stationId = it.stationId, stationName = it.stationName, timestamp = it.timestamp,
                    temperature = it.temperature, condition = it.condition, locationLat = it.locationLat,
                    locationLon = it.locationLon, distanceKm = it.distanceKm, stationType = it.stationType,
                    fetchedAt = it.fetchedAt, api = it.api, precipAmountMm = it.precipAmountMm,
                )
            },
        )
    }

    override suspend fun seedArchive(rows: List<HourlyActual>) = dao.insertHourlyActuals(rows)

    @After
    fun deleteDatabase() {
        Files.deleteIfExists(path)
    }
}
