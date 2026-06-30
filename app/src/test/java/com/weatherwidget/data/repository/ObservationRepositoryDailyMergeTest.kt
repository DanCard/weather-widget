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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.weatherwidget.data.model.DailyExtreme
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
            hourlyForecastDao = db.hourlyForecastDao(),
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
        assertNotNull("Expected an NWS DailyExtreme entry for today", todayActual)

        // BEFORE fix: this is 73.5 (mergeDailyActual maxOf picks the persisted value).
        // AFTER  fix: this is ~73.1 (live blended wins, persisted is not consulted).
        assertEquals(
            "Today's high must come from the live IDW blender, not the stale daily_extremes row",
            73.1f,
            todayActual!!.highTemp,
            0.1f,
        )
    }

    /**
     * Gate regression: a past day's daily_extreme whose temps/condition are unchanged but whose
     * precip arrives later must still be persisted. Before the fix, recomputeDailyExtremesForDay
     * only wrote when high/low/condition changed, so precip-only deltas were silently dropped and
     * rain never landed.
     *
     * Past days are measured-only (forecast fallback is suppressed for completed days), so the
     * late-arriving precip here is a *measured* station value, not a forecast.
     */
    @Test
    fun `recompute persists precip-only change for a past day`() = runTest {
        val yesterday = today.minusDays(1)
        val t10 = yesterday.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val t14 = yesterday.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val yStart = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        fun obs(ts: Long, precip: Float?) = TestData.observation(
            stationId = "KNEAR", timestamp = ts, temperature = 60f, distanceKm = 1f,
            api = WeatherSource.NWS.id,
        ).copy(precipAmountMm = precip)

        // Run 1: station observations carry temps but no measured precip yet (the row is null).
        db.observationDao().insertAll(listOf(obs(t10, null), obs(t14, null)))
        repository.recomputeDailyExtremesFromStoredObservations(lat, lon, yesterday, yesterday, emptyList())
        val afterRun1 = db.dailyExtremeDao().getExtremesInRange(yStart, yStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertNull("Precip should be null before measured precip arrives", afterRun1.precipAmountMm)

        // Measured precip now arrives (REPLACE on same PK); temps/condition unchanged.
        db.observationDao().insertAll(listOf(obs(t10, 2.0f), obs(t14, 3.0f)))

        // Run 2: only precip differs — the gate must persist it.
        repository.recomputeDailyExtremesFromStoredObservations(lat, lon, yesterday, yesterday, emptyList())
        val afterRun2 = db.dailyExtremeDao().getExtremesInRange(yStart, yStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertEquals(5.0f, afterRun2.precipAmountMm!!, 0.01f) // 2.0 + 3.0 measured
        assertEquals(60f, afterRun2.highTemp, 0.1f)           // temps unchanged
    }

    @Test
    fun `recomputeDailyExtremesFromStoredObservations skips days older than 9 days`() = runTest {
        val tenDaysAgo = today.minusDays(10)
        val t10 = tenDaysAgo.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val tenDaysAgoStart = tenDaysAgo.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        val obs = TestData.observation(
            stationId = "KNEAR", timestamp = t10, temperature = 80f, distanceKm = 1f,
            api = WeatherSource.NWS.id,
        )

        db.observationDao().insertAll(listOf(obs))
        
        // Recomputation should skip this day since it is 10 days ago (older than 9-day cutoff)
        repository.recomputeDailyExtremesFromStoredObservations(lat, lon, tenDaysAgo, tenDaysAgo, emptyList())
        
        val extremes = db.dailyExtremeDao().getExtremesInRange(tenDaysAgoStart, tenDaysAgoStart, lat, lon)
        assertTrue("Daily extreme should not be inserted or recomputed for a day older than 9 days", extremes.isEmpty())
    }
}
