package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.ViewingRefreshPolicy
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the while-viewing watchdog (user's rule, 2026-10-08): rendering the CLOUD view refreshes the
 * viewed source's current temp/actuals when older than 15 min, and its forecast only when due by the
 * normal cadence — then as a targeted, hourly-limited forced refresh. The forced-refresh enqueue is
 * suppressed via [RefreshScheduler.setIsRefreshDisabledForTesting] and observed through
 * `lastForcedRefreshForTesting`; the decision is the function's return value.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewingRefreshRoboTest {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val widgetId = 9001
    private val repository = mockk<WeatherRepository>(relaxed = true)
    private val source = WeatherSource.OPEN_METEO

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
        RefreshScheduler.lastForcedRefreshForTesting = null
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        RefreshScheduler.lastForcedRefreshForTesting = null
    }

    private fun cloudRow(fetchedAt: Long) = HourlyForecastEntity(
        dateTime = 1_800_000_000_000L,
        locationLat = 37.4220,
        locationLon = -122.0841,
        temperature = 60f,
        condition = "Cloudy",
        source = source.id,
        cloudCover = 100,
        fetchedAt = fetchedAt,
        cloudCoverLow = 100,
    )

    private val cadence = 4 * 3_600_000L
    private val now = System.currentTimeMillis()
    private val staleActuals = now - ViewingRefreshPolicy.ACTUALS_STALE_WHILE_VIEWING_MS - 60_000L

    private suspend fun run(
        forecastAt: Long,
        actualsAt: Long = staleActuals,
        repo: WeatherRepository? = repository,
        rows: List<HourlyForecastEntity> = listOf(cloudRow(forecastAt)),
    ) = CloudCoverViewHandler.maybeRefreshWhileViewing(
        context, stateManager, widgetId, source, repo, rows,
        nowMs = now, lastActualsAtMs = actualsAt, forecastIntervalMs = cadence,
    )

    @Test
    fun `forecast 30 min old refreshes only actuals, no forecast fetch`() = runBlocking {
        val decision = run(forecastAt = now - 30 * 60_000L)

        assertEquals(ViewingRefreshPolicy.Decision(refreshActuals = true, refreshForecast = false), decision)
        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `forecast due by the cadence gets a targeted hourly-limited refresh`() = runBlocking {
        run(forecastAt = now - cadence - 60_000L)

        val request = RefreshScheduler.lastForcedRefreshForTesting
        assertEquals("viewing_forecast_due", request?.reason)
        assertEquals(source.id, request?.targetSourceId)
        assertTrue(request!!.hourlyLimited)
    }

    @Test
    fun `fresh actuals and forecast do nothing`() = runBlocking {
        val decision = run(forecastAt = now, actualsAt = now)

        assertFalse(decision!!.any)
        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `null repository does not enqueue`() = runBlocking {
        assertNull(run(forecastAt = now - cadence - 60_000L, repo = null))
        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `rows missing for the viewed source do not enqueue`() = runBlocking {
        // Rows exist but belong to a different source: no freshness signal, and missing is not stale.
        val other = cloudRow(now - cadence - 60_000L).copy(source = WeatherSource.NWS.id)
        assertNull(run(forecastAt = 0L, rows = listOf(other)))
        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `enqueue marks the per-widget-source cooldown`() = runBlocking {
        run(forecastAt = now - cadence - 60_000L)

        val stillCoolingDown = !stateManager.shouldRefreshMissingData(
            widgetId,
            source.id,
            "cloud_viewing",
            ViewingRefreshPolicy.ACTUALS_STALE_WHILE_VIEWING_MS,
        )
        assertTrue("a successful enqueue must mark the cooldown so a repaint storm can't stampede", stillCoolingDown)
        RefreshScheduler.lastForcedRefreshForTesting = null
        run(forecastAt = now - cadence - 60_000L)
        assertNull("cooling down: no second enqueue", RefreshScheduler.lastForcedRefreshForTesting)
    }
}
