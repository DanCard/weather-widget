package com.weatherwidget.widget

import com.weatherwidget.data.model.StationType
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.repository.SynopticObservationSource
import com.weatherwidget.shared.util.SynopticFetchPolicy
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.coEvery
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
 * A Synoptic store brings earlier rows of the fetched stations onto the type just fetched, so the
 * RAWS reclassification reaches the ~10 days of rows already stored as OFFICIAL (the blend reads
 * the stored type; the gap window never re-fetches them). Real refresher + thinning + Room.
 * See plans/261003-raws-station-type.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class SynopticStationTypeRetagTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lat = 37.417
    private val lon = -122.089
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        context.getSharedPreferences("synoptic_fetch_backoff", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    private fun row(station: String, type: StationType, api: String, ageMin: Long) = ObservationEntity(
        stationId = station,
        stationName = station,
        timestamp = now - ageMin * 60_000L,
        temperature = 80f,
        condition = "",
        locationLat = lat,
        locationLon = lon,
        stationType = type,
        api = api,
    )

    @Test
    fun `a Synoptic store retags the station's older rows and nothing else`() = runBlocking {
        val dao = WeatherDatabase.getDatabase(context).observationDao()
        dao.insertAll(
            listOf(
                row("LOAC1", StationType.OFFICIAL, WeatherSource.SYNOPTIC.id, ageMin = 600),
                row("KNUQ", StationType.OFFICIAL, WeatherSource.SYNOPTIC.id, ageMin = 600),
                row("LOAC1", StationType.PERSONAL, WeatherSource.NWS.id, ageMin = 600),
            ),
        )
        val source = mockk<SynopticObservationSource>()
        coEvery { source.fetchObservationsResult(any(), any(), any(), any(), any(), any()) } returns
            FetchOutcome.Success(
                listOf(
                    row("LOAC1", StationType.RAWS, WeatherSource.SYNOPTIC.id, ageMin = 5),
                    row("KNUQ", StationType.OFFICIAL, WeatherSource.SYNOPTIC.id, ageMin = 5),
                ),
            )

        SynopticObservationRefresher(context, source, mockk(relaxed = true), WeatherDatabase.getDatabase(context).appLogDao())
            .refreshIfDue(SynopticFetchPolicy.Tier.entries.toSet(), lat, lon, "full_sync")

        val stored = dao.getObservationsInRange(0L, Long.MAX_VALUE, lat, lon,
            listOf(WeatherSource.SYNOPTIC.id, WeatherSource.NWS.id))
            .associate { "${it.api}|${it.stationId}|${if (it.timestamp < now - 60 * 60_000L) "old" else "new"}" to it.stationType }
        assertEquals(
            mapOf(
                "SYNOPTIC|LOAC1|old" to StationType.RAWS,
                "SYNOPTIC|LOAC1|new" to StationType.RAWS,
                "SYNOPTIC|KNUQ|old" to StationType.OFFICIAL,
                "SYNOPTIC|KNUQ|new" to StationType.OFFICIAL,
                "NWS|LOAC1|old" to StationType.PERSONAL,
            ),
            stored,
        )
    }
}
