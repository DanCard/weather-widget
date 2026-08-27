package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.CloudBands
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Desktop's half of the observed band trails, mirroring
 * `CloudCoverGraphObservedBandRobolectricTest` on Android: actuals draw independently of forecast
 * accuracy/frozen state, any layer matching total stays silent, and drawn ink reaches obstacles.
 *
 * Counts rather than pixels, so the two platforms can assert the same invariants despite Skia
 * measuring text here and Robolectric stubbing it there.
 */
@Category(LongDuration::class)
class CloudCoverGraphObservedBandTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val hourMs = 3_600_000L
    private val now = System.currentTimeMillis()
    private val topOfHour = now - (now % hourMs)

    /** A steady mid deck; the forecast is held constant across every case. */
    private fun midDeck(forecastMid: Int = 80): List<HourlyForecast> =
        (-8..12).map { offset ->
            HourlyForecast(
                dateTime = topOfHour + offset * hourMs,
                temperature = 64f,
                condition = "Cloudy",
                cloudCover = 100,
                cloudCoverLow = 3,
                cloudCoverMid = forecastMid,
            )
        }

    private fun retroActual(total: Int = 5): Map<Long, Int> =
        (-8..0).associate { (topOfHour + it * hourMs) to total }

    private fun pastHours() = (-8..0).map { topOfHour + it * hourMs }

    private fun render(
        forecastMid: Int = 80,
        actualMid: Int? = null,
        frozen: Boolean = false,
        actualTotal: Int = 5,
        actualLow: Int? = null,
    ): CloudGraphPlacementDebug {
        var debug: CloudGraphPlacementDebug? = null
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1200.dp, 700.dp)) {
                CloudCoverGraph(
                    hourly = midDeck(forecastMid),
                    retroCloudActual = retroActual(actualTotal),
                    // Only a stored snapshot makes the gray forecast frozen. Pink actual bands
                    // remain independently visible with or without one.
                    priorDayBandForecast = if (frozen) {
                        pastHours().associateWith { CloudBands(mid = forecastMid) }
                    } else {
                        emptyMap()
                    },
                    retroCloudBands = if (actualLow != null || actualMid != null) {
                        pastHours().associateWith { CloudBands(low = actualLow, mid = actualMid) }
                    } else {
                        emptyMap()
                    },
                    displaySourceId = "OPEN_METEO",
                    modifier = Modifier.requiredSize(1200.dp, 700.dp),
                    onPlacementDebug = { debug = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        assertNotNull("expected the cloud graph to emit placement debug", debug)
        return debug!!
    }

    @Test
    fun `an actual adds a second trail of glyph ink`() {
        val forecastOnly = render().layerGlyphBounds.size
        val withActual = render(actualMid = 20, frozen = true).layerGlyphBounds.size

        assertTrue("expected a forecast trail to compare against, got $forecastOnly", forecastOnly > 20)
        assertTrue(
            "expected the observed trail to add glyphs: forecastOnly=$forecastOnly withActual=$withActual",
            withActual > forecastOnly,
        )
    }

    @Test
    fun `an agreeing actual still adds its pink trail`() {
        val forecastOnly = render().layerGlyphBounds.size
        val agreeing = render(actualMid = 81, frozen = true).layerGlyphBounds.size

        assertTrue("actual shape must remain visible even when forecast agrees", agreeing > forecastOnly)
    }

    @Test
    fun `an unfrozen hour still draws its observed glyph trail`() {
        val forecastOnly = render().layerGlyphBounds.size
        val unfrozen = render(actualMid = 6, frozen = false, actualTotal = 5).layerGlyphBounds.size

        assertTrue(unfrozen > forecastOnly)
    }

    @Test
    fun `an actual at one hundred draws nothing when total is also one hundred`() {
        val forecastOnly = render().layerGlyphBounds.size
        val matchingTop = render(actualMid = 100, actualTotal = 100).layerGlyphBounds.size

        assertEquals(forecastOnly, matchingTop)
    }

    @Test
    fun `an actual at one hundred still draws when total differs`() {
        val forecastOnly = render().layerGlyphBounds.size
        val distinctTop = render(actualMid = 100, actualTotal = 99).layerGlyphBounds.size

        assertTrue(distinctTop > forecastOnly)
    }

    @Test
    fun `an actual low below five draws when total differs`() {
        val forecastOnly = render().layerGlyphBounds.size
        val distinctLow = render(actualLow = 3, actualTotal = 80).layerGlyphBounds.size

        assertTrue(distinctLow > forecastOnly)
    }

    @Test
    fun `an actual low draws nothing when it matches non-extreme total`() {
        val forecastOnly = render().layerGlyphBounds.size
        val matchingLow = render(actualLow = 44, actualTotal = 44).layerGlyphBounds.size

        assertEquals(forecastOnly, matchingLow)
    }

    @Test
    fun `observed glyphs reach the label placement search`() {
        val debug = render(actualMid = 20, frozen = true)
        val placement = debug.actualsSourcePlacement

        assertTrue(
            "glyph boxes must have area",
            debug.layerGlyphBounds.all { it.right > it.left && it.bottom > it.top },
        )
        val hit = placement?.let { placed -> debug.layerGlyphBounds.filter { it.intersects(placed.box) } }.orEmpty()
        assertTrue("annotation overlaps ${hit.size} glyph box(es): first=${hit.firstOrNull()}", hit.isEmpty())
    }
}
