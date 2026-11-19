package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.StoredDataVersion
import com.weatherwidget.test.RobolectricTest
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration test over a real Room database for [DailyActualsStore]'s per-source versioned memory
 * cache and provider-scoped observation reads (performance/261118-android-api-toggle-tail-latency.md).
 */
@Category(LongDuration::class)
class DailyActualsStoreTapCacheTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val lat = 37.4168205
    private val lon = -122.0890350
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setup() {
        DailyActualsStore.clearCache()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DailyActualsStore(
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            appLogDao = db.appLogDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            personalStationWeightProvider = PersonalStationWeightProvider { 1.0 },
        )
    }

    @After
    fun tearDown() {
        DailyActualsStore.clearCache()
        db.close()
    }

    @Test
    fun `observationApisFor scopes to requested providers and includes METAR for NWS`() {
        val nwsApis = DailyActualsStore.observationApisFor(listOf(WeatherSource.NWS))
        assertTrue("NWS should include NWS", WeatherSource.NWS.id in nwsApis)
        assertTrue("NWS should include supplemental METAR", WeatherSource.METAR.id in nwsApis)
        assertTrue("NWS should not include SILURIAN", WeatherSource.SILURIAN.id !in nwsApis)

        val wapiApis = DailyActualsStore.observationApisFor(listOf(WeatherSource.WEATHER_API))
        assertEquals(listOf(WeatherSource.WEATHER_API.id), wapiApis)
    }

    @Test
    fun `getDailyActualsWithLiveToday caches per source and invalidates on StoredDataVersion bump`() = runTest {
        val morningMs = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val noonMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = morningMs,
                    temperature = 52f,
                    condition = "Clear",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 3.0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = morningMs,
                    api = WeatherSource.NWS.id,
                ),
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = noonMs,
                    temperature = 64f,
                    condition = "Clear",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 3.0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = noonMs,
                    api = WeatherSource.NWS.id,
                ),
            ),
        )

        // Initially cold in RAM.
        assertNull(DailyActualsStore.getCachedLive(db.observationDao(), lat, lon, listOf(WeatherSource.NWS.id), 1.0))

        // First live read populates the per-source cache for NWS.
        val first = store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(WeatherSource.NWS.id))
        val firstToday = first[WeatherSource.NWS.id]?.get(today)
        assertNotNull("expected NWS today actuals", firstToday)
        assertEquals(64f, firstToday!!.computedHighTemp!!, 0.1f)

        // RAM cache now serves NWS without hitting Room.
        val cached = DailyActualsStore.getCachedLive(db.observationDao(), lat, lon, listOf(WeatherSource.NWS.id), 1.0)
        assertNotNull("expected cached NWS actuals", cached)
        assertEquals(firstToday.computedHighTemp!!, cached!![WeatherSource.NWS.id]!![today]!!.computedHighTemp!!, 0.01f)

        // A warmer observation lands + StoredDataVersion bumps -> cache invalidates automatically.
        val afternoonMs = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        db.observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = afternoonMs,
                    temperature = 71f,
                    condition = "Clear",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 3.0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = afternoonMs,
                    api = WeatherSource.NWS.id,
                ),
            ),
        )
        StoredDataVersion.process.bump("test_new_obs")

        assertNull(DailyActualsStore.getCachedLive(db.observationDao(), lat, lon, listOf(WeatherSource.NWS.id), 1.0))
        val refreshed = store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(WeatherSource.NWS.id))
        assertEquals(71f, refreshed[WeatherSource.NWS.id]!![today]!!.computedHighTemp!!, 0.1f)
    }
}
