package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.NwsStationActualsRun
import com.weatherwidget.shared.contract.NwsStationActualsHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [NwsStationActualsHostContract]; the Room half is `RoomNwsStationActualsHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopNwsStationActualsHostContractTest : NwsStationActualsHostContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Pair<NwsStationActualsRun.Host, Seed> {
        val path = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        val host = DesktopNwsStationActualsHost(
            dao, LAT, LON, personalStationWeight = 1.0,
            stations = { error("no network") }, stationDay = { _, _, _ -> error("no network") },
        )
        return host to DesktopSeed(db, dao)
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    /** Plain INSERTs for hourly (the DAO's writers quantize and stamp `fetchedAt`); observations as stored. */
    private class DesktopSeed(private val db: DesktopWeatherDatabase, private val dao: DesktopWeatherDao) : Seed {
        override fun hourly(row: HourlyForecast) = insert(
            "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source, row.fetchedAt,
        )

        override fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long) = insert(
            "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "timestampToGroupPredictions, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            row.dateTime, row.locationLat, row.locationLon, row.temperature, row.condition, row.source,
            snapshotBucketMs, row.fetchedAt,
        )

        override fun observation(row: ObservationReading) {
            dao.upsertObservations(
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

        private fun insert(sql: String, vararg args: Any?) {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    args.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                    stmt.executeUpdate()
                }
            }
        }
    }
}
