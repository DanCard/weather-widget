package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `updateBlendIfUnchanged` is the optimistic write the blend recompute uses instead of a full-row
 * `REPLACE` (plans/260812-code-review-actuals-observation-blending.md, H1). The full-row replace
 * clobbered a concurrent station pull's provenance: the recompute's stale snapshot had
 * `actualsSource = null`, and REPLACE wrote that null back over the pull's fresh value.
 *
 * Two guarantees are pinned here:
 * 1. the UPDATE sets ONLY the recompute-owned columns, so provenance columns
 *    (`actualsSource`, `apiHighTemp`, `apiLowTemp`, `apiStationId`) are untouched;
 * 2. it is conditional on `updatedAt`, so a stale write affects 0 rows and the caller skips.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class DailyHistoryDaoBlendUpdateTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: DailyHistoryDao

    private val date = 1_750_000_000_000L
    private val lat = 37.417
    private val lon = -122.089

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dailyHistoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun row(
        updatedAt: Long = 1_000L,
        actualsSource: String? = "nws_station_pull",
        apiHigh: Float? = 82f,
        apiLow: Float? = 61f,
        computedHigh: Float = 70f,
        computedLow: Float = 55f,
    ) = DailyHistoryEntity(
        date = date,
        source = "NWS",
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = computedHigh,
        computedLowTemp = computedLow,
        condition = "Partly Cloudy",
        updatedAt = updatedAt,
        apiHighTemp = apiHigh,
        apiLowTemp = apiLow,
        apiStationId = "KNUQ",
        apiStationDistanceKm = 4.2f,
        actualsSource = actualsSource,
        lastWriter = "nws_station_pull",
    )

    @Test
    fun `recompute update sets owned fields and preserves provenance columns`() = runTest {
        dao.insertAll(listOf(row()))

        val updated = dao.updateBlendIfUnchanged(
            date = date,
            source = "NWS",
            locationLat = lat,
            locationLon = lon,
            computedHighTemp = 72f,
            computedLowTemp = 54f,
            condition = "Mostly Sunny",
            precipAmountMm = 1.5f,
            precipDayMm = 1.0f,
            precipNightMm = 0.5f,
            lastWriter = "blend_recompute",
            updatedAt = 2_000L,
            expectedUpdatedAt = 1_000L,
        )
        assertEquals(1, updated)

        val stored = dao.getExtremesInRange(date, date, lat, lon).single()
        assertEquals(72f, stored.computedHighTemp)
        assertEquals(54f, stored.computedLowTemp)
        assertEquals("Mostly Sunny", stored.condition)
        assertEquals(1.5f, stored.precipAmountMm)
        assertEquals(1.0f, stored.precipDayMm)
        assertEquals(0.5f, stored.precipNightMm)
        assertEquals("blend_recompute", stored.lastWriter)
        assertEquals(2_000L, stored.updatedAt)

        // Provenance columns the recompute must never touch.
        assertEquals("nws_station_pull", stored.actualsSource)
        assertEquals(82f, stored.apiHighTemp)
        assertEquals(61f, stored.apiLowTemp)
        assertEquals("KNUQ", stored.apiStationId)
        assertEquals(4.2f, stored.apiStationDistanceKm)
    }

    @Test
    fun `stale write affects zero rows and leaves the row unchanged`() = runTest {
        dao.insertAll(listOf(row(updatedAt = 1_000L)))

        // A concurrent writer bumped updatedAt to 3_000 before this recompute's write.
        dao.insertAll(listOf(row(updatedAt = 3_000L, actualsSource = "nws_station_pull", computedHigh = 80f)))

        val updated = dao.updateBlendIfUnchanged(
            date = date,
            source = "NWS",
            locationLat = lat,
            locationLon = lon,
            computedHighTemp = 72f,
            computedLowTemp = 54f,
            condition = "Mostly Sunny",
            precipAmountMm = null,
            precipDayMm = null,
            precipNightMm = null,
            lastWriter = "blend_recompute",
            updatedAt = 4_000L,
            expectedUpdatedAt = 1_000L, // stale: we read 1_000, the row is now at 3_000
        )
        assertEquals(0, updated)

        val stored = dao.getExtremesInRange(date, date, lat, lon).single()
        assertEquals(80f, stored.computedHighTemp) // untouched by the stale recompute
        assertEquals("nws_station_pull", stored.actualsSource)
        assertEquals(3_000L, stored.updatedAt)
    }
}
