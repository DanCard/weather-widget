package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.buildHourDataList
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Settings → "Hourly Zoom" set to 8 hours must draw a graph covering 8 hours.
 *
 * **The assertion is elapsed coverage, not column count.** That distinction is the whole bug: an 8h
 * setting drew hour marks `12a…7a` — eight marks, but the leftmost and rightmost only 7 hours apart,
 * so the curve carried 7 hours of weather. Counting marks says "8, correct"; counting hours says 7,
 * and hours are what the user reads off the graph. The first version of this test counted marks and
 * passed against a widget visibly showing 7 hours.
 *
 * Desktop settled the same question the same way in [NarrowZoomSpanDisplayedHoursTest] — it asserts
 * `cutoffMs - startMs == span hours` — and fixed its own renderer to include the end hour mark
 * (`hourlyPointsInWindow`, "6h rendered a 5h graph"). This is the Android end of that promise: the
 * SeekBar the user drags → [WidgetStateManager.getZoomWindow] (the seam every renderer reads) → the
 * hours the three hourly graphs actually build.
 *
 * The span both platforms share is emitted by `ActualTemperatureSeriesBuilder` in `:shared`; see
 * SharedNarrowSpanDisplayedHoursTest for the platform-independent half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class HourlyZoomSpanSettingRoboTest {

    private lateinit var context: Context

    /** Fixed so the window is pure arithmetic, and in the past so "closest hour" never forces a label. */
    private val centerTime = LocalDateTime.of(2026, 3, 15, 12, 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs()
    }

    @After
    fun tearDown() {
        clearTestPrefs()
    }

    @Test
    fun `settings 8 hours draws eight hours of weather in the narrow view`() {
        val window = setSpanInSettingsAndResolveNarrowWindow(spanHours = 8)

        // The window itself already promises 8: 4 back / 4 forward.
        assertEquals(8L, window.totalSpanHours)
        assertEquals(4L, window.backHours)
        assertEquals(4L, window.forwardHours)

        val hours = temperatureHours(window)

        // What the user counts: the distance between the first and last hour on the axis.
        assertDrawnCoverage(expectedHours = 8, hours = hours)
        // 8 hours of coverage needs 9 hour marks — both edges are part of the view.
        assertEquals(
            listOf("8a", "9a", "10a", "11a", "12p", "1p", "2p", "3p", "4p"),
            hours.map(HourData::label),
        )
        assertEquals(centerTime.minusHours(4), hours.first().dateTime)
        assertEquals(centerTime.plusHours(4), hours.last().dateTime)
    }

    @Test
    fun `every configurable span draws exactly the hours it promises`() {
        // 4..8 rather than 8 alone: an off-by-one is invisible at a single span (any constant window
        // passes one case), and the SeekBar's whole range is a shipped promise.
        (HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS).forEach { span ->
            val window = setSpanInSettingsAndResolveNarrowWindow(spanHours = span)
            assertDrawnCoverage(expectedHours = span, hours = temperatureHours(window))
        }
    }

    @Test
    fun `settings 8 hours draws eight hours on the precip and cloud graphs too`() {
        // "Narrow view" is a zoom stage, not one graph — all three hourly modes owe the same span.
        val window = setSpanInSettingsAndResolveNarrowWindow(spanHours = 8)
        val expected = listOf("8a", "9a", "10a", "11a", "12p", "1p", "2p", "3p", "4p")

        val precip = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = centerTime,
            numColumns = WIDE_WIDGET_COLUMNS,
            displaySource = WeatherSource.NWS,
            zoom = window,
        )
        val cloud = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = centerTime,
            numColumns = WIDE_WIDGET_COLUMNS,
            displaySource = WeatherSource.NWS,
            zoom = window,
        )

        assertEquals(expected, precip.map(PrecipitationGraphRenderer.PrecipHourData::label))
        assertEquals(expected, cloud.map(CloudCoverGraphRenderer.CloudHourData::label))
    }

    @Test
    fun `narrow widget at 8 hours still covers eight hours and thins only the footer labels`() {
        // A 2–6 column widget can't fit 9 <hour><icon> footer groups, so the cadence halves
        // (HourlyZoomRules.narrowWidgetLabelInterval). Fewer *labels* is the intended look there; the
        // hours covered must not shrink, because that is what the setting controls.
        val window = setSpanInSettingsAndResolveNarrowWindow(spanHours = 8)

        val hours = temperatureHours(window, numColumns = NARROW_WIDGET_COLUMNS)

        assertDrawnCoverage(expectedHours = 8, hours = hours)
        assertEquals(
            listOf("8a", "10a", "12p", "2p", "4p"),
            hours.filter { it.showLabel }.map(HourData::label),
        )
    }

    @Test
    fun `settings shows the chosen span and repaints widgets so the display cannot lag the setting`() {
        launchSettings { activity ->
            val app = shadowOf(activity.application)
            val refreshesBefore = app.broadcastIntents.count { it.action == WidgetActions.ACTION_REFRESH }

            dragSeekBarTo(activity, spanHours = 8)

            assertEquals(
                "8 hours",
                activity.findViewById<TextView>(R.id.hourly_zoom_value).text.toString(),
            )
            // A persisted span the widget never repaints for reads to the user as the setting not
            // taking effect, so the repaint is part of "matches the display".
            val refreshesAfter = app.broadcastIntents.count { it.action == WidgetActions.ACTION_REFRESH }
            assertTrue("changing the span must repaint widgets", refreshesAfter > refreshesBefore)
        }
    }

    /**
     * The user-visible promise: the drawn axis runs [expectedHours] hours end to end. Asserted on
     * the hour marks themselves rather than their count, so an axis that grew a column without
     * covering more time cannot pass.
     */
    private fun assertDrawnCoverage(expectedHours: Int, hours: List<HourData>) {
        val drawn = Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
        assertEquals(
            "a ${expectedHours}h setting must draw ${expectedHours}h of weather, " +
                "first=${hours.first().label} last=${hours.last().label} marks=${hours.size}",
            expectedHours.toLong(),
            drawn,
        )
    }

    /**
     * Drives the Settings control to [spanHours] the way a user does, then resolves the widget's
     * NARROW window through the same accessor the renderers call.
     */
    private fun setSpanInSettingsAndResolveNarrowWindow(spanHours: Int): ZoomWindow {
        launchSettings { activity -> dragSeekBarTo(activity, spanHours) }

        val stateManager = WidgetStateManager(context)
        assertEquals(
            "SeekBar release must persist the span",
            spanHours,
            stateManager.getNarrowZoomSpanHours(),
        )

        stateManager.setZoomLevel(WIDGET_ID, ZoomStage.NARROW)
        return stateManager.getZoomWindow(WIDGET_ID)
    }

    private fun launchSettings(block: (SettingsActivity) -> Unit) {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java))
            .onActivity(block)
    }

    /**
     * The SeekBar is 0..4 offset by [HourlyZoomRules.MIN_NARROW_SPAN_HOURS], and SettingsActivity
     * persists on release rather than on every progress tick — so the release callback is the step
     * that matters here, not the progress write.
     */
    private fun dragSeekBarTo(activity: SettingsActivity, spanHours: Int) {
        val seekBar = activity.findViewById<SeekBar>(R.id.hourly_zoom_seekbar)
        seekBar.progress = spanHours - HourlyZoomRules.MIN_NARROW_SPAN_HOURS
        shadowOf(seekBar).onSeekBarChangeListener!!.onStopTrackingTouch(seekBar)
    }

    private fun temperatureHours(
        zoom: ZoomWindow,
        numColumns: Int = WIDE_WIDGET_COLUMNS,
    ): List<HourData> = buildHourDataList(
        hourlyForecasts = sampleHourlyForecasts(),
        centerTime = centerTime,
        numColumns = numColumns,
        displaySource = WeatherSource.NWS,
        zoom = zoom,
    )

    private fun sampleHourlyForecasts(count: Int = 48): List<HourlyForecastEntity> {
        val base = LocalDateTime.of(2026, 3, 15, 0, 0)
        return (0 until count).map { hourIndex ->
            val dateTime = base.plusHours(hourIndex.toLong())
            HourlyForecastEntity(
                dateTime = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.42,
                locationLon = -122.08,
                temperature = 50f + hourIndex,
                condition = "Partly Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = 10 + hourIndex,
                cloudCover = 55 + hourIndex,
                fetchedAt = 1L,
            )
        }
    }

    private fun clearTestPrefs() {
        listOf("weather_widget_prefs", "widget_state_prefs", "weather_prefs").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        const val WIDGET_ID = 4242

        /** Above HourlyGraphDefaults.NARROW_WIDGET_MAX_COLUMNS: no footer label thinning. */
        const val WIDE_WIDGET_COLUMNS = 9

        /** At or below it: the footer thins itself. */
        const val NARROW_WIDGET_COLUMNS = 3
    }
}
