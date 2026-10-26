package com.weatherwidget.data.repository

import com.weatherwidget.shared.actuals.InMemoryReducedSignatureStore
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Android's NWS cycle writes through the shared ObservationWriteRun
 * (`plans/261024-shared-observation-persist-rules.md`). Both cases failed before: Android thinned
 * personal stations on the Synoptic path only (O1) and re-tagged on Synoptic and the Observations
 * screen only (O2).
 */
@Category(LongDuration::class)
class NwsObservationWriteRulesIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val lat = 37.4168
    private val lon = -122.0891
    // On a 10-minute boundary, so the expected buckets are fixed.
    private val now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
    private fun iso(minutesAgo: Long) = DateTimeFormatter.ISO_INSTANT.format(now.minusMinutes(minutesAgo).toInstant())
    private fun ms(minutesAgo: Long) = now.minusMinutes(minutesAgo).toInstant().toEpochMilli()
    private fun obs(minutesAgo: Long) = NwsApi.Observation(timestamp = iso(minutesAgo), temperatureCelsius = 20f, textDescription = "Clear")

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    private fun updater(stations: List<NwsApi.StationInfo>, recent: Map<String, List<Long>>): NwsCurrentObservationUpdater {
        val api = mockk<NwsApi>()
        coEvery { api.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR", gridX = 1, gridY = 1,
            forecastUrl = "https://example.invalid/forecast",
            observationStationsUrl = "https://example.invalid/stations",
        )
        coEvery { api.getObservationStations(any()) } returns stations
        recent.forEach { (station, minutesAgo) ->
            coEvery { api.getLatestObservationDetailedResult(station, any()) } returns FetchOutcome.Success(obs(minutesAgo.min()))
            coEvery { api.getObservations(station, any(), any()) } returns minutesAgo.map { obs(it) }
        }
        val dailyStore = DailyActualsStore(
            db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 }, InMemoryReducedSignatureStore(),
        )
        return NwsCurrentObservationUpdater(
            NwsObservationSource(context, api, db.appLogDao(), metarTextApi = null),
            db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), dailyStore,
        )
    }

    private suspend fun stored(station: String) =
        db.observationDao().getRecentObservations(0L).filter { it.api == WeatherSource.NWS.id && it.stationId == station }.sortedBy { it.timestamp }

    @Test
    fun `a personal NWS station reporting every 5 minutes is stored at one row per 10 minutes plus its newest`() = runBlocking {
        updater(
            listOf(NwsApi.StationInfo("F4751", "Backyard", 37.42, -122.09, StationType.PERSONAL)),
            mapOf("F4751" to listOf(55L, 50L, 45L, 40L, 35L, 30L)),
        ).fetchNwsCurrent(lat, lon)

        // :05 alone in its bucket; :10 and :20 keep theirs; :30 is the newest.
        assertEquals(listOf(ms(55), ms(50), ms(40), ms(30)), stored("F4751").map { it.timestamp })
    }

    @Test
    fun `an NWS station's reclassification reaches its stored rows`() = runBlocking {
        db.observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KXYZ", stationName = "Ridge", timestamp = ms(180), temperature = 60f, condition = "Clear",
                    locationLat = 37.417, locationLon = -122.089, stationType = StationType.OFFICIAL, fetchedAt = ms(179),
                    api = WeatherSource.NWS.id,
                ),
            ),
        )

        updater(
            listOf(NwsApi.StationInfo("KXYZ", "Ridge", 37.42, -122.09, StationType.RAWS)),
            mapOf("KXYZ" to listOf(20L)),
        ).fetchNwsCurrent(lat, lon)

        assertEquals(listOf(StationType.RAWS, StationType.RAWS), stored("KXYZ").map { it.stationType })
    }
}
