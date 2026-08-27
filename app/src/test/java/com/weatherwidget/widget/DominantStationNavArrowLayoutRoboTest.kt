package com.weatherwidget.widget

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.NavArrowGeometry
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The integration test that spans the *layout* as well as the renderer, and so is the only one that
 * can catch this bug end to end.
 *
 * `DominantStationNavArrowRoboTest` proves the renderer honours the arrow rectangle, but its oracle
 * is `NavArrowGeometry` — the same code under test — so a wrong constant passes it. This test never
 * consults `NavArrowGeometry` for the answer. It inflates the real `widget_weather`, measures and
 * lays it out, reads `nav_left`/`nav_right` where the launcher would actually put them, and asks
 * whether the label the renderer drew lands under one of them.
 *
 * That is the loop the 2026-08-18 bug slipped through: every piece was individually defensible and
 * nothing compared the bitmap's ink with the view hierarchy on top of it.
 *
 * The bitmap is rendered at exactly `graph_view`'s measured size so `scaleType="fitCenter"` is the
 * identity — see the warning at `widget_weather.xml:1680`. Render at any other size and the fitCenter
 * transform has to be applied before these rectangles can be compared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@Category(LongDuration::class)
class DominantStationNavArrowLayoutRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: ViewGroup

    private val start: LocalDateTime = LocalDateTime.of(2026, 8, 18, 0, 0)

    @Before
    fun setup() {
        root = LayoutInflater.from(context).inflate(R.layout.widget_weather, null) as ViewGroup
        listOf(R.id.graph_view, R.id.nav_left, R.id.nav_right).forEach {
            root.findViewById<View>(it).visibility = View.VISIBLE
        }
        val density = context.resources.displayMetrics.density
        val widthPx = (WIDGET_WIDTH_DP * density).toInt()
        val heightPx = (WIDGET_HEIGHT_DP * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
    }

    /** Bounds of [view] in [root]'s coordinate space, accumulating each ancestor's offset. */
    private fun rectInRoot(view: View): Rect {
        var dx = 0
        var dy = 0
        var current: View = view
        while (current !== root) {
            dx += current.left
            dy += current.top
            current = current.parent as View
        }
        return Rect(dx, dy, dx + view.width, dy + view.height)
    }

    private fun hours(): List<HourData> =
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

    @Test
    fun `dominant station label does not land under the laid-out nav arrows`() {
        val graphRect = rectInRoot(root.findViewById(R.id.graph_view))
        assertTrue("graph_view must have been laid out: $graphRect", graphRect.width() > 0)

        var debug: DominantStationDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours(),
            // Rendered at graph_view's own size, so fitCenter is the identity and bitmap
            // coordinates translate to root coordinates by graphRect.left/top alone.
            widthPx = graphRect.width(),
            heightPx = graphRect.height(),
            currentTime = start.plusHours(10),
            numColumns = 5,
            useCelsius = false,
            dominantStationLabel = requireNotNull(
                DominantStationLabel.formatLabelText(
                    stationId = "KNUQ",
                    rawTemp = 64.4f,
                    useCelsius = false,
                    lastReadingMs = start.plusHours(10)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    zoneId = ZoneId.systemDefault(),
                ),
            ),
            navArrowVisibility = NavArrowGeometry.Visibility.BOTH,
            onDominantStationPlaced = { debug = it },
        )

        val placed = requireNotNull(debug) { "renderer must report a dominant-station outcome" }
        assertEquals(
            "the label must actually be drawn, or this test proves nothing. debug=$placed",
            "drawn",
            placed.reason,
        )
        val box = requireNotNull(placed.box)

        // Bitmap -> root coordinates.
        val labelInRoot = Rect(
            graphRect.left + box.left.toInt(),
            graphRect.top + box.top.toInt(),
            graphRect.left + box.right.toInt(),
            graphRect.top + box.bottom.toInt(),
        )

        listOf(R.id.nav_left to "nav_left", R.id.nav_right to "nav_right").forEach { (id, name) ->
            val arrow = rectInRoot(root.findViewById<ImageButton>(id))
            assertTrue(
                "$name was not laid out; the assertion below would be vacuous. arrow=$arrow",
                arrow.width() > 0 && arrow.height() > 0,
            )
            assertTrue(
                "dominant-station label '${placed.text}' is drawn under $name. " +
                    "label=$labelInRoot $name=$arrow graph_view=$graphRect",
                !Rect.intersects(labelInRoot, arrow),
            )
        }
    }

    private companion object {
        /** A 4x2-cell widget: wide enough for the hourly graph, tall enough for a real plot band. */
        const val WIDGET_WIDTH_DP = 320f
        const val WIDGET_HEIGHT_DP = 180f
    }
}
