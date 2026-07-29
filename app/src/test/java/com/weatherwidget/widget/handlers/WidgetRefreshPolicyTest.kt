package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WidgetRefreshPolicyTest {

    @Test
    fun `recent successful check supersedes old unchanged content`() {
        assertEquals(
            20_000L,
            WidgetRefreshContextResolver.freshestAt(
                contentAtMs = 1_000L,
                successfulCheckAtMs = 20_000L,
            ),
        )
    }

    @Test
    fun `row timestamp bootstraps freshness when successful check is absent`() {
        assertEquals(
            1_000L,
            WidgetRefreshContextResolver.freshestAt(
                contentAtMs = 1_000L,
                successfulCheckAtMs = null,
            ),
        )
    }

    @Test
    fun `stale scheduling failure is logged and does not suppress following render`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        val database = mockk<WeatherDatabase>()
        every { database.appLogDao() } returns appLogDao
        val requester =
            InteractionRefreshRequester(
                staleRequest = { _, _, _, _ -> error("scheduler unavailable") },
            )
        var rendererInvoked = false

        requester.requestIfStale(
            context = mockk<Context>(relaxed = true),
            refreshContext = resolved(database),
            reason = "toggle_view",
        )
        rendererInvoked = true

        assertEquals(true, rendererInvoked)
        coVerify(exactly = 1) {
            appLogDao.insert(
                match {
                    it.tag == "STALE_REFRESH_ENQUEUE_FAIL" &&
                        it.message.contains("stale_on_toggle_view")
                },
            )
        }
    }

    @Test
    fun `forced scheduling always uses KEEP and failure does not escape`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        var policy: ExistingWorkPolicy? = null
        val requester =
            InteractionRefreshRequester(
                forcedRequest = { _, _, requestedPolicy, _, _ ->
                    policy = requestedPolicy
                    error("scheduler unavailable")
                },
            )

        requester.requestForced(
            context = mockk(relaxed = true),
            appLogDao = appLogDao,
            reason = "toggle_api_stale",
            targetSourceId = WeatherSource.NWS.id,
        )

        assertEquals(ExistingWorkPolicy.KEEP, policy)
        coVerify(exactly = 1) {
            appLogDao.insert(match { it.tag == "TOGGLE_REFRESH_ENQUEUE_FAIL" })
        }
    }

    @Test
    fun `scheduling cancellation remains terminal`() = runTest {
        val requester =
            InteractionRefreshRequester(
                staleRequest = { _, _, _, _ -> throw CancellationException("scope gone") },
            )

        try {
            requester.requestIfStale(
                context = mockk(relaxed = true),
                refreshContext = resolved(mockk(relaxed = true)),
                reason = "toggle_view",
            )
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private fun resolved(database: WeatherDatabase) =
        WidgetRefreshContextResolver.Resolved(
            database = database,
            forecastDao = mockk<ForecastDao>(relaxed = true),
            location = WidgetRefreshContextResolver.Location(37.4219, -122.0840),
            displaySource = WeatherSource.NWS,
            latestSuccessfulOrContentAtMs = 1_000L,
        )
}
