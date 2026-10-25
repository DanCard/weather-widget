package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ForecastFetchCoordinator] + stores + Room: every fetch is LONG — it stores every daily row and
 * 8 days of live hourly (72 h snapshotted; `performance/261016-long-fetch-keeps-eight-days-of-live-hourly.md`)
 * and sets the marker. performance/261025-every-forecast-fetch-long.md
 */
@Category(LongDuration::class)
class ForecastHorizonStorageIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var coordinator: ForecastFetchCoordinator
    private lateinit var widgetStateManager: WidgetStateManager
    private val openMeteo = mockk<OpenMeteoApi>()
    private val om = WeatherSource.OPEN_METEO
    private val lat = 37.417
    private val lon = -122.089
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private var clockMs = today.atTime(8, 10).atZone(zone).toInstant().toEpochMilli()
    private val daily = (0 until 16).map { DailyForecast(today.plusDays(it.toLong()).toString(), 70f, 50f, "Cloudy", precipProbability = 20) }

    /** Battery 85 %, screen on: every 4 h. */
    private val high = ForecastFetchContext(isCharging = false, isScreenInteractive = true, batteryLevel = 85, activeSourceIds = setOf(om.id))
    private val low = high.copy(batteryLevel = 60)

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        widgetStateManager = WidgetStateManager(context)
        val appLogDao = db.appLogDao()
        val snapshotStore = ForecastSnapshotStore(
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
        coEvery { openMeteo.getForecast(any(), any(), any(), any(), any()) } returns RawFetch(daily = daily, hourly = download(seed))
        coordinator.fetchFromAllApis(lat, lon, setOf(om), context)
    }

    private suspend fun liveHourly() =
        db.hourlyForecastDao().getHourlyForecastsBySource(clockMs - 48 * hour, clockMs + 400 * hour, lat, lon, om.id)

    /** The newest daily row per date, keyed by days from today. */
    private suspend fun dailyRows(): Map<Long, ForecastEntity> {
        val start = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        return db.forecastDao()
            .getLatestForecastsInRangeBySourceAllSites(start, start + 20 * WidgetConstants.MS_IN_A_DAY, lat, lon, om.id)
            .associateBy { it.targetDate / WidgetConstants.MS_IN_A_DAY - today.toEpochDay() }
    }

    @Test
    fun `the first fetch is LONG - every daily row, 8 days of live hourly, the marker`() = runTest {
        assertNull(widgetStateManager.getLastLongFetch(om.id, lat, lon))

        fetch(seed = 1, high)

        val rows = dailyRows()
        assertTrue("all 16 days stored", (0L until 16L).all { it in rows })
        assertTrue(rows.values.all { it.lastConfirmedAt == clockMs })
        val hours = liveHourly()
        assertTrue("not stored past 8 days", hours.all { it.dateTime < clockMs + 192 * hour })
        assertTrue("reaches into day 8", hours.any { it.dateTime >= clockMs + 168 * hour })
        assertEquals(clockMs, widgetStateManager.getLastLongFetch(om.id, lat, lon))
    }

    @Test
    fun `four hours later the fetch is LONG again - every daily row and day 8 hourly rewritten`() = runTest {
        // Was SHORT before 2026-10-25: only the next 3 days rewritten.
        fetch(seed = 1, high)
        clockMs += 4 * hour

        fetch(seed = 50, high)

        assertTrue(dailyRows().values.all { it.lastConfirmedAt == clockMs })
        assertTrue("day 8 hourly refreshed", liveHourly().any { it.dateTime >= clockMs + 168 * hour && it.fetchedAt == clockMs })
        assertEquals(clockMs, widgetStateManager.getLastLongFetch(om.id, lat, lon))
    }

    @Test
    fun `a fetch with nothing due is LONG too, on any battery`() = runTest {
        fetch(seed = 1, low)
        clockMs += hour

        fetch(seed = 50, low)

        assertTrue(dailyRows().values.all { it.lastConfirmedAt == clockMs })
        assertEquals(clockMs, widgetStateManager.getLastLongFetch(om.id, lat, lon))
    }

    @Test
    fun `a manual refresh is LONG for every source, displayed or not`() = runTest {
        fetch(seed = 1, high)
        clockMs += hour

        fetch(seed = 50, high.copy(userRefresh = true, activeSourceIds = setOf(WeatherSource.NWS.id)))

        assertEquals(clockMs, dailyRows().getValue(6L).lastConfirmedAt)
    }

    @Test
    fun `four hours later the far-day summaries refresh`() = runTest {
        fetch(seed = 1, high)
        val before = dailyRows().getValue(10L)
        assertNotNull(before.noonCloudPercent)
        clockMs += 4 * hour

        fetch(seed = 50, high)

        val after = dailyRows().getValue(10L)
        assertNotEquals(before.noonCloudPercent, after.noonCloudPercent)
        assertEquals(clockMs, after.lastConfirmedAt)
    }

    /** Hourly checks for a day: fetch whenever a horizon is due; count what each fetch was. */
    private suspend fun simulateDay(context: ForecastFetchContext): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        repeat(24) {
            val due = coordinator.horizonDecision(om, context, lat, lon)?.due
            if (due != null) {
                fetch(seed = it, context)
                counts.merge(due.name, 1, Int::plus)
            }
            clockMs += hour
        }
        return counts
    }

    @Test
    fun `a day at 85 percent with the screen on is 6 LONG fetches`() = runTest {
        // At 0, 4, 8, 12, 16, 20 h (was 3 LONG + 3 SHORT).
        assertEquals(mapOf("LONG" to 6), simulateDay(high))
    }

    @Test
    fun `a day below 70 percent is one LONG fetch`() = runTest {
        assertEquals(mapOf("LONG" to 1), simulateDay(low))
    }

    @Test
    fun `a day at 85 percent with the screen off is 3 LONG fetches`() = runTest {
        assertEquals(mapOf("LONG" to 3), simulateDay(high.copy(isScreenInteractive = false)))
    }

    @Test
    fun `charging overnight - the alarm makes the fetch due before it, then nothing is due`() = runTest {
        val night = high.copy(isCharging = true, isScreenInteractive = false)
        fetch(seed = 1, night)
        clockMs += 5 * hour
        val alarm = clockMs + 30 * 60_000L
        val beforeAlarm = night.copy(wakeAtMs = alarm)
        assertNull("no alarm: not due at 5 h of 8", coordinator.horizonDecision(om, night, lat, lon)?.due)

        assertEquals("LONG", coordinator.horizonDecision(om, beforeAlarm, lat, lon)?.due?.name)
        fetch(seed = 50, beforeAlarm)

        assertEquals("stored the far day", clockMs, dailyRows().getValue(10L).lastConfirmedAt)
        assertEquals(clockMs, widgetStateManager.getLastLongFetch(om.id, lat, lon))
        clockMs += 10 * 60_000L
        assertNull("snoozed: nothing due", coordinator.horizonDecision(om, night.copy(wakeAtMs = alarm + 9 * 60_000L), lat, lon)?.due)
    }
}
