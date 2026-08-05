package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter

/**
 * Covers [ForecastRepository.getHistoricalNormalsByMonthDay]: averaging a multi-year
 * window of observed daily temps into 12 monthly means (kept to one decimal), caching
 * 12 rows, and expanding back to per-day values by interpolation between month midpoints.
 */
@Category(ShortDuration::class)
class ClimateNormalsTest {
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var climateNormalDao: ClimateNormalDao
    private lateinit var repository: ForecastRepository

    private val testLat = 37.42
    private val testLon = -122.08

    // Distinct per-month base highs/lows; June is fractional to prove the tenth survives.
    private fun baseHigh(month: Int): Float = if (month == 6) 76.5f else (month * 5 + 40).toFloat()
    private fun baseLow(month: Int): Float = if (month == 6) 54.1f else (month * 5 + 20).toFloat()

    @Before
    fun setup() {
        val context = mockk<Context>(relaxed = true)
        val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs

        openMeteoApi = mockk()
        widgetStateManager = mockk(relaxed = true)
        climateNormalDao = mockk(relaxed = true)

        repository = ForecastRepository(
            context,
            mockk<ForecastDao>(relaxed = true),
            mockk<HourlyForecastDao>(relaxed = true),
            mockk(relaxed = true),
            mockk<AppLogDao>(relaxed = true),
            mockk<NwsApi>(),
            openMeteoApi,
            mockk(relaxed = true),
            mockk<WeatherApi>(),
            mockk(relaxed = true),
            widgetStateManager,
            climateNormalDao,
            mockk<ObservationDao>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            DailyActualsStore(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
        )
    }

    /** Five complete years of daily temps, every day in a month set to that month's base. */
    private fun syntheticDailyTemps(): List<DailyForecast> {
        val rows = mutableListOf<DailyForecast>()
        var date = LocalDate.of(2019, 1, 1)
        val end = LocalDate.of(2023, 12, 31)
        while (!date.isAfter(end)) {
            rows.add(
                DailyForecast(
                    date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    highTemp = baseHigh(date.monthValue),
                    lowTemp = baseLow(date.monthValue),
                    condition = "",
                ),
            )
            date = date.plusDays(1)
        }
        return rows
    }

    @Test
    fun `fetch averages into 12 monthly rows preserving one decimal`() = runTest {
        every { widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO) } returns true
        coEvery { climateNormalDao.getNormalsForLocation(any()) } returns emptyList()
        val endYear = java.time.LocalDate.now().year - 1
        val startYear = endYear - 19
        coEvery { openMeteoApi.getHistoricalDailyTemps(testLat, testLon, "$startYear-01-01", "$endYear-12-31") } returns
            syntheticDailyTemps()
        val inserted = slot<List<ClimateNormalEntity>>()
        coEvery { climateNormalDao.insertAll(capture(inserted)) } just Runs

        val result = repository.getHistoricalNormalsByMonthDay(testLat, testLon)

        // Exactly 12 cached rows, anchored mid-month.
        assertEquals(12, inserted.captured.size)
        val june = inserted.captured.first { it.monthDay == "06-15" }
        assertEquals(76.5f, june.highTemp, 0.001f)   // fractional mean preserved, not 76 or 77
        assertEquals(54.1f, june.lowTemp, 0.001f)

        // A mid-month day reads that month's mean exactly.
        val midJune = result[MonthDay.of(6, 15)]!!
        assertEquals(76.5f, midJune.first, 0.001f)
        assertEquals(54.1f, midJune.second, 0.001f)

        // A between-months day is interpolated between the two neighboring means
        // (July=75, Aug=80 — a monotonic boundary).
        val augFirst = result[MonthDay.of(8, 1)]!!
        assertTrue(augFirst.first > baseHigh(7))   // above July
        assertTrue(augFirst.first < baseHigh(8))   // below Aug

        // Leap day is covered (expansion iterates a leap year).
        assertNotNull(result[MonthDay.of(2, 29)])
    }

    @Test
    fun `cached rows are expanded without refetching`() = runTest {
        coEvery { climateNormalDao.getNormalsForLocation(any()) } returns
            (1..12).map { ClimateNormalEntity("${it.toString().padStart(2, '0')}-15", "k", baseHigh(it), baseLow(it)) }

        val result = repository.getHistoricalNormalsByMonthDay(testLat, testLon)

        assertEquals(76.5f, result[MonthDay.of(6, 15)]!!.first, 0.001f)
        coVerify(exactly = 0) { openMeteoApi.getHistoricalDailyTemps(any(), any(), any(), any()) }
    }
}
