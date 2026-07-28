package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * Read-side plausibility guard on every [ForecastDao] path (incident 2026-07-28, plan `260728c`).
 *
 * The ingest gate landed ~2h *after* the poisoned row was written, so one `lowTemp = -100.0` row
 * survived in the 1-month retention window and kept rendering on all three devices for another day.
 * The desktop DAO already applied `orNullIfImplausibleTempF()` on read; Android did not.
 *
 * These tests exist specifically to catch a **mis-wired wrapper**: the guard is applied by same-named
 * wrappers over renamed `...Raw` queries, so a query that is accidentally left calling its raw form
 * would silently pass the shared-module unit tests while still leaking sentinels. Only a real Room
 * round-trip can catch that, so each read path is asserted individually.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastDaoPlausibilityTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ForecastDao

    private val lat = 37.422
    private val lon = -122.073
    private val targetDate = LocalDate.of(2026, 7, 28).toEpochDay() * 86_400_000L
    private val predictedOn = LocalDate.of(2026, 7, 26).toEpochDay() * 86_400_000L

    /** The actual row recovered from the device, values and all. */
    private fun poisonedRow() = ForecastEntity(
        targetDate = targetDate,
        dateOfPrediction = predictedOn,
        locationLat = lat,
        locationLon = lon,
        highTemp = 75f,
        lowTemp = -100f,
        condition = "Sunny",
        source = "NWS",
        batchFetchedAt = 1_000L,
        fetchedAt = 1_000L,
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.forecastDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `sentinel is nulled on every read path`() = runTest {
        dao.insertForecast(poisonedRow())

        // Backward-reaching history paths — these are the ones that actually leaked. The Android
        // today-column snapshot reads through getAllForecastsInRange; ForecastHistoryActivity and
        // AccuracyCalculator read through getForecastsInRangeBySource and getForecastEvolution.
        assertNull(
            "getAllForecastsInRange",
            dao.getAllForecastsInRange(targetDate, targetDate, lat, lon).single().lowTemp,
        )
        assertNull(
            "getForecastsInRangeBySource",
            dao.getForecastsInRangeBySource(targetDate, targetDate, lat, lon, "NWS").single().lowTemp,
        )
        assertNull(
            "getForecastEvolution",
            dao.getForecastEvolution(targetDate, lat, lon).single().lowTemp,
        )
        assertNull(
            "getAllForecastsInRangeForSources",
            dao.getAllForecastsInRangeForSources(targetDate, targetDate, lat, lon, listOf("NWS")).single().lowTemp,
        )

        // Latest-row paths.
        assertNull("getLatestWeather", dao.getLatestWeather()!!.lowTemp)
        assertNull("getLatestWeatherBySource", dao.getLatestWeatherBySource("NWS")!!.lowTemp)
        assertNull("getLatestForecastBySource", dao.getLatestForecastBySource("NWS", lat, lon)!!.lowTemp)
        assertNull("getForecastForDate", dao.getForecastForDate(targetDate, lat, lon)!!.lowTemp)
        assertNull(
            "getSpecificForecast",
            dao.getSpecificForecast(targetDate, predictedOn, lat, lon)!!.lowTemp,
        )
        assertNull(
            "getForecastForDateBySource",
            dao.getForecastForDateBySource(targetDate, predictedOn, lat, lon, "NWS")!!.lowTemp,
        )

        // Site-collapsing wrappers.
        assertNull(
            "getForecastsInRange",
            dao.getForecastsInRange(targetDate, targetDate, lat, lon).single().lowTemp,
        )
        assertNull(
            "getForecastsInRangeForSources",
            dao.getForecastsInRangeForSources(targetDate, targetDate, lat, lon, listOf("NWS")).single().lowTemp,
        )
    }

    /**
     * `highTemp IS NOT NULL AND lowTemp IS NOT NULL` in the SQL is NOT a plausibility filter — a
     * `-100` sentinel is perfectly non-null and passes it untouched. So these queries return a row
     * whose low the guard has since nulled, which is exactly the intended outcome: callers treat it
     * as missing rather than as weather.
     */
    @Test
    fun `IS NOT NULL queries still surface the row but with the sentinel neutralised`() = runTest {
        dao.insertForecast(poisonedRow())

        val rows = dao.getLatestForecastsInRangeAllSites(targetDate, targetDate, lat, lon)
        assertEquals("SQL non-null filter does not exclude a sentinel", 1, rows.size)
        assertNull(rows.single().lowTemp)
        assertEquals("the healthy high must be preserved", 75f, rows.single().highTemp)
    }

    @Test
    fun `the good half of a poisoned row is preserved`() = runTest {
        dao.insertForecast(poisonedRow())

        val row = dao.getForecastForDate(targetDate, lat, lon)!!
        assertNull(row.lowTemp)
        assertEquals(75f, row.highTemp)
        assertEquals("Sunny", row.condition)
        assertEquals("NWS", row.source)
        assertEquals(targetDate, row.targetDate)
    }

    @Test
    fun `healthy rows pass through completely untouched`() = runTest {
        val healthy = poisonedRow().copy(lowTemp = 58f, fetchedAt = 2_000L, batchFetchedAt = 2_000L)
        dao.insertForecast(healthy)

        val row = dao.getForecastForDate(targetDate, lat, lon)
        assertNotNull(row)
        assertEquals("a healthy row must be returned as-is", healthy, row)
    }

    /**
     * -40°F is a real temperature that NWS legitimately reports; the guard must not eat it. Pins the
     * boundary so nobody "tightens" the range into discarding genuine cold weather.
     */
    @Test
    fun `legitimately extreme but real temperatures survive`() = runTest {
        dao.insertForecast(poisonedRow().copy(highTemp = -20f, lowTemp = -40f))

        val row = dao.getForecastForDate(targetDate, lat, lon)!!
        assertEquals(-40f, row.lowTemp)
        assertEquals(-20f, row.highTemp)
        assertTrue(
            "precondition: -40F is inside the plausibility bounds",
            com.weatherwidget.data.remote.NwsTemperaturePlausibility.isPlausibleF(-40f),
        )
    }
}
