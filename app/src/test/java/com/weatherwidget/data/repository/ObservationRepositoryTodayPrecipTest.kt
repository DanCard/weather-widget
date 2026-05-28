package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression for the today-precip-drop bug: getDailyActualsWithLiveToday used to construct
 * today's DailyActual without any precip fields, so the daily-view rain label couldn't see
 * measured rain that was already in daily_extremes / observations. The fix is to compute
 * precip via the same resolveDailyPrecip helper the past-day persisted path uses.
 *
 * Past-day rendering is exercised by ObservationRepositoryDailyMergeTest; this class covers
 * the live-today branch specifically.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ObservationRepositoryTodayPrecipTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ObservationRepository

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()

    @Before
    fun setup() {
        db = TestDatabase.create()
        val context = RuntimeEnvironment.getApplication()
        repository = ObservationRepository(
            context = context,
            observationDao = db.observationDao(),
            dailyExtremeDao = db.dailyExtremeDao(),
            appLogDao = db.appLogDao(),
            nwsApi = mockk(relaxed = true),
            hourlyForecastDao = db.hourlyForecastDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun tsAt(hour: Int): Long =
        today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dtString(hour: Int): String = today.atTime(hour, 0).toString()

    /**
     * Measured branch: when observations carry precipAmountMm (Open-Meteo / Tomorrow.io /
     * Silurian pattern via their _MAIN pseudo-actuals), today's DailyActual must expose all
     * three precip values summed from those observations — and the measured precip must win
     * over forecast precip (we seed conflicting hourly forecasts to prove it).
     */
    @Test
    fun `today live DailyActual carries measured precip total day and night`() = runTest {
        val sourceId = WeatherSource.OPEN_METEO.id

        val dayObs = TestData.observation(
            stationId = "${sourceId}_MAIN",
            timestamp = tsAt(10),
            temperature = 65f,
            distanceKm = 0f,
            api = sourceId,
        ).copy(precipAmountMm = 1.0f)
        val nightObs = TestData.observation(
            stationId = "${sourceId}_MAIN",
            timestamp = tsAt(22),
            temperature = 60f,
            distanceKm = 0f,
            api = sourceId,
        ).copy(precipAmountMm = 3.0f)
        db.observationDao().insertAll(listOf(dayObs, nightObs))

        // Hourly forecasts with DIFFERENT precip — should be ignored because the measured
        // branch wins when any observation has non-null precipAmountMm.
        val hourlyForecasts = listOf(
            TestData.hourly(dateTime = dtString(10), source = sourceId, temperature = 65f).copy(precipAmountMm = 99f),
            TestData.hourly(dateTime = dtString(22), source = sourceId, temperature = 60f).copy(precipAmountMm = 99f),
        )

        val result = repository.getDailyActualsWithLiveToday(
            latitude = lat,
            longitude = lon,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = listOf(sourceId),
        )

        val todayActual = result[sourceId]?.get(today)
        assertNotNull("Expected a DailyActual for today", todayActual)
        assertEquals("Total precip = observation sum", 4.0f, todayActual!!.precipAmountMm!!, 0.01f)
        assertEquals("Day precip = 10AM observation", 1.0f, todayActual.precipDayMm!!, 0.01f)
        assertEquals("Night precip = 22:00 observation", 3.0f, todayActual.precipNightMm!!, 0.01f)
    }

    /**
     * Forecast-fallback branch (NWS hybrid): when observations have null precip,
     * today's DailyActual falls back to summing the source's hourly_forecasts precip over
     * the day/night windows. Before the fix, this value was dropped on the floor and the
     * today daily-view rain label could only fall back to forecast probability.
     */
    @Test
    fun `today live DailyActual falls back to forecast precip when observations have null precip`() = runTest {
        val sourceId = WeatherSource.NWS.id

        // Two NWS station readings, both with null precip (the real NWS pattern).
        val obs10 = TestData.observation(
            stationId = "KNEAR",
            timestamp = tsAt(10),
            temperature = 65f,
            distanceKm = 1f,
            api = sourceId,
        )
        val obs22 = TestData.observation(
            stationId = "KNEAR",
            timestamp = tsAt(22),
            temperature = 60f,
            distanceKm = 1f,
            api = sourceId,
        )
        db.observationDao().insertAll(listOf(obs10, obs22))

        val hourlyForecasts = listOf(
            TestData.hourly(dateTime = dtString(10), source = sourceId, temperature = 65f).copy(precipAmountMm = 1.5f),
            TestData.hourly(dateTime = dtString(22), source = sourceId, temperature = 60f).copy(precipAmountMm = 2.5f),
        )

        val result = repository.getDailyActualsWithLiveToday(
            latitude = lat,
            longitude = lon,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = listOf(sourceId),
        )

        val todayActual = result[sourceId]?.get(today)
        assertNotNull("Expected a DailyActual for today", todayActual)
        assertEquals("Total precip = forecast sum", 4.0f, todayActual!!.precipAmountMm!!, 0.01f)
        assertEquals("Day precip = 10AM forecast", 1.5f, todayActual.precipDayMm!!, 0.01f)
        assertEquals("Night precip = 22:00 forecast", 2.5f, todayActual.precipNightMm!!, 0.01f)
    }
}
