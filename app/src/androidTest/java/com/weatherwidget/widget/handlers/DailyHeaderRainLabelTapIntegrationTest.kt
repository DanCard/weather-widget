package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.TestLocations
import com.weatherwidget.testutil.WidgetStateTestUtils
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tapping the rain % in the daily header opens the hourly rain graph.
 *
 * The daily header is painted into graph_view's bitmap, with invisible RemoteViews tap zones laid
 * out over it, so the two can drift apart without any routing test noticing. On a Fold 4
 * (2026-11-01) the painted "94%" spanned x=445–535 while current_temp_zone reached 550: the whole
 * label fired ACTION_TOGGLE_VIEW and opened the temperature graph, because INVISIBLE delta views no
 * delta was painted for still took up width in the row.
 *
 * So this binds a real widget into a real AppWidgetHost, finds the label by its pixels in the
 * pushed bitmap, resolves each tap the way touch dispatch does, and clicks whatever that hits.
 */
@RunWith(AndroidJUnit4::class)
class DailyHeaderRainLabelTapIntegrationTest : IsolatedIntegrationTest("daily_header_rain_tap") {

    private lateinit var stateManager: WidgetStateManager
    private lateinit var host: AppWidgetHost
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val now = LocalDate.now().atTime(12, 0)

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        grantBindPermission()
        runBlocking { db.forecastDao().insertAll(dailyForecasts(now.toLocalDate())) }
        host = AppWidgetHost(context, TEST_HOST_ID)
    }

    @After
    override fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { host.stopListening() }
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            stateManager.clearWidgetState(appWidgetId)
            host.deleteAppWidgetId(appWidgetId)
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
        super.cleanup()
    }

    @Test
    fun tappingRainLabel_onWideWidgetWithoutDelta_opensRainGraph() =
        assertRainLabelOpensRainGraph(widthDp = FOLD_WIDTH_DP, heightDp = FOLD_HEIGHT_DP, deltaFromYesterday = null)

    @Test
    fun tappingRainLabel_onWideWidgetWithDelta_opensRainGraph() =
        assertRainLabelOpensRainGraph(widthDp = FOLD_WIDTH_DP, heightDp = FOLD_HEIGHT_DP, deltaFromYesterday = 2.2f)

    @Test
    fun tappingRainLabel_onPhoneWidgetWithoutDelta_opensRainGraph() =
        assertRainLabelOpensRainGraph(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, deltaFromYesterday = null)

    @Test
    fun tappingRainLabel_onPhoneWidgetWithDelta_opensRainGraph() =
        assertRainLabelOpensRainGraph(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, deltaFromYesterday = 2.2f)

    private fun assertRainLabelOpensRainGraph(
        widthDp: Int,
        heightDp: Int,
        deltaFromYesterday: Float?,
    ) = runBlocking {
        val hostView = bindWidget(widthDp, heightDp)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val tap = awaitPaintedRainLabel(hostView, widthDp, heightDp, deltaFromYesterday)
        Log.d(TAG, "widthDp=$widthDp delta=$deltaFromYesterday $tap")

        tap.hits.forEach { (x, hit) ->
            assertEquals(
                "Tap at x=$x on the painted label must land on the rain zone: $tap",
                "precip_touch_zone",
                hit,
            )
        }

        var clicked = false
        instrumentation.runOnMainSync {
            val root = hostView.findViewById<View>(R.id.widget_root)
            clicked = topClickableAt(root, tap.hits.keys.elementAt(1), tap.label.centerY)!!.performClick()
        }
        assertTrue("performClick should run the bound PendingIntent", clicked)

        WidgetStateTestUtils.waitForViewMode(context, stateManager, appWidgetId, ViewMode.PRECIPITATION)
        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(appWidgetId))
    }

    /** The launcher's add: allocate, bind at this size, and host the widget's view. */
    private suspend fun bindWidget(widthDp: Int, heightDp: Int): AppWidgetHostView {
        val manager = AppWidgetManager.getInstance(context)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        appWidgetId = host.allocateAppWidgetId()
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        assertTrue(
            "bindAppWidgetIdIfAllowed refused widget $appWidgetId",
            manager.bindAppWidgetIdIfAllowed(
                appWidgetId,
                ComponentName(context, WeatherWidgetProvider::class.java),
                options,
            ),
        )

        lateinit var hostView: AppWidgetHostView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.startListening()
            hostView = host.createView(context, appWidgetId, manager.getAppWidgetInfo(appWidgetId))
            hostView.setPadding(0, 0, 0, 0)
        }

        // Binding also starts the provider's own first paint; let it land so it cannot replace ours.
        val deadline = System.currentTimeMillis() + PAINT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline &&
            db.appLogDao().getLogsByTag("WIDGET_RENDER_OK", 50).none { it.message.contains("widget=$appWidgetId ") }
        ) {
            delay(POLL_INTERVAL_MS)
        }
        return hostView
    }

    /** Pushes the daily render and returns the first complete layout showing the rain label. */
    private suspend fun awaitPaintedRainLabel(
        hostView: AppWidgetHostView,
        widthDp: Int,
        heightDp: Int,
        deltaFromYesterday: Float?,
    ): LabelTaps {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()
        val deadline = System.currentTimeMillis() + PAINT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            pushDailyRender(deltaFromYesterday)
            delay(PUSH_SETTLE_MS)
            var taps: LabelTaps? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                hostView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                )
                hostView.layout(0, 0, widthPx, heightPx)
                taps = hostView.findViewById<View>(R.id.widget_root)?.let(::labelTaps)
            }
            taps?.let { return it }
        }
        fail("Widget $appWidgetId never showed a daily graph with the rain label")
        error("unreachable")
    }

    private suspend fun pushDailyRender(deltaFromYesterday: Float?) {
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = AppWidgetManager.getInstance(context),
            appWidgetId = appWidgetId,
            weatherData = WeatherData(
                weatherList = dailyForecasts(now.toLocalDate()),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = hourlyForecasts(),
            ),
            observationData = ObservationData(
                lastObservedTemp = 62f,
                observedAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                deltaFromYesterday = deltaFromYesterday,
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )
    }

    /** Where taps across the painted label land, or null while no rain label is on screen. */
    private fun labelTaps(root: View): LabelTaps? {
        val graphView = root.findViewById<ImageView>(R.id.graph_view) ?: return null
        if (graphView.visibility != View.VISIBLE) return null
        val label = paintedRainLabel(graphView) ?: return null
        val inset = (label.right - label.left) / 4
        val xs = listOf(label.left + inset, (label.left + label.right) / 2, label.right - inset)
        fun bounds(id: Int) = root.findViewById<View>(id).let { "${it.left}..${it.right}" }
        return LabelTaps(
            label = label,
            tempZone = bounds(R.id.current_temp_zone),
            rainZone = bounds(R.id.precip_touch_zone),
            hits = xs.associateWith { x -> topClickableAt(root, x, label.centerY)?.let(::idName) },
        )
    }

    /**
     * The label's ink in [graphView]'s parent coordinates, found by its exact colour: the topmost
     * band of rows holding that colour is the header line (day labels start below it), and the
     * last run across that band is the % (the weather icon's blue drops, if any, come first).
     */
    private fun paintedRainLabel(graphView: ImageView): PaintedLabel? {
        val bitmap = (graphView.drawable as? BitmapDrawable)?.bitmap ?: return null
        val density = context.resources.displayMetrics.density
        val stripRows = 0 until minOf((HEADER_STRIP_DP * density).toInt(), bitmap.height)
        val inkRows = stripRows.filter { y -> (0 until bitmap.width).any { x -> isRainInk(bitmap.getPixel(x, y)) } }
        if (inkRows.isEmpty()) return null
        val band = inkRows.zipWithNext().takeWhile { (a, b) -> b - a <= 2 }.map { it.second } + inkRows.first()
        val columns = (0 until bitmap.width).filter { x -> band.any { y -> isRainInk(bitmap.getPixel(x, y)) } }

        val maxGlyphGap = (GLYPH_GAP_DP * density).toInt()
        var first = columns.first()
        columns.zipWithNext().forEach { (a, b) -> if (b - a > maxGlyphGap) first = b }

        val points = floatArrayOf(first.toFloat(), band.min().toFloat(), columns.last().toFloat(), band.max().toFloat())
        graphView.imageMatrix.mapPoints(points)
        return PaintedLabel(
            left = graphView.left + points[0].roundToInt(),
            right = graphView.left + points[2].roundToInt(),
            centerY = graphView.top + ((points[1] + points[3]) / 2f).roundToInt(),
        )
    }

    private fun isRainInk(pixel: Int): Boolean =
        Color.alpha(pixel) > 200 &&
            abs(Color.red(pixel) - Color.red(RAIN_LABEL_COLOR)) < COLOR_TOLERANCE &&
            abs(Color.green(pixel) - Color.green(RAIN_LABEL_COLOR)) < COLOR_TOLERANCE &&
            abs(Color.blue(pixel) - Color.blue(RAIN_LABEL_COLOR)) < COLOR_TOLERANCE

    /**
     * What a touch at ([x], [y]) reaches: like ViewGroup dispatch, the last-declared visible child
     * under the finger wins, and a non-clickable view passes the touch to what lies below it.
     * Coordinates are in [view]'s parent's space.
     */
    private fun topClickableAt(view: View, x: Int, y: Int): View? {
        if (view.visibility != View.VISIBLE) return null
        if (x < view.left || x >= view.right || y < view.top || y >= view.bottom) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                topClickableAt(view.getChildAt(index), x - view.left, y - view.top)?.let { return it }
            }
        }
        return view.takeIf { it.isClickable }
    }

    private fun idName(view: View): String =
        if (view.id == View.NO_ID) view.javaClass.simpleName else context.resources.getResourceEntryName(view.id)

    private fun hourlyForecasts(): List<HourlyForecastEntity> {
        val start = now.minusHours(8)
        val fetchedAt = System.currentTimeMillis()
        return (0..24).map { index ->
            HourlyForecastEntity(
                dateTime = start.plusHours(index.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = TestLocations.LAT,
                locationLon = TestLocations.LON,
                temperature = 60f + index,
                condition = "Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = RAIN_PERCENT,
                cloudCover = 90,
                fetchedAt = fetchedAt,
            )
        }
    }

    private fun dailyForecasts(today: LocalDate): List<ForecastEntity> {
        val fetchedAt = System.currentTimeMillis()
        return (-1L..5L).map { offset ->
            val day = today.plusDays(offset).toEpochDay() * MILLIS_PER_DAY
            ForecastEntity(
                targetDate = day,
                dateOfPrediction = day,
                locationLat = TestLocations.LAT,
                locationLon = TestLocations.LON,
                highTemp = 72f,
                lowTemp = 54f,
                condition = "Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = RAIN_PERCENT,
                fetchedAt = fetchedAt,
            )
        }
    }

    private data class PaintedLabel(val left: Int, val right: Int, val centerY: Int)

    private data class LabelTaps(
        val label: PaintedLabel,
        val tempZone: String,
        val rainZone: String,
        val hits: Map<Int, String?>,
    )

    /** The instrumentation app needs BIND_APPWIDGET to bind without the system's dialog. */
    private fun grantBindPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "DailyHeaderRainLabelTap"
        const val TEST_HOST_ID = 0x7E58
        const val PAINT_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 250L
        const val PUSH_SETTLE_MS = 500L

        // The Fold 4 widget that failed, and a typical phone widget.
        const val FOLD_WIDTH_DP = 662
        const val FOLD_HEIGHT_DP = 465
        const val PHONE_WIDTH_DP = 360
        const val PHONE_HEIGHT_DP = 340

        // DailyForecastGraphRenderer.HeaderRenderData.precipColor.
        val RAIN_LABEL_COLOR = 0xFF5AC8FA.toInt()
        const val COLOR_TOLERANCE = 12

        // Below the peak-hour threshold, so no blue "4pm" label can join the header ink.
        const val RAIN_PERCENT = 25
        const val HEADER_STRIP_DP = 40f
        const val GLYPH_GAP_DP = 6f
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
