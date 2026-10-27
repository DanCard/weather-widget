package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `GraphDataLoader.loadCurrentTempInputHourlyForecasts` read the live table only, so the current
 * (started) hour carried its post-hour revision while desktop's stitched read carried the pre-hour
 * forecast. Plan: plans/261027-current-temp-reads-settled-hourly.md.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class CurrentTempInputSettledRoboTest {
    private lateinit var db: WeatherDatabase
    private val lat = 37.417
    private val lon = -122.089
    private val source = WeatherSource.OPEN_METEO
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 27, 12, 30)
    private fun ms(hour: Int, minute: Int = 0) = now.withHour(hour).withMinute(minute).atZone(zone).toInstant().toEpochMilli()

    @After
    fun close() = db.close()

    @Test
    fun `the current hour comes back at its pre-hour forecast, not the live revision`() = runBlocking {
        db = TestDatabase.create()
        val key = LocationMatch.quantize(lat) to LocationMatch.quantize(lon)
        db.hourlyForecastDao().insertAll(
            listOf(
                HourlyForecastEntity(
                    dateTime = ms(12), locationLat = key.first, locationLon = key.second, temperature = 70f,
                    condition = "Clear", source = source.id, fetchedAt = ms(12, 20),
                ),
            ),
        )
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                HourlyForecastHistoryEntity(
                    dateTime = ms(12), locationLat = key.first, locationLon = key.second, temperature = 60f,
                    condition = "Clear", source = source.id, timestampToGroupPredictions = ms(11, 40), fetchedAt = ms(11, 40),
                ),
                // Another source's history is not read for this display source.
                HourlyForecastHistoryEntity(
                    dateTime = ms(12), locationLat = key.first, locationLon = key.second, temperature = 50f,
                    condition = "Clear", source = WeatherSource.NWS.id, timestampToGroupPredictions = ms(11, 40), fetchedAt = ms(11, 40),
                ),
            ),
        )

        val rows = GraphDataLoader.loadCurrentTempInputHourlyForecasts(
            db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), source, lat, lon, now,
        )

        assertEquals(listOf(source.id to 60f), rows.filter { it.dateTime == ms(12) }.map { it.source to it.temperature })
    }
}
