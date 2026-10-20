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
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            assertTrue(
                "Layer description should describe 6-hour rain",
                tvChartSubtitle.text.toString().contains("6-hour") || tvChartSubtitle.text.toString().contains("Rain"),
            )
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
}
