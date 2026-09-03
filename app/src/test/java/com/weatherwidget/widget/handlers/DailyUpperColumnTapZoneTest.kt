package com.weatherwidget.widget.handlers

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The upper half of a day column — above the nav chevrons — always opens the temperature graph,
 * while the same column's lower half keeps the icon-and-precip routing.
 *
 * The day used here is deliberately the worst case for the old behaviour: a rain icon at 100%,
 * which the lower zone still sends to the precipitation graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyUpperColumnTapZoneTest {

    private lateinit var context: Context
    private val now = LocalDateTime.of(2026, 3, 20, 12, 0)
    private val today: LocalDate = now.toLocalDate()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun rainyDays(): List<DailyForecastGraphRenderer.DayData> =
        listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Fri",
                solidLineHigh = 62f,
                solidLineLow = 48f,
                iconRes = R.drawable.ic_weather_rain,
                isRainy = true,
                isToday = true,
                columnIndex = 0,
                rainData = DailyForecastGraphRenderer.RainLabelData(dailyPrecipProbability = 100),
            ),
            DailyForecastGraphRenderer.DayData(
                date = today.plusDays(1),
                label = "Sat",
                solidLineHigh = 64f,
                solidLineLow = 50f,
                iconRes = R.drawable.ic_weather_rain,
                isRainy = true,
                columnIndex = 1,
                rainData = DailyForecastGraphRenderer.RainLabelData(dailyPrecipProbability = 100),
            ),
        )

    private fun renderZones(): View {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        DailyVisibilityManager.setGraphModeViews(views)
        DailyClickHandlerFactory.setupGraphDayClickHandlers(
            context = context,
            views = views,
            appWidgetId = 71,
            now = now,
            days = rainyDays(),
            lat = 37.42,
            lon = -122.08,
            displaySource = WeatherSource.NWS,
            numColumns = 2,
        )
        val root = FrameLayout(context)
        val applied = views.apply(context, root)
        root.addView(applied)
        // 400dp tall at Robolectric's default density (1x) — comfortably past the 44/140 bands, so
        // both halves have real height.
        applied.measure(
            MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
        )
        applied.layout(0, 0, 600, 400)
        return applied
    }

    private fun lastTargetView(applied: View, zoneId: Int): String? {
        applied.findViewById<View>(zoneId).performClick()
        val broadcasts = shadowOf(context as android.app.Application).broadcastIntents
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        return broadcasts.last().getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)
    }

    @Test
    fun `upper half of a 100 percent rain column opens temperature, lower half opens precipitation`() {
        val applied = renderZones()

        assertEquals(
            "lower half must keep condition routing",
            ViewMode.PRECIPITATION.name,
            lastTargetView(applied, R.id.graph_day1_zone),
        )
        assertEquals(
            "upper half must be unconditional",
            ViewMode.TEMPERATURE.name,
            lastTargetView(applied, R.id.graph_day1_top_zone),
        )
    }

    @Test
    fun `upper zone carries the date of its own column`() {
        val applied = renderZones()

        applied.findViewById<View>(R.id.graph_day2_top_zone).performClick()
        val intent = shadowOf(context as android.app.Application).broadcastIntents.last()
        assertEquals(today.plusDays(1).toString(), intent.getStringExtra("date"))
        assertEquals(ViewMode.TEMPERATURE.name, intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
    }

    @Test
    fun `the split lands on the widget midpoint where the chevrons are centred`() {
        val applied = renderZones()
        val height = applied.height

        val upper = applied.findViewById<View>(R.id.graph_day_top_zones)
        val lower = applied.findViewById<View>(R.id.graph_day_bottom_zones)

        val upperBottom = upper.bottomInRoot()
        val lowerTop = lower.topInRoot()
        assertEquals("rows must meet with no gap", upperBottom, lowerTop)
        assertEquals("boundary must be the widget's vertical midpoint", height / 2, upperBottom)

        // nav_left centres its glyph on the same line, which is what makes the boundary visible.
        val nav = applied.findViewById<View>(R.id.nav_left)
        assertEquals(
            "chevron centre must sit on the boundary",
            upperBottom,
            (nav.topInRoot() + nav.bottomInRoot()) / 2,
        )
    }

    @Test
    fun `upper and lower zones of one column share the same horizontal span`() {
        val applied = renderZones()

        val upper = applied.findViewById<View>(R.id.graph_day1_top_zone)
        val lower = applied.findViewById<View>(R.id.graph_day1_zone)
        assertTrue("zone must have width", upper.width > 0)
        assertEquals(upper.leftInRoot(), lower.leftInRoot())
        assertEquals(upper.width, lower.width)
    }

    private fun View.topInRoot(): Int {
        var y = 0
        var v: View? = this
        while (v != null && v.parent is ViewGroup) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    private fun View.bottomInRoot(): Int = topInRoot() + height

    private fun View.leftInRoot(): Int {
        var x = 0
        var v: View? = this
        while (v != null && v.parent is ViewGroup) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }
}
