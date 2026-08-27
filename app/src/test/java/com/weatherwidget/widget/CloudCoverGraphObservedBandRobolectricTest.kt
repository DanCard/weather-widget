package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * The renderer's half of the observed band trails: that a divergent actual actually produces extra
 * glyph ink, that an agreeing or unfrozen one produces none, and that whatever is drawn is handed
 * to the label-placement search as an obstacle.
 *
 * Robolectric has no font engine, so nothing here asserts pixels. The assertions are counts and
 * containment over boxes sized from dp
 * ([CloudLayerGlyphPlacer.GLYPH_BOX_WIDTH_RATIO][com.weatherwidget.shared.graph.CloudLayerGlyphPlacer.GLYPH_BOX_WIDTH_RATIO]),
 * which is the same reason the sibling obstacle test is meaningful here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class CloudCoverGraphObservedBandRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val start = LocalDateTime.of(2026, 8, 27, 4, 0)

    /**
     * A steady mid deck across the window. [actualMid] and [frozen] vary per case; the forecast is
     * held constant so any change in glyph count comes from the observed trail alone.
     */
    private fun hours(
        actualMid: Int?,
        frozen: Boolean,
        forecastMid: Int = 80,
    ): List<CloudCoverGraphRenderer.CloudHourData> = (0 until 18).map { index ->
        CloudCoverGraphRenderer.CloudHourData(
            dateTime = start.plusHours(index.toLong()),
            cloudCover = 3,
            midCover = forecastMid,
            actualMidCover = actualMid,
            isFrozenBands = frozen,
            label = "${index}h",
            showLabel = index % 4 == 0,
            isCurrentHour = index == 17,
        )
    }

    private fun glyphBoxesFor(hours: List<CloudCoverGraphRenderer.CloudHourData>): List<GraphRect> {
        var glyphs: List<GraphRect> = emptyList()
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1460,
            heightPx = 900,
            currentTime = start.plusHours(17),
            dominantStationLabel = DominantStationLabel.plainLabelText("Actual cloud cover data from Open-Meteo"),
            onLayerGlyphsPlaced = { glyphs = it },
        )
        return glyphs
    }

    private val forecastOnly by lazy { glyphBoxesFor(hours(actualMid = null, frozen = false)) }

    @Test
    fun `a divergent frozen actual adds a second trail of glyph ink`() {
        val withActual = glyphBoxesFor(hours(actualMid = 20, frozen = true))

        assertTrue("expected a forecast trail to compare against", forecastOnly.size > 20)
        assertTrue(
            "expected the observed trail to add glyphs: forecastOnly=${forecastOnly.size} withActual=${withActual.size}",
            withActual.size > forecastOnly.size,
        )
    }

    @Test
    fun `an actual within the divergence floor adds nothing`() {
        val agreeing = glyphBoxesFor(hours(actualMid = 80 + 1, frozen = true))

        assertEquals(
            "an agreeing actual must leave the graph exactly as it was",
            forecastOnly.size,
            agreeing.size,
        )
    }

    /**
     * Without a stored snapshot the forecast trail is carrying the retro-corrected live row, so a
     * pink glyph would be comparing the actual against a copy of itself.
     */
    @Test
    fun `an unfrozen hour draws no observed glyph however far it diverges`() {
        val unfrozen = glyphBoxesFor(hours(actualMid = 5, frozen = false))

        assertEquals(forecastOnly.size, unfrozen.size)
    }

    /**
     * The observed trail must reach the same obstacle list the forecast one does, or the
     * free-floating dominant-station label reads it as open air.
     */
    @Test
    fun `observed glyphs reach the label placement search`() {
        var placement: DominantStationLabel.Placement? = null
        var glyphs: List<GraphRect> = emptyList()
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours(actualMid = 20, frozen = true),
            widthPx = 1460,
            heightPx = 900,
            currentTime = start.plusHours(17),
            dominantStationLabel = DominantStationLabel.plainLabelText("Actual cloud cover data from Open-Meteo"),
            onDominantStationPlaced = { placement = it },
            onLayerGlyphsPlaced = { glyphs = it },
        )

        assertTrue("glyph boxes must have area", glyphs.all { it.right > it.left && it.bottom > it.top })
        val hit = placement?.let { placed -> glyphs.filter { it.intersects(placed.box) } }.orEmpty()
        assertTrue("annotation overlaps ${hit.size} glyph box(es): first=${hit.firstOrNull()}", hit.isEmpty())
    }

    /**
     * The scale must account for the observed trail: an actual far above every forecast value
     * would otherwise be placed above the plot's top edge.
     */
    @Test
    fun `a tall observed band stays inside the plot`() {
        val glyphs = glyphBoxesFor(hours(actualMid = 100, frozen = true, forecastMid = 10))

        assertTrue("expected glyphs to be drawn", glyphs.isNotEmpty())
        assertTrue(
            "every glyph must sit inside the canvas; highest top=${glyphs.minOf { it.top }}",
            glyphs.all { it.top >= 0f && it.bottom <= 900f },
        )
    }
}
