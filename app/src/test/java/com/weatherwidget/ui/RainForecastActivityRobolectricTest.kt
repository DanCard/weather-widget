package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.remote.weathernext.WeatherNextDomain
import com.weatherwidget.data.remote.weathernext.WeatherNextLayer
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.WeatherNextGoneStore
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class RainForecastActivityRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        WeatherNextGoneStore.clear(context)
    }

    private fun viewerIntent() = Intent(context, RainForecastActivity::class.java).apply {
        putExtra(RainForecastActivity.EXTRA_LAT, 37.42)
        putExtra(RainForecastActivity.EXTRA_LON, -122.09)
    }

    @Test
    fun `host gone within a day - explicit message, no Retry, Open-Meteo opens for the same place`() {
        // Remembered 404: the viewer answers from it without asking the host (no network in this test).
        WeatherNextGoneStore.markGone(context, 404)
        ActivityScenario.launch<RainForecastActivity>(viewerIntent()).onActivity { activity ->
            shadowOf(context.mainLooper).idle()

            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
            assertEquals(
                context.getString(R.string.sim_loop_error_gone, "ajoros.github.io", 404),
                activity.findViewById<TextView>(R.id.tv_error_message).text.toString(),
            )
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_error_retry).visibility)

            activity.findViewById<Button>(R.id.btn_use_open_meteo).performClick()
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(OpenMeteoRainLoopActivity::class.java.name, started.component?.className)
            assertEquals(37.42, started.getDoubleExtra(OpenMeteoRainLoopActivity.EXTRA_LAT, 0.0), 0.0)
            assertEquals(-122.09, started.getDoubleExtra(OpenMeteoRainLoopActivity.EXTRA_LON, 0.0), 0.0)
            assertTrue("WeatherNext viewer closes", activity.isFinishing)
        }
    }

    @Test
    fun `retryable errors keep Retry and say what failed`() {
        ActivityScenario.launch<RainForecastActivity>(viewerIntent()).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            val host = "ajoros.github.io"
            val cases = mapOf(
                RainLoopLoadError(RainLoopLoadError.Kind.HTTP, host, 503) to context.getString(R.string.sim_loop_error_http, host, 503),
                RainLoopLoadError(RainLoopLoadError.Kind.OFFLINE, host) to context.getString(R.string.sim_loop_error_offline, host),
                RainLoopLoadError(RainLoopLoadError.Kind.TIMEOUT, host) to context.getString(R.string.sim_loop_error_timeout, host),
                RainLoopLoadError.noFrames(host, 100) to context.getString(R.string.sim_loop_error_no_frames, 100, host),
            )
            for ((error, text) in cases) {
                activity.showLoadError(error)
                assertEquals(text, activity.findViewById<TextView>(R.id.tv_error_message).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_error_retry).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_use_open_meteo).visibility)
            }
        }
    }

    @Test
    fun `activity launches with location extras and sets location header`() {
        val intent = Intent(context, RainForecastActivity::class.java).apply {
            putExtra(RainForecastActivity.EXTRA_LAT, 37.42)
            putExtra(RainForecastActivity.EXTRA_LON, -122.09)
        }
        ActivityScenario.launch<RainForecastActivity>(intent).onActivity { activity ->
            shadowOf(context.mainLooper).idle()

            val tvLocation = activity.findViewById<TextView>(R.id.tv_location)
            assertNotNull(tvLocation)
            assertTrue(
                "Header should contain California or domain title",
                tvLocation.text.toString().contains("California") || tvLocation.text.toString().contains("West Coast"),
            )

            val tvChartSubtitle = activity.findViewById<TextView>(R.id.tv_chart_subtitle)
            assertNotNull(tvChartSubtitle)
            assertTrue(
                "Subtitle should contain layer info",
                tvChartSubtitle.text.toString().isNotEmpty(),
            )
        }
    }

    @Test
    fun `layer buttons toggle active layer and update subtitle`() {
        val intent = Intent(context, RainForecastActivity::class.java).apply {
            putExtra(RainForecastActivity.EXTRA_LAT, 37.42)
            putExtra(RainForecastActivity.EXTRA_LON, -122.09)
        }
        ActivityScenario.launch<RainForecastActivity>(intent).onActivity { activity ->
            shadowOf(context.mainLooper).idle()

            val btnPcp6 = activity.findViewById<Button>(R.id.btn_layer_pcp6)
            btnPcp6.performClick()
            shadowOf(context.mainLooper).idle()

            val tvChartSubtitle = activity.findViewById<TextView>(R.id.tv_chart_subtitle)
            assertEquals(context.getString(R.string.rain_loop_subtitle_fronts), tvChartSubtitle.text.toString())
        }
    }

    @Test
    fun `no location - says so and never invents one`() {
        // No extras, no stored widget location, no active location: there is no default (CLAUDE.md).
        val intent = Intent(context, RainForecastActivity::class.java)
        ActivityScenario.launch<RainForecastActivity>(intent).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            assertEquals(
                context.getString(R.string.sim_loop_no_location),
                activity.findViewById<TextView>(R.id.tv_model_run).text.toString(),
            )
            assertTrue(activity.findViewById<TextView>(R.id.tv_location).text.isEmpty())
        }
    }

    @Test
    fun `Open-Meteo rain map - no location says so and fetches nothing`() {
        ActivityScenario.launch<OpenMeteoRainLoopActivity>(Intent(context, OpenMeteoRainLoopActivity::class.java)).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            assertEquals(
                context.getString(R.string.sim_loop_no_location),
                activity.findViewById<TextView>(R.id.tv_source).text.toString(),
            )
        }
    }

    private fun speedPrefs() = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, OpenMeteoRainLoopActivity.PREFS)

    @Test
    fun `Open-Meteo rain map - speed slider starts slower, drags live, saves on release`() {
        speedPrefs().edit().remove(OpenMeteoRainLoopActivity.KEY_SECONDS_PER_HOUR).commit()
        ActivityScenario.launch<OpenMeteoRainLoopActivity>(Intent(context, OpenMeteoRainLoopActivity::class.java)).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            val speed = activity.findViewById<android.widget.SeekBar>(R.id.sb_speed)
            val readout = activity.findViewById<TextView>(R.id.tv_speed_value)
            val timeline = activity.findViewById<android.widget.SeekBar>(R.id.sb_timeline)
            val play = activity.findViewById<Button>(R.id.btn_play_pause)

            assertEquals(context.getString(R.string.rain_map_speed_value, "0.8"), readout.text.toString())
            val defaultFraction = com.weatherwidget.shared.rainmap.RainLoopSpeed.fractionOf(0.8f)
            assertEquals(defaultFraction, speed.progress / speed.max.toFloat(), 0.002f)

            val listener = shadowOf(speed).onSeekBarChangeListener
            val timelineBefore = timeline.progress
            val playBefore = play.text.toString()
            speed.progress = speed.max // fastest
            listener.onStartTrackingTouch(speed)
            listener.onProgressChanged(speed, speed.max, true)
            assertEquals(context.getString(R.string.rain_map_speed_value, "0.15"), readout.text.toString())
            assertFalse("not saved until let go", speedPrefs().contains(OpenMeteoRainLoopActivity.KEY_SECONDS_PER_HOUR))
            listener.onStopTrackingTouch(speed)
            assertEquals(0.15f, speedPrefs().getFloat(OpenMeteoRainLoopActivity.KEY_SECONDS_PER_HOUR, 0f), 1e-4f)
            assertEquals("timeline untouched", timelineBefore, timeline.progress)
            assertEquals("play state untouched", playBefore, play.text.toString())
        }
        // Reopened: the chosen speed is back.
        ActivityScenario.launch<OpenMeteoRainLoopActivity>(Intent(context, OpenMeteoRainLoopActivity::class.java)).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            assertEquals(
                context.getString(R.string.rain_map_speed_value, "0.15"),
                activity.findViewById<TextView>(R.id.tv_speed_value).text.toString(),
            )
        }
        speedPrefs().edit().remove(OpenMeteoRainLoopActivity.KEY_SECONDS_PER_HOUR).commit()
    }
}
