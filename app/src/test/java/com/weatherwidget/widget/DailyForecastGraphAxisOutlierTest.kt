package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Axis backstop for the `-100°F` sentinel (incident 2026-07-28, plan `260728c`).
 *
 * `snapshotHigh`/`snapshotLow`/`ghostLineHigh` feed the y-axis range but are never printed as text,
 * so a bad value in one of them is invisible as a number while still rescaling every column through
 * `tempToY`. On the day of the incident that turned today's bar into a full-height streak and
 * squashed the other nine days into ~100px stubs, while each printed label read perfectly healthy.
 *
 * The fixture is the exact ten-day column set from the reported screenshot. Assertions are on **dp
 * geometry only** — Robolectric has no font engine (text measures ~0 wide) and renderer paint colors
 * come back as 0 under the test shadow, so neither is worth asserting on.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyForecastGraphAxisOutlierTest {

    private lateinit var context: Context

    private val widthPx = 1000
    private val heightPx = 400

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Highs/lows as displayed in the 2026-07-28 screenshot, oldest column first. */
    private val observed = listOf(
        72.5f to 58.7f,  // Sun (past)
        72.8f to 56.8f,  // Mon (past)
        78.0f to 58.0f,  // Today
        80.0f to 58.0f,  // Wed
        81.0f to 57.0f,  // Thu
        85.0f to 58.0f,  // Fri  <- warmest
        84.0f to 59.0f,  // Sat
        84.0f to 59.0f,  // Sun
        83.0f to 59.0f,  // Mon
        82.0f to 59.0f,  // Tue
    )

    private fun days(todaySnapshotLow: Float?): List<DailyForecastGraphRenderer.DayData> {
        val start = LocalDate.of(2026, 7, 26)
        return observed.mapIndexed { i, (high, low) ->
            val isToday = i == 2
            DailyForecastGraphRenderer.DayData(
                date = start.plusDays(i.toLong()),
                label = if (isToday) "Today" else "D$i",
                solidLineHigh = high,
                solidLineLow = low,
                isToday = isToday,
                isPast = i < 2,
                // The poisoned column: a snapshot low that no label will ever print.
                snapshotHigh = if (isToday) 75f else null,
                snapshotLow = if (isToday) todaySnapshotLow else null,
            )
        }
    }

    private fun render(days: List<DailyForecastGraphRenderer.DayData>): List<DailyForecastGraphRenderer.BarDrawnDebug> {
        val results = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        val bitmap = runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = widthPx,
                heightPx = heightPx,
                onBarDrawn = { results.add(it) },
                useCelsius = false,
            )
        }
        assertNotNull(bitmap)
        return results
    }

    /**
     * The invariant that actually broke: a bar must stay inside the graph area.
     *
     * In the screenshot the poisoned bar ran from the top of the graph, past the icon row, past the
     * low label, and through the `Sun/Mon/Today/Wed` day-label band. Excluding the sentinel from the
     * axis range is not by itself enough to stop that — the bar is still *drawn* at `tempToY(-100)`,
     * which measured 1585px on this 400px canvas before `tempToY` clamped to the graph area.
     *
     * Containment is the assertion rather than a bar-height fraction: the clamp legitimately leaves
     * the poisoned bar spanning the full graph height, and that is fine. It is confined to its own
     * cell, and the DAO read guard is what stops the value existing in the first place.
     */
    @Test
    fun `sentinel snapshot low cannot draw a bar outside the graph area`() {
        val bars = render(days(todaySnapshotLow = -100f))

        assertTrue("expected bars to be drawn", bars.isNotEmpty())
        val lowest = bars.maxOf { it.lowY }
        assertTrue(
            "no bar may reach into or past the day-label band at the bottom of the ${heightPx}px" +
                " canvas; lowest bar edge was ${lowest}px",
            lowest < heightPx,
        )
        assertTrue(
            "no bar may be drawn above the canvas; highest was ${bars.minOf { it.highY }}px",
            bars.minOf { it.highY } >= 0f,
        )
    }

    /**
     * Geometry with the sentinel present must match geometry without it. This is the strong form:
     * the outlier is not merely survivable, it is *inert* — it must not move a single bar.
     */
    @Test
    fun `sentinel does not change the geometry of any other column`() {
        val clean = render(days(todaySnapshotLow = 58f)).associateBy { it.date to it.barType }
        val poisoned = render(days(todaySnapshotLow = -100f)).associateBy { it.date to it.barType }

        // The snapshot bar itself legitimately differs (its own bottom is the bad value), so compare
        // every OTHER bar — those are the ones a rescaled axis would silently drag.
        val comparable = clean.keys.intersect(poisoned.keys).filterNot { it.second.contains("SNAPSHOT") }
        assertTrue("expected comparable bars across both renders", comparable.isNotEmpty())

        for (key in comparable) {
            val a = clean.getValue(key)
            val b = poisoned.getValue(key)
            assertTrue(
                "bar $key moved when a sentinel was present: highY ${a.highY} -> ${b.highY}",
                kotlin.math.abs(a.highY - b.highY) < 0.5f,
            )
            assertTrue(
                "bar $key resized when a sentinel was present: lowY ${a.lowY} -> ${b.lowY}",
                kotlin.math.abs(a.lowY - b.lowY) < 0.5f,
            )
        }
    }

    /**
     * Guards the opposite failure: an over-eager filter that discards legitimate cold weather. -40°F
     * is a real temperature (and inside the plausibility bounds), so it must still set the axis.
     */
    @Test
    fun `legitimately cold snapshot still participates in the axis range`() {
        val mild = render(days(todaySnapshotLow = 58f)).first { it.barType == "TODAY_SNAPSHOT" }
        val cold = render(days(todaySnapshotLow = -40f)).first { it.barType == "TODAY_SNAPSHOT" }

        assertTrue(
            "a real -40F low must produce a taller snapshot bar than a 58F one," +
                " got ${cold.lowY - cold.highY} vs ${mild.lowY - mild.highY}",
            (cold.lowY - cold.highY) > (mild.lowY - mild.highY),
        )
    }
}
