package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.contract.NwsStationBundlesHostContract
import com.weatherwidget.shared.observations.NwsStationBundles
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [NwsStationBundlesHostContract]; the Room half is `RoomNwsStationBundlesHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopNwsStationBundlesHostContractTest : NwsStationBundlesHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<NwsStationBundles.Host, Db> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val database = DesktopWeatherDatabase(path).apply { initialize() }
        val dao = DesktopWeatherDao(database)
        val db = object : Db {
            override fun observation(row: ObservationReading) = dao.upsertObservations(
                listOf(
                    DesktopObservationEntity(
                        stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                        temperature = row.temperature, condition = row.condition,
                        locationLat = row.locationLat, locationLon = row.locationLon, fetchedAt = row.fetchedAt, api = row.api,
                    ),
                ),
            )

            override fun fetchedAts(): Map<Triple<String, Long, Double>, Long> =
                database.getConnection().use { conn ->
                    conn.createStatement().executeQuery("SELECT stationId, timestamp, locationLat, fetchedAt FROM observations").use { rs ->
                        buildMap { while (rs.next()) put(Triple(rs.getString(1), rs.getLong(2), rs.getDouble(3)), rs.getLong(4)) }
                    }
                }
        }
        return DesktopNwsStationBundlesHost(dao, LAT, LON) { NOW } to db
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
