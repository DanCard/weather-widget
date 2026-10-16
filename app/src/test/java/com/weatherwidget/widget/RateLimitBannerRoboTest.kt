package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.RateLimitNotice
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.SourceErrorDetailsContent
import com.weatherwidget.widget.handlers.FailureBannerRepaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/**
 * A per-minute 429 is bannered at once on every view, for a minute — no 3-failure streak; a later
 * hourly page refused the same way is bannered as incomplete (user, 2026-10-16).
 * `plans/261016-quota-refusals-always-show-a-banner.md`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class RateLimitBannerRoboTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val google = WeatherSource.GOOGLE_WEATHER
    private lateinit var state: WidgetStateManager

    private val perMinuteBody =
        """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"1/min/{project}/{user}",""" +
            """"quota_limit":"ForecastHoursQueriesPerUser","quota_limit_value":"20",""" +
            """"quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    @Before
    fun setUp() {
        state = WidgetStateManager(context)
        state.recordSourceFetchSuccess(google)
        ForecastProduct.entries.forEach { state.clearProductQuota(google, it) }
    }

    @Test
    fun `one per-minute 429 shows the pill at once on the daily and hourly views`() {
        state.recordSourceFetchFailure(google, "HTTP_429", "status 429. Detail: $perMinuteBody")
        val now = System.currentTimeMillis()

        ForecastProduct.entries.forEach { product ->
            val pill = state.viewWatermark(google, product, now)
            assertTrue("$product", pill.show)
            assertEquals(RateLimitNotice.ERROR_CODE, pill.errorCode)
        }
    }

    @Test
    fun `the pill comes down a minute after the refusal`() {
        state.recordSourceFetchFailure(google, "HTTP_429", null)
        val at = requireNotNull(state.getRateLimit(google)).atMs

        assertTrue(state.viewWatermark(google, ForecastProduct.DAILY, at + RateLimitNotice.SHOW_MS - 1).show)
        assertFalse(state.viewWatermark(google, ForecastProduct.DAILY, at + RateLimitNotice.SHOW_MS).show)
    }

    @Test
    fun `other failures still wait for the 3-failure streak`() {
        state.recordSourceFetchFailure(google, "TIMEOUT", null)
        assertFalse(state.viewWatermark(google, ForecastProduct.HOURLY, System.currentTimeMillis()).show)
    }

    @Test
    fun `a daily quota keeps its own notice, not the rate-limit one`() {
        state.recordSourceFetchFailure(google, GoogleQuota.ERROR_CODE_DAILY, null)
        assertNull(state.getRateLimit(google))
    }

    @Test
    fun `a partial refusal is bannered as incomplete on every view and opens its details`() {
        state.recordRateLimit(google, partial = true, detail = "status 429. Detail: $perMinuteBody")
        val now = System.currentTimeMillis()

        ForecastProduct.entries.forEach { product ->
            assertEquals("$product", RateLimitNotice.ERROR_CODE_PARTIAL, state.viewWatermark(google, product, now).errorCode)
        }
        val content = SourceErrorDetailsContent.build(context, google, state, now, Locale.US, ZoneId.of("America/Los_Angeles"))
        assertNotNull("the pill must open a page, not an empty one", content)
        assertEquals("GOOGLE WEATHER RATE LIMITED", content!!.headline)
        assertEquals("Hourly forecast incomplete", content.summary)
        assertTrue("shows the per-minute limit", content.rows.any { it.second.contains("20") })
    }

    @Test
    fun `the pill reads rate limited, partial or not`() {
        fun layout(code: String) = requireNotNull(
            GraphFailureWatermarkRenderer.calculateLayout(
                width = 900f, height = 400f, density = 1f,
                sourceLabel = "Google Weather", errorCode = code,
                failureTimeMs = 0L, nowMs = 0L, locale = Locale.US, zoneId = ZoneId.of("UTC"),
                errorCodeText = { GraphFailureWatermarkRenderer.localizedErrorCodeText(context, it) },
                rateLimitedText = context.getString(R.string.updates_rate_limited),
                measureMain = { t, s -> t.length * s * 0.5f }, measureDetail = { t, s -> t.length * s * 0.5f },
                mainMetrics = { s -> -s * 0.8f to s * 0.2f }, detailMetrics = { s -> -s * 0.8f to s * 0.2f },
            ),
        )
        assertEquals("⚠ GOOGLE WEATHER RATE LIMITED", layout(RateLimitNotice.ERROR_CODE).mainText)
        assertEquals("Too many requests · 12:00 AM", layout(RateLimitNotice.ERROR_CODE).detailText)
        assertEquals("Hourly forecast incomplete · 12:00 AM", layout(RateLimitNotice.ERROR_CODE_PARTIAL).detailText)
    }

    @Test
    fun `the widget repaints when the minute is up`() {
        assertEquals("next stage first", 8_000L, FailureBannerRepaint.nextStageDelayMs(0L, 0L, RateLimitNotice.SHOW_MS))
        assertEquals(36_000L, FailureBannerRepaint.nextStageDelayMs(0L, 24_000L, RateLimitNotice.SHOW_MS))
        assertNull("gone, nothing left", FailureBannerRepaint.nextStageDelayMs(0L, 60_000L, RateLimitNotice.SHOW_MS))
        assertNull("other banners stop at 24 s", FailureBannerRepaint.nextStageDelayMs(0L, 24_000L))
    }
}
