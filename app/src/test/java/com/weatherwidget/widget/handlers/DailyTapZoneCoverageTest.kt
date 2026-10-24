package com.weatherwidget.widget.handlers

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every slot the daily graph draws needs its own tap zone. The zones are equal-weight children of a
 * horizontal LinearLayout, so too few zones do not leave a dead strip on the right — they stretch
 * and drift off every column. 13 slots on 10 zones sent the left half of Today to yesterday on the
 * Fold inner screen (plans/261017-daily-tap-zones-fewer-than-drawn-slots.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class DailyTapZoneCoverageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no supported width draws more slots than there are tap zones`() {
        // A density change (CELL_WIDTH_DP) moves every width's slot count; this is where it shows.
        // At the old 60dp this already failed at 662dp — the Fold inner widget — with 10 zones.
        for (widthDp in 1..DailyClickHandlerFactory.MAX_SUPPORTED_WIDTH_DP) {
            val launcherColumns = com.weatherwidget.shared.graph.DailyLayoutConstants.columnsForWidthDp(widthDp)
            for (overlay in listOf(true, false)) {
                val decision =
                    DailyLargeTodayOverlayPolicy.resolve(
                        launcherColumns = launcherColumns,
                        launcherRows = DailyLargeTodayOverlayPolicy.MIN_LAUNCHER_ROWS,
                        useGraph = true,
                        todayVisible = overlay,
                    )
                val slots =
                    DailyClickHandlerFactory.visibleSlotCount(
                        numColumns = decision.displayColumns,
                        useLargeTodayOverlay = decision.enabled,
                        todayVisible = overlay,
                    )
                assertTrue(
                    "widthDp=$widthDp overlay=${decision.enabled} draws $slots slots but the layout has " +
                        "${DailyClickHandlerFactory.DAILY_TAP_ZONE_COUNT} tap zones per band",
                    slots <= DailyClickHandlerFactory.DAILY_TAP_ZONE_COUNT,
                )
            }
        }
    }

    @Test
    fun `the Fold inner widget fits`() {
        // 662dp, the widget that showed the bug: 13 slots then, 12 since the wide cell width
        // (plans/261017-daily-density-narrow-only.md) — still more than the old 10 zones.
        val slots = com.weatherwidget.shared.graph.DailyLayoutConstants.columnsForWidthDp(662)
        assertEquals(12, slots)
        assertTrue(slots <= DailyClickHandlerFactory.DAILY_TAP_ZONE_COUNT)
    }

    @Test
    fun `each band in the layout holds exactly the factory's zones, in order`() {
        val root = LayoutInflater.from(context).inflate(R.layout.widget_weather, null)
        val bands =
            mapOf(
                "upper" to DailyClickHandlerFactory.UPPER_ZONE_IDS,
                "middle" to DailyClickHandlerFactory.MIDDLE_ZONE_IDS,
                "bottom" to DailyClickHandlerFactory.BOTTOM_ZONE_IDS,
            )
        for ((band, ids) in bands) {
            assertEquals("$band zone list size", DailyClickHandlerFactory.DAILY_TAP_ZONE_COUNT, ids.size)
            val parent = root.findViewById<View>(ids.first()).parent as ViewGroup
            val childIds = (0 until parent.childCount).map { parent.getChildAt(it).id }
            assertEquals(
                "$band band: layout children must be exactly the factory's zones, left to right",
                ids,
                childIds,
            )
            ids.forEach { id ->
                val zone = root.findViewById<View>(id)
                val params = zone.layoutParams as android.widget.LinearLayout.LayoutParams
                assertEquals("$band zone ${context.resources.getResourceEntryName(id)} weight", 1f, params.weight)
            }
        }
    }
}
