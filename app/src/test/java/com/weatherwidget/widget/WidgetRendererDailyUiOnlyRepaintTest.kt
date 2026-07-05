package com.weatherwidget.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.mockAppWidgetManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Regression coverage for the stuck "Loading…" daily widget.
 *
 * The DAILY view skips the expensive rebuild on opportunistic UI-only repaints (the ~2-min
 * now-tracking tick). That skip is only safe once a real graph has been painted in the current
 * process — after a force-stop / fresh process / app update the widget still shows the "Loading…"
 * placeholder, and the first update is often UI-only. Skipping then stranded the widget on
 * "Loading…" (graph bitmap never set; observed as `views_bitmap_memory=0` in `dumpsys appwidget`).
 *
 * The fix only skips when the widget has already been fully painted this process
 * ([WidgetRenderer.shouldSkipDailyUiOnlyRepaint]); otherwise it falls through to a full paint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class WidgetRendererDailyUiOnlyRepaintTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetRenderer.resetPaintTrackingForTest()
    }

    @After
    fun tearDown() {
        WidgetRenderer.resetPaintTrackingForTest()
    }

    // ── Pure decision logic ───────────────────────────────────────────────────────────────────

    @Test
    fun `ui-only daily repaint is skipped only after a full paint this process`() {
        // The bug: a fresh process hadn't painted yet, but a UI-only tick skipped anyway.
        assertFalse(
            "must NOT skip before any full paint (would strand on Loading)",
            WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly = true, alreadyPaintedThisProcess = false),
        )
        // The optimization: once a real graph exists, opportunistic ticks may skip.
        assertTrue(
            WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly = true, alreadyPaintedThisProcess = true),
        )
        // A full (non-UI-only) render always paints, regardless of prior state.
        assertFalse(WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly = false, alreadyPaintedThisProcess = false))
        assertFalse(WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly = false, alreadyPaintedThisProcess = true))
    }

    // ── Integration: drives the real updateWidgetWithData render path ──────────────────────────

    @Test
    fun `fresh-process ui-only daily repaint paints the graph instead of leaving Loading`() = runBlocking {
        val id = 9201
        prepareDailyWidget(id)
        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = id, widthDp = 300, heightDp = 200)

        assertFalse("precondition: nothing painted yet this process", WidgetRenderer.hasDailyPaintedForTest(id))

        WidgetRenderer.updateWidgetWithData(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = id,
            weatherList = sampleWeather(),
            uiOnly = true,
        )

        // The fix: the UI-only tick fell through to a real paint rather than returning early.
        assertTrue("fresh-process UI-only repaint must push a RemoteViews (not skip)", viewsSlot.isCaptured)
        assertTrue("widget should now be marked fully painted this process", WidgetRenderer.hasDailyPaintedForTest(id))

        // And what was pushed is a real daily view, not the "Loading…" placeholder.
        val applied = viewsSlot.captured.apply(context, FrameLayout(context) as ViewGroup)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.graph_view).visibility)
        val placeholderLow = applied.findViewById<TextView>(R.id.day2_low)?.text?.toString()
        assertNotEquals("Loading...", placeholderLow)
    }

    @Test
    fun `ui-only daily repaint skips once the graph has already been painted this process`() = runBlocking {
        val id = 9202
        prepareDailyWidget(id)
        // Simulate "already painted in this process" (e.g. the onUpdate full render already ran).
        WidgetRenderer.markDailyPaintedForTest(id)
        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = id, widthDp = 300, heightDp = 200)

        WidgetRenderer.updateWidgetWithData(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = id,
            weatherList = sampleWeather(),
            uiOnly = true,
        )

        // The optimization still holds: no expensive rebuild / push for an opportunistic tick.
        assertFalse("already-painted UI-only repaint should skip (no push)", viewsSlot.isCaptured)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    private fun prepareDailyWidget(id: Int) {
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(id)
        stateManager.setViewMode(id, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API),
        )
        WidgetRenderer.resetPaintTrackingForTest()
    }

    private fun sampleWeather(): List<ForecastEntity> {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return listOf(createWeather(today, 70f, 55f), createWeather(tomorrow, 72f, 56f))
    }

    private fun createWeather(date: String, highTemp: Float, lowTemp: Float): ForecastEntity =
        ForecastEntity(
            targetDate = dateEpoch(date),
            dateOfPrediction = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = 1L,
        )
}
