package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.contract.StitchedHourlyReadContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.HourlyForecastLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room half of [StitchedHourlyReadContract], through `HourlyForecastLoader.withHistory` (the
 * startup/tap paints; `load` and `GraphDataLoader` issue the same unwindowed history query). The
 * desktop half is `DesktopStitchedHourlyReadContractTest` (`:desktop`).
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomStitchedHourlyReadContractTest : StitchedHourlyReadContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<Reader, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val reader = Reader { startMs, endMs, nowMs ->
            val sources = listOf(SOURCE)
            val current = db.hourlyForecastDao().getHourlyForecastsForSources(startMs, endMs, LAT, LON, sources)
            HourlyForecastLoader.withHistory(db, current, startMs, endMs, LAT, LON, sources, nowMs)
                .map { it.toHourlyForecast() }
        }
        return reader to RoomSeed(db)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private class RoomSeed(private val db: WeatherDatabase) : Seed {
        override fun hourly(row: HourlyForecast) = runBlocking {
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                        temperature = row.temperature, condition = row.condition, source = row.source!!,
                        cloudCover = row.cloudCover, fetchedAt = row.fetchedAt,
                    ),
                ),
            )
        }

        override fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long) = runBlocking {
            db.hourlyForecastHistoryDao().insertAll(
                listOf(
                    HourlyForecastHistoryEntity(
                        dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                        temperature = row.temperature, condition = row.condition, source = row.source!!,
                        timestampToGroupPredictions = snapshotBucketMs, cloudCover = row.cloudCover,
                        fetchedAt = row.fetchedAt,
                    ),
                ),
            )
        }
    }
}
