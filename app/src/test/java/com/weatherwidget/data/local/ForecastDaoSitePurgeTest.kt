package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * [ForecastDao.deleteForecastsAtSite] — the database half of the retired-sentinel migration.
 *
 * Two ways this query can be wrong, and both have already bitten this codebase once:
 *
 *  - **Too narrow.** An exact-coordinate match misses the 3-dp quantized copy (−122.0841 → −122.084)
 *    that the write path produces, exactly as `HourlyObservationBackfill`'s original `==` guard did.
 *  - **Too wide.** Reusing [LocationMatch.ROOM_WHERE]'s ±0.1° box would delete every row within
 *    ~7 miles — a genuinely nearby user's unrelated data, permanently.
 *
 * So the bound is asserted from both sides.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastDaoSitePurgeTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ForecastDao

    // The retired sentinel, duplicated on purpose: this test must fail, not follow along, if the
    // constant in LegacyDefaultLocationMigration changes.
    private val legacyLat = 37.4220
    private val legacyLon = -122.0841

    private val targetDate = LocalDate.of(2026, 8, 12).toEpochDay() * 86_400_000L

    private fun row(lat: Double, lon: Double, source: String = "NWS") = ForecastEntity(
        targetDate = targetDate,
        dateOfPrediction = targetDate,
        locationLat = lat,
        locationLon = lon,
        highTemp = 75f,
        lowTemp = 55f,
        condition = "Sunny",
        source = source,
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
    fun `deletes rows filed at the exact sentinel coordinates`() = runTest {
        dao.insertAll(listOf(row(legacyLat, legacyLon)))

        assertEquals(1, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        assertNull(dao.getLatestWeather())
    }

    /**
     * The quantization trap. Write-side rows land on the 3-dp grid ([LocationMatch.quantize]), so the
     * stored longitude is −122.084, not −122.0841. An exact match finds nothing and the sentinel
     * survives to be resurrected by `resolve()` — the same silent miss `==` produced in the migration.
     */
    @Test
    fun `deletes the 3-dp quantized copy that an exact match would miss`() = runTest {
        val quantizedLat = LocationMatch.quantize(legacyLat)
        val quantizedLon = LocationMatch.quantize(legacyLon)
        dao.insertAll(listOf(row(quantizedLat, quantizedLon)))

        assertEquals(1, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        assertNull(dao.getLatestWeather())
    }

    /**
     * 0.05° away: comfortably inside the ±0.1° read box, comfortably outside sameSite. A user living
     * a few miles from the sentinel keeps their data.
     */
    @Test
    fun `leaves a row inside the read box but outside the same-site box`() = runTest {
        dao.insertAll(listOf(row(legacyLat + 0.05, legacyLon)))

        assertEquals(0, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        assertNotNull(dao.getLatestWeather())
    }

    /** The boundary itself is inclusive, matching [LocationMatch.sameSite]'s `<=`. */
    @Test
    fun `deletes a row exactly on the same-site boundary`() = runTest {
        dao.insertAll(listOf(row(legacyLat + LocationMatch.SAME_SITE_TOLERANCE_DEG, legacyLon)))

        assertEquals(1, dao.deleteForecastsAtSite(legacyLat, legacyLon))
    }

    @Test
    fun `leaves an unrelated location untouched`() = runTest {
        dao.insertAll(listOf(row(40.7128, -74.0060)))

        assertEquals(0, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        val survivor = dao.getLatestWeather()
        assertNotNull(survivor)
        assertEquals(40.7128, survivor!!.locationLat, 0.0001)
    }

    /** Every source's rows go, not just the primary — the resolver's read is source-blind. */
    @Test
    fun `deletes rows from every source at the site`() = runTest {
        dao.insertAll(
            listOf(
                row(legacyLat, legacyLon, source = "NWS"),
                row(legacyLat, legacyLon, source = "OPEN_METEO"),
                row(40.7128, -74.0060, source = "NWS"),
            ),
        )

        assertEquals(2, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        assertNotNull(dao.getLatestWeather())
    }

    @Test
    fun `is idempotent`() = runTest {
        dao.insertAll(listOf(row(legacyLat, legacyLon)))

        assertEquals(1, dao.deleteForecastsAtSite(legacyLat, legacyLon))
        assertEquals(0, dao.deleteForecastsAtSite(legacyLat, legacyLon))
    }
}
