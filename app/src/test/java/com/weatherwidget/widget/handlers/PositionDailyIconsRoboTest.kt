package com.weatherwidget.widget.handlers

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.util.HeaderButton
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.annotation.Config

/**
 * Verifies Android's right-anchored header button layout (`hourly_center_header_container` with
 * `layout_gravity="top|end"` and `MARGIN_END = rightCluster + gap`).
 */
@Category(LongDuration::class)
@Config(sdk = [34])
class PositionDailyIconsRoboTest : RobolectricTest() {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun inflateAndLayout(views: RemoteViews, widthDp: Int = 373, heightDp: Int = 220): View {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val applied = views.apply(context, FrameLayout(context))
        applied.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        applied.layout(0, 0, widthPx, heightPx)
        return applied
    }

    private fun rectInRoot(root: View, child: View): Rect {
        val rect = Rect(0, 0, child.width, child.height)
        var current: View? = child
        while (current != null && current !== root) {
            rect.offset(current.left, current.top)
            current = current.parent as? View
        }
        return rect
    }

    private fun measureDaily(widthDp: Int, wantHome: Boolean, wantMonths: Boolean = false): Pair<HeaderFit.Measured, HeaderFit.Plan> {
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = widthDp,
            headerScale = 1f,
            apiSourceText = "NWS",
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows = 3),
            currentTempText = "53.3°",
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = "-7.2",
            deltaLabelText = "from yest",
            precipText = "72%",
            precipTextSizeDp = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
            dateText = null,
            includeIcon = true,
            baseButtons = 2,
            wantHome = wantHome,
            wantMonths = wantMonths,
        )
        return measured to HeaderFit.resolve(measured)
    }

    @Test
    fun `container child order matches HeaderButtons ORDER`() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val root = inflateAndLayout(views)
        val container = root.findViewById<ViewGroup>(R.id.hourly_center_header_container)
        val childIds = (0 until container.childCount).map { container.getChildAt(it).id }
        assertEquals(
            HeaderButtons.ORDER.map { btn -> HeaderButtonViews.entries.first { it.button == btn }.zone },
            childIds,
        )
    }

    @Test
    fun `daily buttons sit right of center immediately left of api_touch_zone with no overlap`() {
        val widthDp = 373
        val density = context.resources.displayMetrics.density
        val (measured, fit) = measureDaily(widthDp = widthDp, wantHome = true)
        val shown = HeaderButtons.shown(HeaderButtons.daily(observationsInView = true, home = true, months = false), fit)

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        HeaderRemoteViewsBinder.bindApiSource(context, views, "NWS", HeaderConstants.apiTextSizeDp(3), scale = 1f)
        positionDailyIcons(views, measured, fit, shown, widthDp, density)

        val root = inflateAndLayout(views, widthDp = widthDp)
        val buttonsRect = rectInRoot(root, root.findViewById(R.id.hourly_center_header_container))
        val apiRect = rectInRoot(root, root.findViewById(R.id.api_touch_zone))

        assertFalse("buttons $buttonsRect must not overlap api_touch_zone $apiRect", Rect.intersects(buttonsRect, apiRect))
        assertTrue("buttons sit left of api_touch_zone", buttonsRect.right <= apiRect.left)
        assertTrue(
            "buttons center (${(buttonsRect.left + buttonsRect.right) / 2f}) sits right of widget center (${root.width / 2f})",
            (buttonsRect.left + buttonsRect.right) / 2f > root.width / 2f,
        )
    }

    @Test
    fun `adding home keeps observations and history pinned at the same x`() {
        val widthDp = 373
        val density = context.resources.displayMetrics.density

        fun layoutButtons(wantHome: Boolean): View {
            val (measured, fit) = measureDaily(widthDp = widthDp, wantHome = wantHome)
            val shown = HeaderButtons.shown(HeaderButtons.daily(observationsInView = true, home = wantHome, months = false), fit)
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            HeaderRemoteViewsBinder.bindApiSource(context, views, "NWS", HeaderConstants.apiTextSizeDp(3), scale = 1f)
            positionDailyIcons(views, measured, fit, shown, widthDp, density)
            return inflateAndLayout(views, widthDp = widthDp)
        }

        val withoutHome = layoutButtons(wantHome = false)
        val withHome = layoutButtons(wantHome = true)

        assertEquals(View.GONE, withoutHome.findViewById<View>(R.id.home_touch_zone).visibility)
        assertEquals(View.VISIBLE, withHome.findViewById<View>(R.id.home_touch_zone).visibility)

        val obsA = rectInRoot(withoutHome, withoutHome.findViewById(R.id.weather_stations_touch_zone))
        val obsB = rectInRoot(withHome, withHome.findViewById(R.id.weather_stations_touch_zone))
        val histA = rectInRoot(withoutHome, withoutHome.findViewById(R.id.forecast_comparison_activity_touch_zone))
        val histB = rectInRoot(withHome, withHome.findViewById(R.id.forecast_comparison_activity_touch_zone))
        val homeB = rectInRoot(withHome, withHome.findViewById(R.id.home_touch_zone))

        assertEquals("observations x pinned", obsA.left, obsB.left)
        assertEquals("history x pinned", histA.left, histB.left)
        assertTrue("home sits left of observations", homeB.right <= obsB.left)
    }

    @Test
    fun `switching daily to hourly keeps history pinned and hides unused buttons`() {
        val widthDp = 373
        val density = context.resources.displayMetrics.density
        val (measured, fit) = measureDaily(widthDp = widthDp, wantHome = false)

        val dailyViews = RemoteViews(context.packageName, R.layout.widget_weather)
        positionDailyIcons(dailyViews, measured, fit, listOf(HeaderButton.OBSERVATIONS, HeaderButton.COMPARISON), widthDp, density)
        val dailyRoot = inflateAndLayout(dailyViews, widthDp = widthDp)

        val hourlyViews = RemoteViews(context.packageName, R.layout.widget_weather)
        positionCenterIcons(
            hourlyViews,
            widthDp,
            density,
            measured,
            fit,
            listOf(HeaderButton.HOME, HeaderButton.OBSERVATIONS, HeaderButton.GRAPH_SELECTOR, HeaderButton.COMPARISON),
        )
        val hourlyRoot = inflateAndLayout(hourlyViews, widthDp = widthDp)

        val dailyHist = rectInRoot(dailyRoot, dailyRoot.findViewById(R.id.forecast_comparison_activity_touch_zone))
        val hourlyHist = rectInRoot(hourlyRoot, hourlyRoot.findViewById(R.id.forecast_comparison_activity_touch_zone))
        assertEquals("history right edge pinned across daily and hourly", dailyHist.right, hourlyHist.right)
        assertEquals(View.GONE, dailyRoot.findViewById<View>(R.id.graph_selector_touch_zone).visibility)
        assertEquals(View.VISIBLE, hourlyRoot.findViewById<View>(R.id.graph_selector_touch_zone).visibility)
    }

    @Test
    fun `toggling API source on wide Fold width moves neither buttons nor date`() {
        val widthDp = 662
        val density = context.resources.displayMetrics.density
        val allLabels = listOf("NWS", "Meteo", "Google")

        fun resolveFor(source: String, wantHome: Boolean, precip: String?): Pair<HeaderFit.Measured, HeaderFit.Plan> {
            val scale = HeaderWidthChecker.computeHeaderScale(
                context = context,
                widthDp = widthDp,
                apiSourceText = source,
                apiTextSizeDp = HeaderConstants.apiTextSizeDp(5),
                currentTempText = "51.7°",
                deltaText = "-6.5",
                precipText = precip,
                precipTextSizeDp = if (precip != null) HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP else null,
                currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
                allApiSourceLabels = allLabels,
            )
            val measured = HeaderWidthChecker.measureHeader(
                context = context,
                widthDp = widthDp,
                headerScale = scale,
                apiSourceText = source,
                apiTextSizeDp = HeaderConstants.apiTextSizeDp(5),
                currentTempText = "51.7°",
                currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
                deltaText = "-6.5",
                deltaLabelText = "from yest",
                precipText = precip,
                precipTextSizeDp = if (precip != null) HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP else null,
                dateText = "Wed 18",
                includeIcon = true,
                baseButtons = 2,
                wantHome = wantHome,
                wantMonths = false,
                allApiSourceLabels = allLabels,
            )
            return measured to HeaderFit.resolve(measured)
        }

        val (mNws, fitNws) = resolveFor("NWS", wantHome = false, precip = null)
        val (mMeteo, fitMeteo) = resolveFor("Meteo", wantHome = true, precip = "53%")

        assertEquals("rightCluster stays identical across API toggle", mNws.rightCluster, mMeteo.rightCluster, 0.01f)
        assertEquals(
            "buttons right edge stays pinned across API toggle",
            HeaderFit.buttonsSpan(mNws, fitNws)!!.second,
            HeaderFit.buttonsSpan(mMeteo, fitMeteo)!!.second,
            0.01f,
        )
        assertEquals(
            "date stays pinned to widget center across API toggle",
            HeaderFit.dateLeft(mNws, fitNws)!!,
            HeaderFit.dateLeft(mMeteo, fitMeteo)!!,
            0.01f,
        )
    }
}
