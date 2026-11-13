package com.weatherwidget.stats

import com.weatherwidget.data.local.HourlyActualEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.RainAccuracyHostContract
import com.weatherwidget.shared.contract.RainAccuracyScenario
import com.weatherwidget.shared.stats.RainAccuracyRunner
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [RainAccuracyHostContract]; the desktop half is `DesktopRainAccuracyHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomRainAccuracyHostContractTest : RainAccuracyHostContract() {
    private lateinit var db: WeatherDatabase

    override fun open(): RainAccuracyRunner.Host {
        db = TestDatabase.create()
        return RoomRainAccuracyHost(db.hourlyForecastHistoryDao(), db.observationDao(), db.hourlyActualDao())
    }

    override suspend fun seedSnapshots(source: WeatherSource, bucketMs: Long, rows: List<RainAccuracyScenario.Hour>) =
        db.hourlyForecastHistoryDao().insertAll(
            rows.map {
                HourlyForecastEntity(
                    dateTime = it.dateTimeMs,
                    locationLat = RainAccuracyScenario.LAT,
                    locationLon = RainAccuracyScenario.LON,
                    temperature = 50f,
                    condition = "Rain",
                    source = source.id,
                    precipAmountMm = it.precipAmountMm,
                    fetchedAt = bucketMs,
                    timestampToGroupPredictions = bucketMs,
                )
            },
        )

    override suspend fun seedObservations(rows: List<ObservationReading>) = db.observationDao().insertAll(
        rows.map {
            ObservationEntity(
                stationId = it.stationId, stationName = it.stationName, timestamp = it.timestamp,
                temperature = it.temperature, condition = it.condition, locationLat = it.locationLat,
                locationLon = it.locationLon, distanceKm = it.distanceKm, stationType = it.stationType,
                fetchedAt = it.fetchedAt, api = it.api, precipAmountMm = it.precipAmountMm,
            )
        },
    )

    override suspend fun seedArchive(rows: List<HourlyActual>) = db.hourlyActualDao().insertAll(
        rows.map {
            HourlyActualEntity(
                hourMs = it.hourMs, source = it.source, locationLat = it.locationLat, locationLon = it.locationLon,
                precipAmountMm = it.precipAmountMm, archivedAt = it.archivedAt,
            )
        },
    )

    @After
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }
}
