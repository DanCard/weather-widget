package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.contract.DailyBlendRecomputeHostContract
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [DailyBlendRecomputeHostContract]; the Room half is `RoomDailyBlendRecomputeHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopDailyBlendRecomputeHostContractTest : DailyBlendRecomputeHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<DailyBlendRecomputeRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val repo = DesktopWeatherRepository(mockk(), dao, LAT, LON, "NWS")
        return repo.blendRecomputeHost() to DesktopSeed(dao)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    private class DesktopSeed(private val dao: DesktopWeatherDao) : Seed {
        override fun dailyHistory(row: DailyHistory) = dao.upsertDailyHistory(listOf(row))

        override fun hourly(row: HourlyForecast) = dao.upsertHourlyForecasts(LAT, LON, row.source!!, listOf(row))

        override fun observation(row: ObservationReading) = dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                    temperature = row.temperature, condition = row.condition,
                    locationLat = row.locationLat, locationLon = row.locationLon, distanceKm = row.distanceKm,
                    stationType = row.stationType, fetchedAt = row.fetchedAt, api = row.api,
                ),
            ),
        )
    }
}
