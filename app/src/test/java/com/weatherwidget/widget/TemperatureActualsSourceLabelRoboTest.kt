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
 * `DominantStationLabel` -> `GraphEmptySpaceFinder` for the borrowed-actuals source annotation
 * ("Actual temperature data from X").
 *
 * The annotation is the lowest-priority free-floating label, so it must draw only where a clear
 * band actually exists and must never land on the launcher's nav arrows (which are composited over
 * the bitmap, invisible to the renderer otherwise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@Category(LongDuration::class)
class TemperatureActualsSourceLabelRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val start: LocalDateTime = LocalDateTime.of(2026, 8, 18, 0, 0)

    private data class RenderResult(
        val dominant: DominantStationDebug,
        val actualsSource: ActualsSourceDebug,
    )

    /**
     * An overnight flat line down at the bottom of the plot: the whole upper half is empty, so the
     * first empty-space anchor is legal and the annotation should place immediately.
     */
    private fun flatOvernightHours(): List<HourData> =
        (0 until 18).map { index ->
            val dateTime = start.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = if (index < 10) 60f else 60f + (index - 9) * 2f,
                isCurrentHour = index == 10,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    private fun hoursSpanning(hours: Int): List<HourData> =
        (0..hours).map { index ->
            val dateTime = start.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = 60f + (index % 3) * 2f,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    private fun label(): DominantStationLabel.LabelText =
        requireNotNull(
            DominantStationLabel.plainLabelText("Actual temperature data from Synoptic"),
        ) { "test fixture must produce a label" }

    private fun dominantLabel(): DominantStationLabel.LabelText =
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
        hours: List<HourData> = flatOvernightHours(),
        actualsSourceLabel: DominantStationLabel.LabelText? = label(),
        dominantStationLabel: DominantStationLabel.LabelText? = null,
        widthPx: Int = 900,
        heightPx: Int = 500,
        visibility: NavArrowGeometry.Visibility = NavArrowGeometry.Visibility.BOTH,
        headerBandBottomPx: Float = 0f,
    ): RenderResult {
        var dominantDebug: DominantStationDebug? = null
        var actualsDebug: ActualsSourceDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = start.plusHours(10),
            numColumns = 5,
            useCelsius = false,
            dominantStationLabel = dominantStationLabel,
            actualsSourceLabel = actualsSourceLabel,
            navArrowVisibility = visibility,
            headerBandBottomPx = headerBandBottomPx,
            onDominantStationPlaced = { dominantDebug = it },
            onActualsSourcePlaced = { actualsDebug = it },
        )
        return RenderResult(
            dominant = requireNotNull(dominantDebug) { "renderer must report a dominant-station outcome" },
            actualsSource = requireNotNull(actualsDebug) { "renderer must report an actuals-source outcome" },
        )
    }

    private fun GraphRect.intersects(other: GraphRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    @Test
    fun `actuals source label is drawn on a clear plot and avoids nav arrows`() {
        val debug = render().actualsSource

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
                "actuals-source label overlaps nav arrow #$index. " +
                    "label=$labelRect arrow=$arrow text=${debug.text}",
                !labelRect.intersects(arrow),
            )
        }
    }

    @Test
    fun `actuals source label is suppressed when the window is too wide`() {
        val debug = render(hours = hoursSpanning(30)).actualsSource

        assertEquals("span_too_wide", debug.reason)
        assertEquals(null, debug.box)
    }

    @Test
    fun `actuals source label is suppressed when there is no label to draw`() {
        val debug = render(actualsSourceLabel = null).actualsSource

        assertEquals("no_text", debug.reason)
        assertEquals(null, debug.box)
    }

    @Test
    fun `actuals source label never overlaps the dominant station label`() {
        val result = render(dominantStationLabel = dominantLabel())
        val dominant = result.dominant
        val actuals = result.actualsSource

        assertEquals("dominant label must draw on a clear plot", "drawn", dominant.reason)
        // The dominant label is placed first; when the actuals-source annotation still finds room it
        // must be in a different band, and when it does not it must be suppressed rather than drawn
        // over the station label.
        if (actuals.reason == "drawn") {
            val actualsBox = requireNotNull(actuals.box)
            val actualsRect = GraphRect(actualsBox.left, actualsBox.top, actualsBox.right, actualsBox.bottom)
            val dominantBox = requireNotNull(dominant.box)
            val dominantRect = GraphRect(dominantBox.left, dominantBox.top, dominantBox.right, dominantBox.bottom)
            assertTrue(
                "actuals-source label overlaps the dominant station label. " +
                    "actuals=$actualsRect dominant=$dominantRect",
                !actualsRect.intersects(dominantRect),
            )
        }
    }

    /**
     * Pixel 7 Pro 2026-11-06: with nothing over the top strip the label lands there, under the
     * RemoteViews header row. Given the header band as a ceiling it must stay below it.
     */
    @Test
    fun `actuals source label stays below the header band`() {
        val unbounded = render().actualsSource
        println("unbounded: reason=${unbounded.reason} box=${unbounded.box} lines=${unbounded.lines}")
        val headerBottom = 120f
        assertTrue(
            "fixture must reproduce the bug: without a ceiling the label sits in the header band. box=${unbounded.box}",
            requireNotNull(unbounded.box).top < headerBottom,
        )

        val bounded = render(headerBandBottomPx = headerBottom).actualsSource
        println("bounded: reason=${bounded.reason} box=${bounded.box} lines=${bounded.lines} scale=${bounded.fontScale}")
        assertEquals(headerBottom, bounded.topPx)
        if (bounded.reason == "drawn") {
            assertTrue(
                "label drawn under the header band. box=${bounded.box} headerBottom=$headerBottom",
                requireNotNull(bounded.box).top >= headerBottom,
            )
        } else {
            assertEquals("no_empty_band", bounded.reason)
        }
    }

    @Test
    fun `actuals source label is omitted when the header band leaves no room`() {
        val debug = render(headerBandBottomPx = 499f).actualsSource
        assertEquals("no_empty_band", debug.reason)
        assertEquals(null, debug.box)
        assertTrue(debug.lines.isEmpty())
    }

    /**
     * Fold 4 2026-11-06: `knuq 71.6° @ 2:35 pm` drew at baseline 25 px under a 19 px header band,
     * across the header's "-3.5 from yest". The dominant-station label shares the header ceiling.
     */
    @Test
    fun `dominant station label stays below the header band`() {
        val unbounded = render(dominantStationLabel = dominantLabel(), actualsSourceLabel = null).dominant
        println("dominant unbounded: reason=${unbounded.reason} box=${unbounded.box}")
        val headerBottom = 120f
        assertTrue(
            "fixture must reproduce the bug: without a ceiling the label sits in the header band. box=${unbounded.box}",
            requireNotNull(unbounded.box).top < headerBottom,
        )

        val bounded =
            render(dominantStationLabel = dominantLabel(), actualsSourceLabel = null, headerBandBottomPx = headerBottom).dominant
        println("dominant bounded: reason=${bounded.reason} box=${bounded.box} topPx=${bounded.topPx}")
        assertEquals(headerBottom, bounded.topPx)
        assertEquals("drawn", bounded.reason)
        assertTrue(
            "dominant label drawn under the header band. box=${bounded.box} headerBottom=$headerBottom",
            requireNotNull(bounded.box).top >= headerBottom,
        )
    }

    @Test
    fun `dominant station label is omitted when the header band leaves no room`() {
        val debug = render(dominantStationLabel = dominantLabel(), headerBandBottomPx = 499f).dominant
        assertEquals("no_empty_band", debug.reason)
        assertEquals(null, debug.box)
    }
}
