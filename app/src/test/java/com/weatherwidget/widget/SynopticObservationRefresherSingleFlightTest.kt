package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.repository.SynopticObservationSource
import com.weatherwidget.shared.util.SynopticBackoff
import com.weatherwidget.shared.util.SynopticFetchPolicy
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 2026-10-03 21:58 incident end to end: two `WeatherWidgetWorker` runs (`periodic_60m`,
 * `on_update_stale`) overlap, each builds its own [SynopticObservationRefresher], and the Synoptic
 * request stalls 30 s. Before the fix each refresher had its own gate state, so two requests went
 * out and the backoff escalated twice (streak 2 → 60 min dark).
 *
 * Real refresher + real gate + real prefs store + real Room DB; only the network source is faked.
 * See performance/261003-synoptic-fetch-review-fixes.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class SynopticObservationRefresherSingleFlightTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var appLogDao: AppLogDao
    private val allTiers = SynopticFetchPolicy.Tier.entries.toSet()

    @Before
    fun setUp() {
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        context.getSharedPreferences("synoptic_fetch_backoff", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    /** A new refresher per call, exactly as each worker run builds one. */
    private fun newRefresher(source: SynopticObservationSource) =
        SynopticObservationRefresher(context, source, mockk(relaxed = true), appLogDao)

    private suspend fun logs(tag: String) = appLogDao.getLogsByTag(tag, 50)

    @Test
    fun `two overlapping worker runs send one request and take one transport backoff step`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = mockk<SynopticObservationSource>()
        coEvery { source.fetchObservationsResult(any(), any(), any(), any(), any(), any()) } coAnswers {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            FetchOutcome.Failed("HttpRequestTimeoutException: Request timeout has expired [request_timeout=30000 ms]")
        }
        val lat = 37.391
        val lon = -122.081

        val periodic = async { newRefresher(source).refreshIfDue(allTiers, lat, lon, "periodic_60m") }
        withTimeout(5_000) { started.await() }
        val onUpdate = async { newRefresher(source).refreshIfDue(allTiers, lat, lon, "on_update_stale") }
        // Release only once the second run has reached the gate (or has given up waiting for it).
        withTimeout(5_000) { while (logs("SYNOPTIC_FETCH_JOINED").isEmpty() && calls.get() < 2) delay(10) }
        release.complete(Unit)
        periodic.await()
        onUpdate.await()

        assertEquals("one stall must be one request", 1, calls.get())
        val backoffs = logs("SYNOPTIC_FETCH_BACKOFF_SET")
        assertEquals(1, backoffs.size)
        assertTrue(backoffs.single().message, backoffs.single().message.contains("class=transport backoffMin=5"))
        val prefs = context.getSharedPreferences("synoptic_fetch_backoff", Context.MODE_PRIVATE)
        assertEquals(0, prefs.getInt("fail_streak", -1))
        val until = prefs.getLong("backoff_until_ms", 0L)
        assertTrue(until - System.currentTimeMillis() <= SynopticBackoff.TRANSPORT_BACKOFF_MS)
    }

    @Test
    fun `a routine refresh requests only the gap since the newest stored row`() = runBlocking {
        val lat = 37.512
        val lon = -122.203
        val requested = mutableListOf<Long?>()
        val source = mockk<SynopticObservationSource>()
        coEvery { source.fetchObservationsResult(any(), any(), any(), any(), any(), captureNullable(requested)) } returns
            FetchOutcome.NoData

        // Nothing stored at this site: the deep window.
        newRefresher(source).refreshIfDue(allTiers, lat, lon, "full_sync")
        assertEquals(1440L, requested.last())

        // A reading 3 h ago: gap + 30 min margin.
        WeatherDatabase.getDatabase(context).observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = System.currentTimeMillis() - 3 * 3_600_000L,
                    temperature = 60f,
                    condition = "",
                    locationLat = lat,
                    locationLon = lon,
                    api = WeatherSource.SYNOPTIC.id,
                ),
            ),
        )
        // The previous NoData fetch set the 2-minute freshness floor; a location-change run is exempt.
        newRefresher(source).refreshIfDue(allTiers, lat, lon, "full_sync", userLocationChange = true)
        assertEquals(210L, requested.last())
    }

    @Test
    fun `app_logs never stores a credential`() = runBlocking {
        appLogDao.log(
            "SYNOPTIC_FETCH_FAIL",
            "error=HttpRequestTimeoutException: https://api.synopticdata.com/v2/stations/timeseries?radius=1,2,25&token=livetoken123&recent=120",
            "WARN",
        )
        val stored = logs("SYNOPTIC_FETCH_FAIL").single().message
        assertFalse(stored, stored.contains("livetoken123"))
        assertTrue(stored, stored.contains("token=<redacted>"))
    }
}
