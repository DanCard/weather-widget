package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.contract.NwsGapFillHostContract
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [NwsGapFillHostContract]; the Room half is `RoomNwsGapFillHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopNwsGapFillHostContractTest : NwsGapFillHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<NwsGapFillRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        // Network only: the contract never asks for stations or a station window.
        val repo = DesktopWeatherRepository(mockk(), dao, LAT, LON, "NWS")
        return repo.nwsGapFillHost(System.currentTimeMillis()) to DesktopSeed(dao)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    private class DesktopSeed(private val dao: DesktopWeatherDao) : Seed {
        override fun dailyHistory(row: DailyHistory) = dao.upsertDailyHistory(listOf(row))

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
