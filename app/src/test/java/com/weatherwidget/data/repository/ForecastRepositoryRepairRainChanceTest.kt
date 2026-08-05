package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repairs daily_history rain-chance columns frozen from RAW proximity-box hourly rows, before the
 * freeze path resolved a site. Reproduces the 2026-07-13 Samsung case with that device's real
 * coordinates and values: the archive held 9% (a GPS-jitter fragment 0.6km away) while the site's
 * own rows — and the hourly graph drawn from them — said 4%.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastRepositoryRepairRainChanceTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository

    // The device's real site; TestData's default box centre.
    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.now().minusDays(1)
    private val dayStartKey = day.toEpochDay() * WidgetConstants.MS_IN_A_DAY

    @Before
    fun setup() {
        db = TestDatabase.create()
        repository = ForecastRepository(
            context = RuntimeEnvironment.getApplication(),
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = mockk(relaxed = true),
            openMeteoApi = mockk(relaxed = true),
            visualCrossingApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = mockk(relaxed = true),
            climateNormalDao = mockk(relaxed = true),
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            observationRepository = mockk(relaxed = true),
            nwsForecastMapper = mockk(relaxed = true),
            dailyActualsStore = DailyActualsStore(db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(), mockk(relaxed = true)),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun historyRow(
        hourOfDay: Int,
        chance: Int,
        rowLat: Double,
        rowLon: Double,
        fetchedAt: Long,
    ) = HourlyForecastHistoryEntity(
        dateTime = day.atTime(hourOfDay, 0).atZone(zone).toInstant().toEpochMilli(),
        locationLat = rowLat,
        locationLon = rowLon,
        temperature = 70f,
        condition = "Clear",
        source = WeatherSource.NWS.id,
        timestampToGroupPredictions = 0L,
        precipProbability = chance,
        fetchedAt = fetchedAt,
    )

    private suspend fun storedChance(): Pair<Int?, Int?> =
        db.dailyHistoryDao().getExtremesInRange(dayStartKey, dayStartKey, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
            .let { it.forecastDayPrecipChance to it.forecastNightPrecipChance }

    private suspend fun seedPoisonedArchive() {
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = dayStartKey, source = WeatherSource.NWS.id,
                    locationLat = lat, locationLon = lon,
                    computedHighTemp = 84f, computedLowTemp = 64f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    // What the box-wide max froze: a neighbouring fragment's value.
                    forecastDayPrecipChance = 9,
                    forecastNightPrecipChance = 3,
                ),
            ),
        )
    }

    @Test
    fun `repair replaces a fragment-poisoned chance with the site's own value`() = runTest {
        seedPoisonedArchive()
        val duringDay = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                // The real site — 4%.
                historyRow(10, 4, lat, lon, duringDay),
                historyRow(14, 3, lat, lon, duringDay),
                // GPS-jitter fragments inside the proximity box — the poison.
                historyRow(10, 9, lat + 0.007, lon + 0.001, duringDay),
                historyRow(14, 13, lat + 0.005, lon + 0.002, duringDay),
            ),
        )

        repository.repairFrozenRainChanceIfNeeded(lat, lon)

        assertEquals(4, storedChance().first)
    }

    /**
     * Retention ages hourly_forecast_history out long before daily_history. A day we can no longer
     * re-derive must keep its archived value — a wrong number beats an erased one.
     */
    @Test
    fun `repair leaves the existing value alone when history cannot re-derive it`() = runTest {
        seedPoisonedArchive()

        repository.repairFrozenRainChanceIfNeeded(lat, lon)

        assertEquals(9 to 3, storedChance())
    }

    /** One-time: the pref gate must stop it re-scanning on every fetch. */
    @Test
    fun `repair runs once`() = runTest {
        seedPoisonedArchive()
        val duringDay = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        db.hourlyForecastHistoryDao().insertAll(listOf(historyRow(10, 4, lat, lon, duringDay)))

        repository.repairFrozenRainChanceIfNeeded(lat, lon)
        assertEquals(4, storedChance().first)

        // A later poisoned write would NOT be repaired again — the pass is one-time by design.
        db.dailyHistoryDao().insertAll(
            listOf(
                db.dailyHistoryDao().getExtremesInRange(dayStartKey, dayStartKey, lat, lon)
                    .first { it.source == WeatherSource.NWS.id }
                    .copy(forecastDayPrecipChance = 99),
            ),
        )
        repository.repairFrozenRainChanceIfNeeded(lat, lon)
        assertEquals(99, storedChance().first)
    }
}
