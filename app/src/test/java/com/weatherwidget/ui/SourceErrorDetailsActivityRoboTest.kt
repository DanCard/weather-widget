package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class SourceErrorDetailsActivityRoboTest {
    private lateinit var context: Context
    private lateinit var state: WidgetStateManager
    private val google = WeatherSource.GOOGLE_WEATHER

    // The Fold's 429 of 2026-10-06, as stored by ForecastFetchCoordinator.
    private val foldDetail = "Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: " +
        """{"error":{"code":429,"message":"Quota exceeded for quota metric 'Weather API - Forecast Hours Usage'.",""" +
        """"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",""" +
        """"metadata":{"window_start_time":"1791270000","quota_unit":"1/d/{project}","quota_limit_value":"60",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        state = WidgetStateManager(context)
        state.recordSourceFetchSuccess(google)
    }

    private fun fail(code: String, detail: String?, times: Int = 3) =
        repeat(times) { state.recordSourceFetchFailure(google, code, detail) }

    @Test
    fun `the background-data trampoline launches under its manifest theme`() {
        // It crashed in onPostCreate under @android:style/Theme.Translucent ("You need to use a
        // Theme.AppCompat theme"); every pill tap crashed the app and got the process blocked.
        Robolectric.buildActivity(BackgroundDataResolutionActivity::class.java).setup()
    }

    @Test
    fun `daily quota page names the quota, its limit and the reset`() {
        fail(GoogleQuota.ERROR_CODE_DAILY, foldDetail)
        val c = SourceErrorDetailsContent.build(
            context, google, state, locale = Locale.US, zone = ZoneId.of("America/Los_Angeles"),
        )!!
        assertEquals("GOOGLE WEATHER UPDATES PAUSED", c.headline)
        assertEquals("Daily quota used · resets 12 AM", c.summary)
        assertTrue(c.explanation.contains("resets at 12 AM"))
        val rows = c.rows.toMap()
        assertEquals("/forecast/hours:lookup", rows["Request"])
        assertEquals("429", rows["HTTP status"])
        assertEquals("ForecastHoursQueriesPerDay", rows["Quota"])
        assertEquals("60 per day, shared by every device using this key", rows["Limit"])
        assertEquals("weather.googleapis.com/forecast/hours", rows["Metric"])
        assertEquals("Oct 6, 12:00 AM", rows["Quota window started"])
        assertEquals("3", rows["Failures in a row"])
        assertTrue(c.rawResponse!!.contains("\"quota_limit_value\": \"60\""))
        assertFalse(c.offerApiKeySettings)
    }

    @Test
    fun `only a rejected key offers API key settings`() {
        fail("HTTP_401", "Tomorrow.io realtime fetch failed: status 401.")
        assertTrue(SourceErrorDetailsContent.build(context, google, state, locale = Locale.US)!!.offerApiKeySettings)

        state.recordSourceFetchSuccess(google)
        fail("DATA_RESTRICTED", "SocketException")
        assertFalse(SourceErrorDetailsContent.build(context, google, state, locale = Locale.US)!!.offerApiKeySettings)
    }

    @Test
    fun `no recorded failure builds no content`() {
        assertNull(SourceErrorDetailsContent.build(context, google, state))
    }

    @Test
    fun `success clears the stored response`() {
        fail("HTTP_429", foldDetail)
        state.recordSourceFetchSuccess(google)
        assertNull(state.getSourceLastFailureDetail(google))
    }

    @Test
    fun `page shows the rows and reveals the full response on demand`() {
        fail(GoogleQuota.ERROR_CODE_DAILY, foldDetail)
        val intent = Intent(context, SourceErrorDetailsActivity::class.java)
            .putExtra(SourceErrorDetailsActivity.EXTRA_SOURCE_ID, google.id)
        ActivityScenario.launch<SourceErrorDetailsActivity>(intent).onActivity { activity ->
            shadowOf(context.mainLooper).idle()
            assertEquals("Google Weather", activity.findViewById<TextView>(R.id.error_details_source).text.toString())
            assertTrue(activity.findViewById<LinearLayout>(R.id.error_details_rows).childCount >= 8)
            val scroll = activity.findViewById<View>(R.id.error_details_response_scroll)
            assertEquals(View.GONE, scroll.visibility)
            activity.findViewById<Button>(R.id.error_details_toggle_response).performClick()
            assertEquals(View.VISIBLE, scroll.visibility)

            // The app data usage button is offered for every error, last on the page.
            val usage = activity.findViewById<Button>(R.id.error_details_background_data)
            assertEquals(View.VISIBLE, usage.visibility)
            assertEquals("App data usage", usage.text.toString())
            val column = usage.parent as LinearLayout
            assertEquals(column.childCount - 1, column.indexOfChild(usage))
            usage.performClick()
            assertEquals(
                BackgroundDataResolutionActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className,
            )
        }
    }
}
