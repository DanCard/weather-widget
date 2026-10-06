package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Google Weather is forecast-only, so its daily actuals must come from the borrowed provider
 * (METAR by default) through the real [DailyActualsStore] → Room path — not from its own
 * current-conditions row, which is model output. Exercises the borrowing end to end rather than
 * just [ActualsProviderResolver.borrows].
 */
@Category(LongDuration::class)
class GoogleWeatherBorrowedActualsIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val lat = 37.4168
    private val lon = -122.0890
    private val zone: ZoneId = ZoneId.systemDefault()
    private val yesterday: LocalDate = LocalDate.now().minusDays(1)

    private fun at(hour: Int): Long =
        yesterday.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        ActualsProviderResolver.resetPreferenceSource()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 },
        )
    }

    @After
    fun teardown() {
        db.close()
        ActualsProviderResolver.resetPreferenceSource()
    }

    private fun obs(stationId: String, api: String, timestamp: Long, temp: Float) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 2f,
        stationType = "OFFICIAL",
        fetchedAt = timestamp,
        api = api,
    )

    @Test
    fun `Google's past-day high and low are METAR's, not its own reading`() = runTest {
        db.observationDao().insertAll(
            listOf(0 to 58.0f, 6 to 55.0f, 12 to 66.0f, 15 to 74.0f, 19 to 68.0f, 23 to 60.0f).map { (h, t) ->
                obs("KNUQ", WeatherSource.METAR.id, at(h), t)
            } +
                // Google's own current-conditions row: model output, must not become the actual.
                obs("GOOGLE_WEATHER_MAIN", WeatherSource.GOOGLE_WEATHER.id, at(15), 99.0f),
        )

        store.recomputeDailyExtremesForDay(lat, lon, yesterday, emptyList())
        val actuals = store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(WeatherSource.GOOGLE_WEATHER.id))

        val row = actuals[WeatherSource.GOOGLE_WEATHER.id]?.get(yesterday)
        assertNotNull("Google should have a borrowed actual for yesterday; got ${actuals.keys}", row)
        assertEquals(74.0f, row!!.computedHighTemp!!, 0.5f)
        assertEquals(55.0f, row.computedLowTemp!!, 0.5f)
    }
}
