package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ForecastFetchCoordinator] + [HourlyForecastStore] + [ForecastSnapshotStore] + Room: hourly is
 * stored to 72 h (deeper only for an on-demand day), and the daily rows keep noon cloud and the rain
 * maxima for every day — never blanked by a fetch that does not reach a day.
 * `performance/261010-daily-view-summaries-instead-of-far-hourly.md`
 */
@Category(LongDuration::class)
class DailySummaryStorageIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var coordinator: ForecastFetchCoordinator
    private lateinit var snapshotStore: ForecastSnapshotStore
    private val openMeteo = mockk<OpenMeteoApi>()
    private val om = WeatherSource.OPEN_METEO
    private val lat = 37.417
    private val lon = -122.089
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private var clockMs = (System.currentTimeMillis() / hour) * hour + 10 * 60_000L
    private val today = LocalDate.now(zone)
    private val days = (0 until 16).map { today.plusDays(it.toLong()) }

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val widgetStateManager = WidgetStateManager(context)
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

    /** Distinct per day and seed, so a carried value can be told from a recomputed one. */
    private fun download(seed: Int, hoursFromNow: Int = 16 * 24): List<HourlyForecast> {
        val first = at(today, 0)
        val last = (clockMs / hour) * hour + hoursFromNow * hour
        return generateSequence(first) { it + hour }.takeWhile { it < last }.map { ms ->
            val dayIndex = ((ms - first) / (24 * hour)).toInt()
            val h = ((ms - first) / hour % 24).toInt()
            HourlyForecast(
                dateTime = ms,
                temperature = 60f,
                condition = "Cloudy",
                precipProbability = (seed + dayIndex * 3 + if (h >= 20 || h < 8) 1 else 0) % 100,
                cloudCover = (seed + dayIndex * 7 + h) % 100,
            )
        }.toList()
    }

    private val daily = days.map { DailyForecast(it.toString(), 70f, 50f, "Cloudy", precipProbability = 20) }

    private suspend fun fetch(hourly: List<HourlyForecast>, context: ForecastFetchContext? = null) {
        coEvery { openMeteo.getForecast(any(), any(), any(), any()) } returns RawFetch(daily = daily, hourly = hourly)
        if (context == null) {
            coordinator.fetchFromAllApis(lat, lon, setOf(om))
        } else {
            coordinator.fetchSingleSource(lat, lon, om, context)
        }
    }

    private suspend fun rows(): Map<LocalDate, ForecastEntity> =
        snapshotStore.getCachedDataBySource(lat, lon, om)
            .filter { it.source == om.id }
            .associateBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }

    private suspend fun liveHourly() =
        db.hourlyForecastDao().getHourlyForecastsBySource(clockMs - 48 * hour, clockMs + 400 * hour, lat, lon, om.id)

    @Test
    fun `a 16-day fetch stores hourly to 72 h, live and snapshot, and every day's summary on its row`() = runTest {
        fetch(download(seed = 5))

        val keepUntil = DailyHourlySummaries.keepUntilMs(clockMs, 72)
        val live = liveHourly()
        assertTrue(live.isNotEmpty() && live.maxOf { it.dateTime } < keepUntil)
        val history = db.hourlyForecastHistoryDao().getHistoryInRangeAllSnapshots(clockMs, clockMs + 400 * hour, lat, lon)
        assertTrue(history.isNotEmpty() && history.maxOf { it.dateTime } < keepUntil)

        val stored = rows()
        days.drop(1).dropLast(1).forEach { day ->
            val row = stored.getValue(day)
            assertNotNull("$day noon", row.noonCloudPercent)
            assertNotNull("$day day", row.hourlyDayPrecipMax)
            assertNotNull("$day night", row.hourlyNightPrecipMax)
            assertEquals("no provider period: the stored chance is the hourly max", row.hourlyDayPrecipMax, row.daytimePrecipProbability)
            assertEquals(row.hourlyNightPrecipMax, row.nighttimePrecipProbability)
        }
    }

    @Test
    fun `a later fetch reaching only 24 h keeps every later day's values`() = runTest {
        fetch(download(seed = 5))
        val before = rows()

        clockMs += 10 * 60_000L
        fetch(download(seed = 40, hoursFromNow = 24))
        val after = rows()

        days.drop(2).dropLast(1).forEach { day ->
            assertEquals("$day", before.getValue(day).hourlySummary, after.getValue(day).hourlySummary)
            assertEquals("$day day chance", before.getValue(day).daytimePrecipProbability, after.getValue(day).daytimePrecipProbability)
        }
    }

    @Test
    fun `a later full fetch with a new noon cloud writes it, even with the high and low unchanged`() = runTest {
        fetch(download(seed = 5))
        val first = rows().getValue(today.plusDays(9))

        clockMs += 5 * hour
        fetch(download(seed = 40))
        val second = rows().getValue(today.plusDays(9))

        assertEquals("high/low unchanged", first.highTemp, second.highTemp)
        assertTrue("noon replaced", first.noonCloudPercent != second.noonCloudPercent)
        assertTrue(first.hourlyDayPrecipMax != second.hourlyDayPrecipMax)
    }

    @Test
    fun `an on-demand fetch keeps the free source's whole horizon`() = runTest {
        val day6 = today.plusDays(6)
        val hours = HourlyOnDemand.hoursToCover(om.id, day6, zone, clockMs, emptyList())!!
        fetch(
            download(seed = 5),
            ForecastFetchContext(
                isCharging = true,
                isScreenInteractive = true,
                batteryLevel = 100,
                activeSourceIds = setOf(om.id),
                hourlyAhead = HourlyOnDemand.Request(om.id, hours),
            ),
        )

        val live = liveHourly()
        assertTrue("day 6 stored to its last hour", live.any { it.dateTime == at(day6, 23) })
        assertEquals(
            "a fresh deep fetch: nothing more to fetch for any day",
            null,
            HourlyOnDemand.hoursToCover(om.id, today.plusDays(12), zone, clockMs, live.map { it.toHourlyForecast() }),
        )
    }

    /** The widget's daily paint reads the 72 h store and the rows; it must draw what the full download drew. */
    @Test
    fun `the daily view resolves the same noon cloud and rain chances as from the full download`() = runTest {
        val full = download(seed = 17)
        fetch(full)
        val live = liveHourly()
        val rows = rows()
        val fullAsStored = full.map { it.copy(source = om.id, fetchedAt = clockMs) }

        days.dropLast(1).forEach { day ->
            val row = rows.getValue(day)
            assertEquals(
                "$day noon",
                DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(fullAsStored, day, om.id),
                row.noonCloudPercent
                    ?: DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(live.map { it.toHourlyForecast() }, day, om.id),
            )
            val before = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = row.copy(noonCloudPercent = null, hourlyDayPrecipMax = null, hourlyNightPrecipMax = null),
                hourlyForecasts = fullAsStored.map {
                    com.weatherwidget.data.local.HourlyForecastEntity(
                        dateTime = it.dateTime,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = it.temperature,
                        condition = it.condition,
                        source = om.id,
                        precipProbability = it.precipProbability,
                        cloudCover = it.cloudCover,
                        fetchedAt = clockMs,
                    )
                },
                targetDate = day,
                isPast = false,
                displaySource = om,
                centerLat = lat,
                centerLon = lon,
            )
            val after = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = row,
                hourlyForecasts = live,
                targetDate = day,
                isPast = false,
                displaySource = om,
                centerLat = lat,
                centerLon = lon,
            )
            assertEquals("$day rain", before, after)
        }
    }
}
