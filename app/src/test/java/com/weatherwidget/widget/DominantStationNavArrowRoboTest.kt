package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.NavArrowGeometry
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Integration test: `TemperatureGraphRenderer` -> `TemperatureGraphAnnotationRenderer` ->
 * `DominantStationLabel` -> `GraphEmptySpaceFinder` -> `NavArrowGeometry`.
 *
 * Covers the 2026-08-18 bug where `knuq 64.4° @ 10:10 am` was drawn on the left nav chevron on both
 * the emulator and the Samsung Fold. The arrows are RemoteViews children the launcher composites
 * over this bitmap, so nothing the renderer draws reveals them and the finder read that strip as the
 * emptiest place on the plot — and `DominantStationLabel.X_FRACTIONS` leads with a left-edge anchor,
 * so it was the first place it looked.
 *
 * **Scope limit, deliberately stated:** the oracle here is `NavArrowGeometry.arrowBounds`, which is
 * part of the code under test. This proves the renderer honours the arrow rectangle; it cannot prove
 * the rectangle matches the real chevron. `NavTouchZoneRoboTest` pins the constants against the
 * inflated layout, and `DominantStationNavArrowLayoutRoboTest` closes the loop by laying out the
 * real widget.
 */
@RunWith(RobolectricTestRunner::class)
// xxhdpi, not the default mdpi: at density 1 the arrow band is only 36px and Robolectric's
// font-less measureText returns ~1px per character, so the label lands clear of the band by
// accident and the test passes without the fix. Real devices are 2.63 (emulator) and 3.03 (Fold).
@Config(sdk = [35], qualifiers = "xxhdpi")
@Category(LongDuration::class)
class DominantStationNavArrowRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val start: LocalDateTime = LocalDateTime.of(2026, 8, 18, 0, 0)

    /**
     * An overnight flat line down at the bottom of the plot, exactly the shape that made the bug so
     * reliable: it leaves the whole upper-left wide open, so the very first anchor is legal and the
     * search returns immediately.
     */
    private fun flatOvernightHours(): List<HourData> =
        (0 until 18).map { index ->
            val dateTime = start.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = if (index < 10) 60f else 60f + (index - 9) * 2f,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    private fun label(): DominantStationLabel.LabelText =
        requireNotNull(
            DominantStationLabel.formatLabelText(
                stationId = "KNUQ",
                rawTemp = 64.4f,
                useCelsius = false,
                lastReadingMs = start.plusHours(10)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                zoneId = ZoneId.systemDefault(),
            ),
        ) { "test fixture must produce a label" }

    private fun render(
        widthPx: Int = 900,
        heightPx: Int = 500,
        visibility: NavArrowGeometry.Visibility = NavArrowGeometry.Visibility.BOTH,
    ): DominantStationDebug {
        var debug: DominantStationDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = flatOvernightHours(),
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = start.plusHours(10),
            numColumns = 5,
            useCelsius = false,
            dominantStationLabel = label(),
            navArrowVisibility = visibility,
            onDominantStationPlaced = { debug = it },
        )
        return requireNotNull(debug) { "renderer must report a dominant-station outcome" }
    }

    private fun GraphRect.intersects(other: GraphRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    @Test
    fun `dominant station label is not drawn on either nav arrow`() {
        val debug = render()

        // A suppressed label trivially clears the arrow; without this the test would pass on the
        // exact regression it exists to catch.
        assertEquals(
            "label must actually be drawn for the geometry check to mean anything. debug=$debug",
            "drawn",
            debug.reason,
        )
        val box = requireNotNull(debug.box) { "reason=drawn must carry a box" }
        val labelRect = GraphRect(box.left, box.top, box.right, box.bottom)

        assertEquals(
            "expected both arrows to be reserved. debug=$debug",
            2,
            debug.navArrowBounds.size,
        )
        debug.navArrowBounds.forEachIndexed { index, rect ->
            val arrow = GraphRect(rect.left, rect.top, rect.right, rect.bottom)
            assertTrue(
                "dominant-station label overlaps nav arrow #$index. " +
                    "label=$labelRect arrow=$arrow text=${debug.text}",
                !labelRect.intersects(arrow),
            )
        }
    }

    /**
     * Sanity-checks the reserved rectangles themselves, so the overlap test above cannot pass by
     * reserving nothing or reserving a band in the wrong place. Whether these rects match the real
     * chevron is the layout test's job, not this one's.
     */
    @Test
    fun `reserved arrow rects hug the plot edges`() {
        val debug = render()
        val (left, right) = debug.navArrowBounds.sortedBy { it.left }
        assertEquals("left arrow starts at the plot's left edge", 0f, left.left, 0.01f)
        assertEquals("right arrow ends at the plot's right edge", 900f, right.right, 0.01f)
        assertTrue("bands must be non-empty", left.height() > 0f && left.width() > 0f)
        assertTrue("bands must not meet: $left / $right", left.right < right.left)
    }

    /**
     * The veto must cost the label a vertical slot, not its anchor. If someone "fixes" this by
     * registering the arrows as repelling `drawnBounds`, the label flees to the far side of the plot
     * and this fails — which is the point.
     */
    @Test
    fun `label still prefers the left edge with the arrow vetoed`() {
        val debug = render()
        assertEquals("drawn", debug.reason)
        val centerX = requireNotNull(debug.centerX)
        assertTrue(
            "expected the label to stay in the left half (anchor preserved), got centerX=$centerX " +
                "of 900px. debug=$debug",
            centerX < 450f,
        )
    }

    /**
     * Guards the gate itself: with no arrows on screen there is nothing to avoid, so the label is
     * free to take its first-choice anchor hard against the left edge.
     */
    @Test
    fun `with no arrows the label may sit against the very edge`() {
        val withArrows = render(visibility = NavArrowGeometry.Visibility.BOTH)
        val withoutArrows = render(visibility = NavArrowGeometry.Visibility.NONE)

        assertEquals("drawn", withArrows.reason)
        assertEquals("drawn", withoutArrows.reason)
        assertNotNull(withoutArrows.box)
        // Not asserting they differ — the label may legitimately find the same clear band either way.
        // What must hold is that removing an obstacle never makes placement worse.
        assertTrue(
            "removing the arrows must not push the label rightwards. " +
                "withArrows=${withArrows.centerX} withoutArrows=${withoutArrows.centerX}",
            requireNotNull(withoutArrows.centerX) <= requireNotNull(withArrows.centerX) + 1f,
        )
    }
}
