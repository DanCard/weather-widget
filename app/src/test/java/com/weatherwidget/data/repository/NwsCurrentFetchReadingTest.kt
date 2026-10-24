package com.weatherwidget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The reading an NWS cycle reports is `:shared` NwsBlend.fetchReading, as on desktop
 * (plans/261024-nws-fetch-time-current-reading-shared.md).
 *
 * Integration: [NwsCurrentObservationUpdater] + [NwsObservationSource] + Room, HTTP ([NwsApi]) faked.
 */
@Category(LongDuration::class)
class NwsCurrentFetchReadingTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val lat = 37.4168
    private val lon = -122.0891
    private val minute = 60_000L

    private val stations = listOf(
        NwsApi.StationInfo("AW020", "AW020", 37.418, -122.090, StationType.PERSONAL),
        NwsApi.StationInfo("KNUQ", "Moffett", 37.41, -122.05, StationType.OFFICIAL),
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private data class Latest(val atMs: Long, val celsius: Float, val text: String, val qcFailed: Boolean = false)

    private fun updater(latest: (String) -> Latest?): NwsCurrentObservationUpdater {
        val api = mockk<NwsApi>()
        coEvery { api.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR",
            gridX = 1,
            gridY = 1,
            forecastUrl = "https://example.invalid/forecast",
            observationStationsUrl = "https://example.invalid/stations",
        )
        coEvery { api.getObservationStations(any()) } returns stations
        coEvery { api.getLatestObservationDetailedResult(any(), any()) } answers {
            val reading = latest(firstArg())
            if (reading == null) {
                FetchOutcome.NoData
            } else {
                FetchOutcome.Success(
                    NwsApi.Observation(
                        timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(reading.atMs)),
                        temperatureCelsius = reading.celsius,
                        textDescription = reading.text,
                        qcFailed = reading.qcFailed,
                    ),
                )
            }
        }
        val source = NwsObservationSource(context, api, db.appLogDao(), metarTextApi = null)
        val store = DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 },
            InMemoryReducedSignatureStore(),
        )
        return NwsCurrentObservationUpdater(source, db.observationDao(), db.appLogDao(), store)
    }

    @Test
    fun `a QC-failed nearest station gives the reading neither its condition nor its time`() = runBlocking {
        // Android took the condition from the nearest station and the time from the newest latest
        // reading, a QC-failed one included (the temperature already skipped it).
        val now = (System.currentTimeMillis() / 1000) * 1000
        val knuqAt = now - 20 * minute
        val payload = updater { id ->
            when (id) {
                "AW020" -> Latest(now - 2 * minute, 37f, "Hail", qcFailed = true)
                "KNUQ" -> Latest(knuqAt, 15f, "Clear")
                else -> null
            }
        }.fetchNwsCurrent(lat, lon)

        // The QC-failed reading did arrive (stored for the stations UI), so the old rule would have used it.
        val stored = db.observationDao().getObservationsInRange(now - 60 * minute, now, lat, lon, apis = null)
        assertEquals(true, stored.single { it.stationId == "AW020" }.qcFailed)
        assertNotNull(payload)
        assertEquals(59f, payload!!.temperature, 0.1f)
        assertEquals("Clear", payload.condition)
        assertEquals(knuqAt, payload.observedAt)
    }

    @Test
    fun `nothing to blend is no reading`() = runBlocking {
        val now = System.currentTimeMillis()
        val payload = updater { id ->
            if (id == "KNUQ") Latest(now - 200 * minute, 15f, "Clear") else null
        }.fetchNwsCurrent(lat, lon)

        assertNull(payload)
    }
}
