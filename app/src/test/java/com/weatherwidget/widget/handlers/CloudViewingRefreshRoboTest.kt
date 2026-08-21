package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.CloudViewingRefreshPolicy
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
 * Pins the cloud-while-viewing watchdog: rendering the CLOUD view must enqueue a targeted refresh
 * of the active source when that source's cloud data is stale, and stay quiet otherwise (fresh,
 * missing rows, or no repository). The actual WorkManager enqueue is suppressed via
 * [RefreshScheduler.setIsRefreshDisabledForTesting] and observed through `lastForcedRefreshForTesting`.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudViewingRefreshRoboTest {

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

    @Test
    fun `stale cloud enqueues a targeted refresh of the active source`() = runBlocking {
        val staleAt = System.currentTimeMillis() - CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS - 60_000L

        CloudCoverViewHandler.maybeRefreshCloudWhileViewing(
            context, stateManager, widgetId, source, repository, listOf(cloudRow(staleAt)),
        )

        val request = RefreshScheduler.lastForcedRefreshForTesting
        assertEquals("cloud_while_viewing", request?.reason)
        assertEquals(source.id, request?.targetSourceId)
    }

    @Test
    fun `fresh cloud does not enqueue`() = runBlocking {
        val freshAt = System.currentTimeMillis()

        CloudCoverViewHandler.maybeRefreshCloudWhileViewing(
            context, stateManager, widgetId, source, repository, listOf(cloudRow(freshAt)),
        )

        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `null repository does not enqueue`() = runBlocking {
        val staleAt = System.currentTimeMillis() - CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS - 60_000L

        CloudCoverViewHandler.maybeRefreshCloudWhileViewing(
            context, stateManager, widgetId, source, null, listOf(cloudRow(staleAt)),
        )

        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `rows missing for the active source do not enqueue`() = runBlocking {
        // Rows exist but belong to a different source, so there is no freshness signal for the
        // active source — this must not be treated as stale (missing ≠ stale).
        val staleAt = System.currentTimeMillis() - CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS - 60_000L
        val otherSourceRow = cloudRow(staleAt).copy(source = WeatherSource.NWS.id)

        CloudCoverViewHandler.maybeRefreshCloudWhileViewing(
            context, stateManager, widgetId, source, repository, listOf(otherSourceRow),
        )

        assertNull(RefreshScheduler.lastForcedRefreshForTesting)
    }

    @Test
    fun `enqueue marks the per-widget-source cooldown`() = runBlocking {
        val staleAt = System.currentTimeMillis() - CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS - 60_000L

        CloudCoverViewHandler.maybeRefreshCloudWhileViewing(
            context, stateManager, widgetId, source, repository, listOf(cloudRow(staleAt)),
        )

        val stillCoolingDown = !stateManager.shouldRefreshMissingData(
            widgetId,
            source.id,
            "cloud_viewing",
            CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS,
        )
        assertTrue("a successful enqueue must mark the cooldown so a repaint storm can't stampede", stillCoolingDown)
    }
}
