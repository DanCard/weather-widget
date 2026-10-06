package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.shared.util.FailureBannerStage
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.FailureBannerRepaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The daily-quota wording and the full → tiny → faded staging of the source-failure banner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class FailureBannerStagingRoboTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pacific = ZoneId.of("America/Los_Angeles")

    // 2026-10-06 14:37 PDT, the Fold's last 429.
    private val failureMs = Instant.parse("2026-10-06T21:37:00Z").toEpochMilli()

    private fun layout(
        errorCode: String,
        stage: FailureBannerStage = FailureBannerStage.FULL,
        zone: ZoneId = pacific,
    ): FailureWatermarkLayout =
        requireNotNull(
            GraphFailureWatermarkRenderer.calculateLayout(
                width = 600f, height = 400f, density = 1f,
                sourceLabel = "Google Weather", errorCode = errorCode, failureTimeMs = failureMs,
                nowMs = failureMs, locale = Locale.US, zoneId = zone, stage = stage,
                measureMain = ::measure, measureDetail = ::measure,
                mainMetrics = ::metrics, detailMetrics = ::metrics,
            ),
        )

    @Test
    fun `daily quota reads paused with the reset time, not failing with a 429`() {
        val l = layout(GoogleQuota.ERROR_CODE_DAILY)
        assertEquals("⚠ GOOGLE WEATHER UPDATES PAUSED", l.mainText)
        assertEquals("Daily quota used · resets 12 AM", l.detailText)
    }

    @Test
    fun `reset time is midnight Pacific shown in the viewer's zone`() {
        assertEquals("Daily quota used · resets 3 AM", layout(GoogleQuota.ERROR_CODE_DAILY, zone = ZoneId.of("America/New_York")).detailText)
    }

    @Test
    fun `an ordinary 429 still reads failing`() {
        val l = layout("HTTP_429")
        assertEquals("⚠ GOOGLE WEATHER UPDATES FAILING", l.mainText)
        assertEquals("429 Rate Limited · 2:37 PM", l.detailText)
    }

    @Test
    fun `tiny stage is one smaller line of the detail and faded adds transparency`() {
        val full = layout(GoogleQuota.ERROR_CODE_DAILY)
        val tiny = layout(GoogleQuota.ERROR_CODE_DAILY, FailureBannerStage.TINY)
        assertEquals("⚠ Daily quota used · resets 12 AM", tiny.mainText)
        assertNull(tiny.detailText)
        assertTrue(tiny.mainTextSize < full.mainTextSize)
        assertTrue(tiny.pillBounds.height() < full.pillBounds.height() / 2f)
        assertEquals(1f, tiny.alpha, 0f)

        val faded = layout(GoogleQuota.ERROR_CODE_DAILY, FailureBannerStage.FADED)
        assertEquals(tiny.mainText, faded.mainText)
        assertEquals(FailureBannerStage.FADED_ALPHA, faded.alpha, 0f)
    }

    @Test
    fun `no anchor draws the full pill`() {
        assertEquals(FailureBannerStage.FULL, GraphFailureWatermarkRenderer.stageFor(null, failureMs))
        assertEquals(FailureBannerStage.TINY, GraphFailureWatermarkRenderer.stageFor(failureMs, failureMs + 9_000L))
    }

    @Test
    fun `the Android strings carry the daily quota wording`() {
        assertEquals("Daily quota used", GraphFailureWatermarkRenderer.localizedErrorCodeText(context, GoogleQuota.ERROR_CODE_DAILY))
        assertEquals("Daily quota used · resets 12 AM", context.getString(com.weatherwidget.R.string.watermark_quota_daily, "12 AM"))
    }

    @Test
    fun `repaints land on the 8 and 24 second boundaries, then stop`() {
        assertEquals(8_000L, FailureBannerRepaint.nextStageDelayMs(0L, 0L))
        assertEquals(5_000L, FailureBannerRepaint.nextStageDelayMs(0L, 3_000L))
        assertEquals(16_000L, FailureBannerRepaint.nextStageDelayMs(0L, 8_000L))
        assertNull(FailureBannerRepaint.nextStageDelayMs(0L, 24_000L))
        assertNull(FailureBannerRepaint.nextStageDelayMs(null, 0L))
    }

    @Test
    fun `daily view must paint through the stage window, then may skip again`() {
        assertTrue(FailureBannerRepaint.stageChangeMayBePending(0L, 8_100L))
        assertTrue("a late 24 s repaint still paints", FailureBannerRepaint.stageChangeMayBePending(0L, 50_000L))
        assertTrue(!FailureBannerRepaint.stageChangeMayBePending(0L, 120_000L))
        assertTrue(!FailureBannerRepaint.stageChangeMayBePending(null, 8_100L))
    }

    @Test
    fun `banner anchor is set at the threshold and on a new code, never on a repeat`() {
        var nowMs = 1_000_000L
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        }
        val prefs = context.getSharedPreferences("banner_anchor_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = WidgetFetchStateStore(prefs, clock)
        val g = WeatherSource.GOOGLE_WEATHER

        store.recordSourceFetchFailure(g, "HTTP_429", bannerThreshold = 3)
        store.recordSourceFetchFailure(g, "HTTP_429", bannerThreshold = 3)
        assertNull("no banner yet, no anchor", store.sourceBannerSince(g))

        nowMs += 1_000
        store.recordSourceFetchFailure(g, "HTTP_429", bannerThreshold = 3)
        val shown = nowMs
        assertEquals(shown, store.sourceBannerSince(g))

        nowMs += 60_000
        store.recordSourceFetchFailure(g, "HTTP_429", bannerThreshold = 3)
        assertEquals("a repeat keeps it small", shown, store.sourceBannerSince(g))

        nowMs += 60_000
        store.recordSourceFetchFailure(g, GoogleQuota.ERROR_CODE_DAILY, bannerThreshold = 3)
        assertEquals("new news gets the full pill", nowMs, store.sourceBannerSince(g))

        store.recordSourceFetchSuccess(g)
        assertNull(store.sourceBannerSince(g))
    }

    @Test
    fun `state from before anchors existed gets one on its next failure`() {
        val prefs = context.getSharedPreferences("banner_anchor_legacy", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("source_fail_count_GOOGLE_WEATHER", 4)
            .putString("source_fail_code_GOOGLE_WEATHER", "HTTP_429").commit()
        val store = WidgetFetchStateStore(prefs)
        store.recordSourceFetchFailure(WeatherSource.GOOGLE_WEATHER, "HTTP_429", bannerThreshold = 3)
        assertNotNull(store.sourceBannerSince(WeatherSource.GOOGLE_WEATHER))
    }

    private fun measure(text: String, textSize: Float): Float = text.length * textSize * 0.5f

    private fun metrics(textSize: Float): Pair<Float, Float> = -textSize * 0.8f to textSize * 0.2f
}
