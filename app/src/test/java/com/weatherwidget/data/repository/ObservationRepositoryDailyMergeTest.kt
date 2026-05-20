package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regression for the Daily-View vs Hourly-Graph discrepancy reproduced on emulator-5556:
 *   Daily View: 73.5°  /  Hourly Graph: 73.1°
 *
 * The live IDW blender already produces 73.1° (matching the Hourly Graph). The bug was
 * that getDailyActualsWithLiveToday then called mergeDailyActualsBySource with the
 * blended result as `primary` and the persisted daily_extreme as `secondary`, but
 * mergeDailyActual uses maxOf(primary.highTemp, secondary.highTemp) (widest-bounds
 * semantics), so the stale persisted 73.5° won.
 *
 * This test seeds the scenario directly into Room and asserts that the live blender
 * value is what reaches the caller.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ObservationRepositoryDailyMergeTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ObservationRepository

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

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
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `today high comes from live IDW blender, not stale persisted daily_extreme`() = runTest {
        val obsTimeMs = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

        // Two NWS stations reporting the same observation timestamp.
        // Near station (1km) reads 73.1°; far station (10km) reads 73.5°.
        // IDW-blended hourly series will be dominated by the near station (~73.1°).
        db.observationDao().insertAll(
            listOf(
                TestData.observation(
                    stationId = "KNEAR",
                    stationName = "Near",
                    timestamp = obsTimeMs,
                    temperature = 73.1f,
                    distanceKm = 1f,
                    api = WeatherSource.NWS.id,
                ),
                TestData.observation(
                    stationId = "KFAR",
                    stationName = "Far",
                    timestamp = obsTimeMs,
                    temperature = 73.5f,
                    distanceKm = 10f,
                    api = WeatherSource.NWS.id,
                ),
            ),
        )

        // Stale persisted daily_extreme row holds 73.5° — the kind of value
        // computeDailyExtremes produces (IDW of per-station-max, weighted differently
        // from IDW-by-hour). This is what was incorrectly winning the merge before the fix.
        db.dailyExtremeDao().insertAll(
            listOf(
                DailyExtremeEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id,
                    locationLat = lat,
                    locationLon = lon,
                    highTemp = 73.5f,
                    lowTemp = 58.7f,
                    condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        // Minimal hourly forecasts for the blender's forecast_extrapolated path —
        // not strictly needed here (both stations have an observed point at the
        // candidate time) but matches real-world conditions.
        val hourlyForecasts = (10..18).map { hour ->
            val timeStr = LocalDateTime.of(today, java.time.LocalTime.of(hour, 0)).toString()
            TestData.hourly(dateTime = timeStr, source = WeatherSource.NWS.id, temperature = 72f)
        }

        val result = repository.getDailyActualsWithLiveToday(
            latitude = lat,
            longitude = lon,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = listOf(WeatherSource.NWS.id),
        )

        val todayActual = result[WeatherSource.NWS.id]?.get(today)
        assertNotNull("Expected an NWS DailyActual entry for today", todayActual)

        // BEFORE fix: this is 73.5 (mergeDailyActual maxOf picks the persisted value).
        // AFTER  fix: this is ~73.1 (live blended wins, persisted is not consulted).
        assertEquals(
            "Today's high must come from the live IDW blender, not the stale daily_extremes row",
            73.1f,
            todayActual!!.highTemp,
            0.1f,
        )
    }
}
