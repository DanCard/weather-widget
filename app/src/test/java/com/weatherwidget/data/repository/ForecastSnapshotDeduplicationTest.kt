package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tests forecast snapshot deduplication: identical forecasts are skipped,
 * changed forecasts create new snapshots.
 *
 * Uses saveForecastSnapshot() directly (now `internal`).
 */
@RunWith(RobolectricTestRunner::class)
class ForecastSnapshotDeduplicationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        repository = WeatherRepository(
            RuntimeEnvironment.getApplication(),
            db.weatherDao(),
            db.forecastSnapshotDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            mockk(),
            mockk(),
            mockk(),
            mockk<WidgetStateManager>(relaxed = true),
            TemperatureInterpolator(),
            db.climateNormalDao(),
            db.weatherObservationDao(),
            mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first forecast creates a snapshot`() = runTest {
        val weather = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f),
        )

        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")

        val count = db.forecastSnapshotDao().getCount()
        assertEquals(1, count)
    }

    @Test
    fun `identical forecast skips snapshot`() = runTest {
        val weather = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f, condition = "Sunny"),
        )

        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")
        assertEquals(1, db.forecastSnapshotDao().getCount())

        // Same forecast again — should be deduplicated
        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")
        assertEquals(1, db.forecastSnapshotDao().getCount())
    }

    @Test
    fun `changed high temp creates new snapshot`() = runTest {
        val weather1 = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f),
        )
        repository.saveForecastSnapshot(weather1, LAT, LON, "NWS")

        val weather2 = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 72f, lowTemp = 50f),
        )
        repository.saveForecastSnapshot(weather2, LAT, LON, "NWS")

        assertEquals(2, db.forecastSnapshotDao().getCount())
    }

    @Test
    fun `changed condition creates new snapshot`() = runTest {
        val weather1 = listOf(
            TestData.weather(date = tomorrow, source = "NWS", condition = "Sunny"),
        )
        repository.saveForecastSnapshot(weather1, LAT, LON, "NWS")

        val weather2 = listOf(
            TestData.weather(date = tomorrow, source = "NWS", condition = "Partly Cloudy"),
        )
        repository.saveForecastSnapshot(weather2, LAT, LON, "NWS")

        assertEquals(2, db.forecastSnapshotDao().getCount())
    }

    @Test
    fun `historical dates are excluded from snapshots`() = runTest {
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weather = listOf(
            TestData.weather(date = yesterday, source = "NWS", highTemp = 65f, lowTemp = 45f),
        )

        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")

        assertEquals(0, db.forecastSnapshotDao().getCount())
    }

    @Test
    fun `climate normals are excluded from snapshots`() = runTest {
        val weather = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 65f, lowTemp = 45f, isClimateNormal = true),
        )

        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")

        assertEquals(0, db.forecastSnapshotDao().getCount())
    }

    @Test
    fun `both temps null skips snapshot`() = runTest {
        val weather = listOf(
            TestData.weather(date = tomorrow, source = "NWS", highTemp = null, lowTemp = null),
        )

        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")

        assertEquals(0, db.forecastSnapshotDao().getCount())
    }
}
