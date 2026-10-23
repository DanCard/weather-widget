package com.weatherwidget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.test.RobolectricTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Android's NWS current-temp cycle stores every reading of the last 90 minutes, not just the latest
 * — the same pass as desktop (`:shared` NwsStationBundles; user, 2026-10-23,
 * plans/261023-nws-observation-cycle-latest-plus-recent-window-both-platforms.md). Before, a
 * station publishing every 20 min lost the readings between two cycles until a repair job ran.
 */
@Category(LongDuration::class)
class NwsCurrentRecentWindowIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val lat = 37.4168
    private val lon = -122.0891
    private val now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
    private fun iso(minutesAgo: Long) = DateTimeFormatter.ISO_INSTANT.format(now.minusMinutes(minutesAgo).toInstant())
    private fun ms(minutesAgo: Long) = now.minusMinutes(minutesAgo).toInstant().toEpochMilli()
    private fun obs(minutesAgo: Long, c: Float) = NwsApi.Observation(timestamp = iso(minutesAgo), temperatureCelsius = c, textDescription = "Clear")

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `a cycle stores the window's readings, and a slow station still gives its latest`() = runBlocking {
        val api = mockk<NwsApi>()
        coEvery { api.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR", gridX = 1, gridY = 1,
            forecastUrl = "https://example.invalid/forecast",
            observationStationsUrl = "https://example.invalid/stations",
        )
        coEvery { api.getObservationStations(any()) } returns listOf(
            NwsApi.StationInfo("KNUQ", "Moffett", 37.41, -122.05, StationType.OFFICIAL),
            NwsApi.StationInfo("KPAO", "Palo Alto", 37.46, -122.12, StationType.OFFICIAL),
        )
        coEvery { api.getLatestObservationDetailedResult("KNUQ", any()) } returns FetchOutcome.Success(obs(5, 22f))
        coEvery { api.getLatestObservationDetailedResult("KPAO", any()) } returns FetchOutcome.Success(obs(80, 20f))
        // KNUQ published three times in the window; KPAO (slow) not at all.
        coEvery { api.getObservations("KNUQ", any(), any()) } returns listOf(obs(5, 22f), obs(25, 21.5f), obs(45, 21f))
        coEvery { api.getObservations("KPAO", any(), any()) } returns emptyList()

        val dailyStore = DailyActualsStore(
            db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 }, InMemoryReducedSignatureStore(),
        )
        val updater = NwsCurrentObservationUpdater(
            NwsObservationSource(context, api, db.appLogDao(), metarTextApi = null),
            db.observationDao(), db.appLogDao(), dailyStore,
        )

        val payload = updater.fetchNwsCurrent(lat, lon)

        val rows = db.observationDao().getRecentObservations(0L).filter { it.api == WeatherSource.NWS.id }
        assertEquals(
            "every KNUQ reading of the window is stored (the latest-only cycle kept one)",
            listOf(ms(45), ms(25), ms(5)),
            rows.filter { it.stationId == "KNUQ" }.map { it.timestamp }.sorted(),
        )
        assertEquals("the slow station's latest still counts", listOf(ms(80)), rows.filter { it.stationId == "KPAO" }.map { it.timestamp })
        assertNotNull("the header blend still comes from the latest readings", payload)
        assertEquals(ms(5), payload!!.observedAt)
        Unit
    }
}
