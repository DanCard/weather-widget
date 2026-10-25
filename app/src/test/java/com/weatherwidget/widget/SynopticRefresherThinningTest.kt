package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
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
 * Incremental Synoptic refreshes of a 5-minute personal station store one row per 10 minutes plus
 * the newest — over the stored rows, through ObservationWriteRun. Thinning each batch alone kept
 * every fetch's newest row beside its bucket's keeper (Pixel 7.2 rows/h against 6/h, 2026-10-25;
 * `plans/261024-shared-observation-persist-rules.md`, O1). Real refresher + run + Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class SynopticRefresherThinningTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lat = 37.417
    private val lon = -122.089
    private val min = 60_000L
    // On a 10-minute boundary, an hour back, so the expected buckets are fixed.
    private val base = (System.currentTimeMillis() / (10 * min)) * (10 * min) - 60 * min

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

    private fun row(offsetMin: Long) = ObservationEntity(
        stationId = "F4751", stationName = "F4751", timestamp = base + offsetMin * min, temperature = 60f, condition = "",
        locationLat = lat, locationLon = lon, stationType = StationType.PERSONAL, api = WeatherSource.SYNOPTIC.id,
    )

    @Test
    fun `two incremental refreshes leave no extra row in a 10-minute bucket`() = runBlocking {
        val source = mockk<SynopticObservationSource>()
        coEvery { source.fetchObservationsResult(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            FetchOutcome.Success(listOf(row(0), row(5))),
            FetchOutcome.Success(listOf(row(10), row(15))),
        )
        val refresher = SynopticObservationRefresher(context, source, mockk(relaxed = true), WeatherDatabase.getDatabase(context).appLogDao())

        // userLocationChange: past the gate's freshness floor, so the second refresh runs.
        repeat(2) { refresher.refreshIfDue(SynopticFetchPolicy.Tier.entries.toSet(), lat, lon, "full_sync", userLocationChange = true) }

        val stored = WeatherDatabase.getDatabase(context).observationDao()
            .getObservationsInRange(0L, Long.MAX_VALUE, lat, lon, listOf(WeatherSource.SYNOPTIC.id))
            .map { (it.timestamp - base) / min }
            .sorted()
        assertEquals("the first fetch's newest (:05) shares :00's bucket once :15 arrives", listOf(0L, 10L, 15L), stored)
    }
}
