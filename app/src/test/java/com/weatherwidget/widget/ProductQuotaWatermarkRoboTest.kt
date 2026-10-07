package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.SourceErrorDetailsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Google's forecast/hours and forecast/days have separate daily quotas. An hours-only refusal shows
 * on the hourly views and nowhere else (user, 2026-10-07: the daily view said "quota used").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ProductQuotaWatermarkRoboTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val google = WeatherSource.GOOGLE_WEATHER
    private val nowMs = Instant.parse("2026-10-07T15:33:00Z").toEpochMilli()
    private val resetMs = Instant.parse("2026-10-08T07:00:00Z").toEpochMilli()
    private lateinit var state: WidgetStateManager

    @Before
    fun setUp() {
        state = WidgetStateManager(context)
        state.recordSourceFetchSuccess(google)
        ForecastProduct.entries.forEach { state.clearProductQuota(google, it) }
    }

    @Test
    fun `an hours-only block shows on hourly views and not on the daily view`() {
        state.recordProductQuota(google, ForecastProduct.HOURLY, resetMs, detail = null)

        val hourly = state.viewWatermark(google, ForecastProduct.HOURLY, nowMs)
        assertTrue(hourly.show)
        assertEquals(GoogleQuota.ERROR_CODE_HOURLY_FORECAST, hourly.errorCode)
        assertFalse("daily view stays clean", state.viewWatermark(google, ForecastProduct.DAILY, nowMs).show)
    }

    @Test
    fun `the block ends at its reset, and a successful product fetch clears it`() {
        state.recordProductQuota(google, ForecastProduct.HOURLY, resetMs, detail = null)
        assertFalse(state.viewWatermark(google, ForecastProduct.HOURLY, resetMs + 1).show)

        state.clearProductQuota(google, ForecastProduct.HOURLY)
        assertFalse(state.viewWatermark(google, ForecastProduct.HOURLY, nowMs).show)
    }

    @Test
    fun `a source-wide failure still shows on every view`() {
        repeat(3) { state.recordSourceFetchFailure(google, GoogleQuota.ERROR_CODE_DAILY) }
        assertTrue(state.viewWatermark(google, ForecastProduct.DAILY, nowMs).show)
        assertTrue(state.viewWatermark(google, ForecastProduct.HOURLY, nowMs).show)
        state.recordSourceFetchSuccess(google)
    }

    @Test
    fun `the hourly pill reads hourly forecast quota`() {
        val layout = requireNotNull(
            GraphFailureWatermarkRenderer.calculateLayout(
                width = 600f, height = 400f, density = 1f,
                sourceLabel = "Google Weather", errorCode = GoogleQuota.ERROR_CODE_HOURLY_FORECAST,
                failureTimeMs = nowMs, nowMs = nowMs, locale = Locale.US,
                zoneId = ZoneId.of("America/Los_Angeles"),
                measureMain = { t, s -> t.length * s * 0.5f }, measureDetail = { t, s -> t.length * s * 0.5f },
                mainMetrics = { s -> -s * 0.8f to s * 0.2f }, detailMetrics = { s -> -s * 0.8f to s * 0.2f },
            ),
        )
        assertEquals("Hourly forecast quota used · resets 12 AM", layout.detailText)
        assertEquals(
            "Daily forecast quota used · resets 12 AM",
            context.getString(com.weatherwidget.R.string.watermark_quota_daily_forecast, "12 AM"),
        )
    }

    @Test
    fun `tapping the hourly pill opens details for that quota`() {
        val body = """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"1/d/{project}",""" +
            """"quota_limit":"ForecastHoursQueriesPerDay","quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""
        state.recordProductQuota(google, ForecastProduct.HOURLY, resetMs, detail = "status 429. Detail: $body")

        val content = SourceErrorDetailsContent.build(context, google, state, nowMs, Locale.US, ZoneId.of("America/Los_Angeles"))
        assertNotNull("an hourly pill must open a page, not an empty one", content)
        assertEquals("Hourly forecast quota used · resets 12 AM", content!!.summary)
    }
}
