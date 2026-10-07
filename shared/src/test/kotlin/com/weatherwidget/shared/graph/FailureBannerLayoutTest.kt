package com.weatherwidget.shared.graph

import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.shared.util.FailureBannerStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The contract both the Android widget and the desktop popup draw from: what the failure pill says,
 * and where it sits. A platform-only change to either can no longer move one of them.
 */
@Category(ShortDuration::class)
class FailureBannerLayoutTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDateTime.of(2026, 10, 7, 14, 35).atZone(zone).toInstant().toEpochMilli()

    // Monospace stand-in: 0.6 em per character, ascent −0.9 em, descent 0.25 em.
    private fun pill(
        width: Float = 600f,
        height: Float = 400f,
        errorCode: String?,
        failureTimeMs: Long? = now - 60_000,
        stage: FailureBannerStage = FailureBannerStage.FULL,
        sourceLabel: String? = "Google",
    ) = FailureBannerLayout.calculate(
        width = width,
        height = height,
        density = 1f,
        sourceLabel = sourceLabel,
        errorCode = errorCode,
        failureTimeMs = failureTimeMs,
        nowMs = now,
        locale = Locale.US,
        zoneId = zone,
        stage = stage,
        measureMain = { text, size -> text.length * size * 0.6f },
        measureDetail = { text, size -> text.length * size * 0.6f },
        mainMetrics = { size -> -0.9f * size to 0.25f * size },
        detailMetrics = { size -> -0.9f * size to 0.25f * size },
    )

    @Test
    fun `pill is centred on the graph both ways`() {
        val p = placed(pill(errorCode = "HTTP_429"))
        val b = p.bounds
        assertEquals("horizontal centre", 300f, (b.left + b.right) / 2f, 0.01f)
        assertEquals("vertical centre", 200f, (b.top + b.bottom) / 2f, 0.01f)
    }

    @Test
    fun `daily quota reads updates paused with the reset time`() {
        val p = placed(pill(errorCode = GoogleQuota.ERROR_CODE_DAILY))
        assertEquals("⚠ GOOGLE UPDATES PAUSED", p.mainText)
        assertEquals("Daily quota used · resets 12 AM", p.detailText)
    }

    @Test
    fun `hourly forecast quota names its product`() {
        val p = placed(pill(errorCode = GoogleQuota.ERROR_CODE_HOURLY_FORECAST))
        assertEquals("⚠ GOOGLE UPDATES PAUSED", p.mainText)
        assertEquals("Hourly forecast quota used · resets 12 AM", p.detailText)
    }

    @Test
    fun `ordinary failure reads updates failing with code and time`() {
        val p = placed(pill(errorCode = "HTTP_429"))
        assertEquals("⚠ GOOGLE UPDATES FAILING", p.mainText)
        assertEquals("429 Rate Limited · 2:34 PM", p.detailText)
    }

    @Test
    fun `tiny stage is one line of detail, faded stage also dims`() {
        val tiny = placed(pill(errorCode = "DNS_ERROR", stage = FailureBannerStage.TINY))
        assertEquals("⚠ DNS Error · 2:34 PM", tiny.mainText)
        assertNull(tiny.detailText)
        assertEquals(1f, tiny.alpha, 0f)
        val faded = placed(pill(errorCode = "DNS_ERROR", stage = FailureBannerStage.FADED))
        assertEquals(FailureBannerStage.FADED_ALPHA, faded.alpha, 0f)
        val full = placed(pill(errorCode = "DNS_ERROR"))
        assertTrue("tiny pill is smaller", tiny.bounds.bottom - tiny.bounds.top < full.bounds.bottom - full.bounds.top)
    }

    @Test
    fun `narrow graph shrinks then ellipsizes, never overflows`() {
        val p = placed(pill(width = 120f, errorCode = "HTTP_503"))
        assertTrue("fits inside the 4 dp insets", p.bounds.left >= 4f && p.bounds.right <= 116f)
        assertTrue("truncated text ends with an ellipsis: ${p.mainText}", p.mainText.endsWith("…"))
    }

    @Test
    fun `no room for the pill draws nothing`() {
        assertNull(pill(height = 10f, errorCode = "HTTP_429"))
    }

    private fun placed(value: FailureBannerPill?): FailureBannerPill {
        assertNotNull("expected a pill", value)
        return value!!
    }
}
