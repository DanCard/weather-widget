package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Same-day high/low cutoffs (plans/261004-same-day-high-low-cutoffs.md): low is frozen at 06:00
 * local, high at 16:00. Post-cutoff batches must not overwrite the last real prediction.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastSnapshotHindcastCutoffTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        val context = RuntimeEnvironment.getApplication()
        val forecastRepo = ForecastRepository(
            context,
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.appLogDao(),
            mockk(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            db.climateNormalDao(),
            db.observationDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val currentRepo = CurrentTempRepository(
            context,
            db.observationDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            mockk(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        repository = WeatherRepository(context, forecastRepo, currentRepo, db.forecastDao(), db.appLogDao(), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
        db.close()
    }

    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime.of(today, java.time.LocalTime.of(h, m))
            .atZone(zone).toInstant().toEpochMilli()

    private suspend fun latest() = db.forecastDao().getLatestForecastBySource("NWS", LAT, LON)!!

    @Test
    fun `before 6 am both low and high are stored`() = runTest {
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 80f, lowTemp = 55f)),
            LAT, LON, "NWS",
            nowMs = at(5, 30),
        )
        val row = latest()
        assertEquals(80f, row.highTemp)
        assertEquals(55f, row.lowTemp)
    }

    @Test
    fun `after 6 am the low is frozen to the prior forecast and the high still updates`() = runTest {
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 80f, lowTemp = 55f)),
            LAT, LON, "NWS",
            nowMs = at(5, 30),
        )
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 82f, lowTemp = 50f)),
            LAT, LON, "NWS",
            nowMs = at(7, 0),
        )
        val row = latest()
        assertEquals(82f, row.highTemp)
        assertEquals("low stays the pre-6 am prediction", 55f, row.lowTemp)
    }

    @Test
    fun `after 4 pm the high is frozen to the prior forecast`() = runTest {
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 80f, lowTemp = 55f)),
            LAT, LON, "NWS",
            nowMs = at(5, 30),
        )
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 82f, lowTemp = 50f)),
            LAT, LON, "NWS",
            nowMs = at(15, 0),
        )
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 90f, lowTemp = 52f)),
            LAT, LON, "NWS",
            nowMs = at(16, 30),
        )
        val row = latest()
        assertEquals("high stays the pre-4 pm prediction", 82f, row.highTemp)
        assertEquals("low stays the pre-6 am prediction", 55f, row.lowTemp)
    }

    @Test
    fun `first write after both cutoffs stores neither high nor low`() = runTest {
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = todayStr, highTemp = 90f, lowTemp = 52f)),
            LAT, LON, "NWS",
            nowMs = at(17, 0),
        )
        assertNull("no junk row", db.forecastDao().getLatestForecastBySource("NWS", LAT, LON))
    }

    @Test
    fun `tomorrow is never gated`() = runTest {
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = tomorrowStr, highTemp = 80f, lowTemp = 55f)),
            LAT, LON, "NWS",
            nowMs = at(17, 0),
        )
        val row = latest()
        assertEquals(80f, row.highTemp)
        assertEquals(55f, row.lowTemp)
    }
}
