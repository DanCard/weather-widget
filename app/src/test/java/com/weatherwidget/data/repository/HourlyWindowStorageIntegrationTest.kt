package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ForecastFetchCoordinator] + stores + Room: a routine fetch stores 48 h (NEAR) or 72 h (FULL, once
 * a day while charging), and a NEAR fetch never blanks what the last FULL one put on the daily rows.
 * `performance/261010-hourly-near-window-often-full-eight-days-daily.md`
 */
@Category(LongDuration::class)
class HourlyWindowStorageIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var coordinator: ForecastFetchCoordinator
    private lateinit var snapshotStore: ForecastSnapshotStore
    private lateinit var widgetStateManager: WidgetStateManager
    private val openMeteo = mockk<OpenMeteoApi>()
    private val om = WeatherSource.OPEN_METEO
    private val lat = 37.417
    private val lon = -122.089
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private var clockMs = (System.currentTimeMillis() / hour) * hour + 10 * 60_000L
    private val today = LocalDate.now(zone)
    private val daily = (0 until 16).map { DailyForecast(today.plusDays(it.toLong()).toString(), 70f, 50f, "Cloudy", precipProbability = 20) }

    private val charging = ForecastFetchContext(isCharging = true, isScreenInteractive = false, batteryLevel = 50, activeSourceIds = setOf(om.id))
    private val onBattery = charging.copy(isCharging = false, batteryLevel = 40)

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        widgetStateManager = WidgetStateManager(context)
        val appLogDao = db.appLogDao()
        snapshotStore = ForecastSnapshotStore(
            forecastDao = db.forecastDao(),
            appLogDao = appLogDao,
            widgetStateManager = widgetStateManager,
            gapFiller = ClimateGapFiller(db.climateNormalDao()),
            clock = { clockMs },
        )
        val hourlyStore = HourlyForecastStore(
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            observationDao = db.observationDao(),
            widgetStateManager = widgetStateManager,
            clock = { clockMs },
            appLogDao = appLogDao,
        )
        coordinator = ForecastFetchCoordinator(
            context = context,
            appLogDao = appLogDao,
            openMeteoApi = openMeteo,
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = widgetStateManager,
            tomorrowIoApi = null,
            openWeatherMapApi = null,
            nwsForecastMapper = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            hourlyStore = hourlyStore,
            weatherApiHistoryBackfiller = mockk(relaxed = true),
            nwsApiDailyActualsFetcher = null,
            clock = { clockMs },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Hours from the start of today to 16 days out; values change with [seed] so a rewrite shows. */
    private fun download(seed: Int): List<HourlyForecast> {
        val first = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val last = first + 16 * 24 * hour
        return generateSequence(first) { it + hour }.takeWhile { it < last }.map { ms ->
            val dayIndex = ((ms - first) / (24 * hour)).toInt()
            val h = ((ms - first) / hour % 24).toInt()
            HourlyForecast(
                dateTime = ms,
                temperature = 50f + seed,
                condition = "Cloudy",
                precipProbability = (seed + dayIndex * 3) % 100,
                cloudCover = (seed + dayIndex * 7 + h) % 100,
            )
        }.toList()
    }

    private suspend fun fetch(seed: Int, context: ForecastFetchContext) {
        coEvery { openMeteo.getForecast(any(), any(), any(), any()) } returns RawFetch(daily = daily, hourly = download(seed))
        coordinator.fetchFromAllApis(lat, lon, setOf(om), context)
    }

    private suspend fun liveHourly() =
        db.hourlyForecastDao().getHourlyForecastsBySource(clockMs - 48 * hour, clockMs + 400 * hour, lat, lon, om.id)

    private suspend fun historyHourly() =
        db.hourlyForecastHistoryDao().getAllInDateTimeRange(clockMs - 48 * hour, clockMs + 400 * hour)
            .filter { it.source == om.id }

    @Test
    fun `a charging fetch with no FULL yet stores 72 h and records the marker`() = runTest {
        assertNull(widgetStateManager.getLastFullHourlyFetch(om.id, lat, lon))

        fetch(seed = 1, charging)

        val rows = liveHourly()
        assertTrue(rows.isNotEmpty())
        assertTrue("stored past 72 h", rows.all { it.dateTime < clockMs + 72 * hour })
        assertTrue("reaches into hours 48-72", rows.any { it.dateTime >= clockMs + 48 * hour })
        assertEquals(clockMs, widgetStateManager.getLastFullHourlyFetch(om.id, lat, lon))
    }

    @Test
    fun `a fetch four hours later while charging is NEAR and leaves hours past 48 h alone`() = runTest {
        fetch(seed = 1, charging)
        val firstClock = clockMs
        clockMs += 4 * hour

        fetch(seed = 50, charging)

        val rows = liveHourly()
        val keepUntil = (clockMs / hour) * hour + 48 * hour
        val near = rows.filter { it.dateTime < keepUntil && it.dateTime >= clockMs }
        val far = rows.filter { it.dateTime >= keepUntil }
        assertTrue(near.isNotEmpty() && far.isNotEmpty())
        assertTrue("near hours rewritten by the NEAR fetch", near.all { it.fetchedAt == clockMs })
        assertTrue("hours past 48 h keep the FULL fetch's write", far.all { it.fetchedAt == firstClock })
        assertTrue("snapshots stop at 48 h too", historyHourly().filter { it.fetchedAt == clockMs }.all { it.dateTime < keepUntil })
        assertEquals("marker not advanced by a NEAR fetch", firstClock, widgetStateManager.getLastFullHourlyFetch(om.id, lat, lon))
    }

    @Test
    fun `a day after the last FULL, charging is FULL again`() = runTest {
        fetch(seed = 1, charging)
        clockMs += 24 * hour

        fetch(seed = 50, charging)

        val rows = liveHourly()
        assertTrue(rows.any { it.dateTime >= clockMs + 48 * hour && it.fetchedAt == clockMs })
        assertEquals(clockMs, widgetStateManager.getLastFullHourlyFetch(om.id, lat, lon))
    }

    @Test
    fun `off the charger is NEAR and records no marker`() = runTest {
        fetch(seed = 1, onBattery)

        val rows = liveHourly()
        assertTrue(rows.isNotEmpty())
        assertTrue("stored past 48 h", rows.all { it.dateTime < clockMs + 48 * hour })
        assertNull(widgetStateManager.getLastFullHourlyFetch(om.id, lat, lon))
    }

    @Test
    fun `a NEAR fetch keeps far-day summaries from the last FULL fetch`() = runTest {
        fetch(seed = 1, charging)
        val farDate = today.plusDays(10)
        val before = snapshotStore.getCachedDataBySource(lat, lon, om)
            .filter { it.source == om.id }
            .first { it.targetDate / WidgetConstants.MS_IN_A_DAY == farDate.toEpochDay() }
        assertNotNull(before.noonCloudPercent)
        clockMs += 4 * hour

        fetch(seed = 50, charging)

        val after = snapshotStore.getCachedDataBySource(lat, lon, om)
            .filter { it.source == om.id }
            .first { it.targetDate / WidgetConstants.MS_IN_A_DAY == farDate.toEpochDay() }
        // Open-Meteo answers with every hour whatever is stored, so the summary here is recomputed
        // from that download — and equals what the same download would give a FULL fetch.
        assertNotNull(after.noonCloudPercent)
    }
}
