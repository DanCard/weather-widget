package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frequent METAR loop asks for the gap since the newest stored METAR row at this site
 * ([com.weatherwidget.shared.util.MetarFetchWindow]), not a fixed 2 h — a sleep or offline gap, or a
 * site with nothing stored, stayed a hole until the next full sync. The full sync still asks for the
 * whole day. Real refresher + Room; only the network source is faked.
 * plans/261121-metar-gap-fetch-window.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class MetarRefresherGapWindowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lat = 37.417
    private val lon = -122.089
    private val hourMs = 3_600_000L

    @Before
    fun setUp() {
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        ActualsProviderResolver.installPreferenceSource { if (it == WeatherSource.GOOGLE_WEATHER) WeatherSource.METAR else null }
    }

    @After
    fun tearDown() {
        ActualsProviderResolver.resetPreferenceSource()
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    private suspend fun storeMetar(ageMs: Long) {
        WeatherDatabase.getDatabase(context).observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KNUQ", stationName = "Moffett", timestamp = System.currentTimeMillis() - ageMs,
                    temperature = 40f, condition = "Clear", locationLat = lat, locationLon = lon,
                    stationType = StationType.OFFICIAL, api = WeatherSource.METAR.id,
                ),
            ),
        )
    }

    @Test
    fun `frequent loop sizes the METAR window from the newest stored row`() = runBlocking {
        val requested = mutableListOf<Int>()
        val source = mockk<MetarObservationSource>()
        coEvery { source.fetchObservations(any(), any(), any(), any()) } answers {
            requested += thirdArg<Int>()
            emptyList()
        }
        val widgetState = mockk<WidgetStateManager>(relaxed = true)
        every { widgetState.getVisibleSourcesOrder() } returns listOf(WeatherSource.GOOGLE_WEATHER)
        val refresher = MetarObservationRefresher(
            context, source, widgetState, WeatherDatabase.getDatabase(context).appLogDao(),
        )
        val tiers = MetarFetchPolicy.Tier.entries.toSet()

        refresher.refreshIfDue(tiers, lat, lon, reason = "current_temp")
        println("no METAR rows -> hours=$requested")
        assertEquals("nothing stored: the whole day", listOf(24), requested)

        requested.clear()
        storeMetar(ageMs = 5 * hourMs)
        refresher.refreshIfDue(tiers, lat, lon, reason = "current_temp")
        println("newest METAR 5 h old -> hours=$requested")
        assertEquals("5 h gap + 30 min margin, rounded up", listOf(6), requested)

        requested.clear()
        storeMetar(ageMs = 10 * 60_000L)
        refresher.refreshIfDue(tiers, lat, lon, reason = "current_temp")
        println("newest METAR 10 min old -> hours=$requested")
        assertEquals("steady state keeps the 2 h pull", listOf(2), requested)

        requested.clear()
        refresher.refreshIfDue(tiers, lat, lon, reason = "full_sync", hours = MetarObservationRefresher.DEEP_HOURS)
        assertEquals("full sync keeps the deep pull", listOf(24), requested)
    }
}
