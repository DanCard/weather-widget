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
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Settings → Data Usage → "Usage stats…" (plans/261008-settings-usage-stats-screen-api-calls-per-source.md):
 * the button opens [UsageStatsActivity], which draws the requests logged in `api_usage_stats`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class UsageStatsActivityRoboTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { WeatherDatabase.getDatabase(context).apiUsageDao().deleteOlderThan(Long.MAX_VALUE) }
    }

    @After
    fun tearDown() {
        runBlocking { WeatherDatabase.getDatabase(context).apiUsageDao().deleteOlderThan(Long.MAX_VALUE) }
    }

    private fun log(source: String, endpoint: String, times: Int, quotaRefusal: Boolean = false) = runBlocking {
        val dao = WeatherDatabase.getDatabase(context).apiUsageDao()
        val day = ApiUsageClassifier.usageDayMs(source, Instant.now(), ZoneId.systemDefault())
        repeat(times) { dao.logCall(day, source, endpoint, isError = quotaRefusal, isQuotaRefusal = quotaRefusal) }
    }

    @Test
    fun `settings usage stats button opens the usage stats screen`() {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).onActivity { activity ->
            // Network data moved to the Usage stats screen (user, 2026-10-08): Settings keeps the button only.
            val card = activity.findViewById<LinearLayout>(R.id.data_usage_container)
            assertEquals(2, card.childCount)
            activity.findViewById<Button>(R.id.usage_stats_button).performClick()
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(UsageStatsActivity::class.java.name, next.component?.className)
        }
    }

    @Test
    fun `screen lists each source with its endpoints, busiest first`() {
        log("GOOGLE_WEATHER", "forecast/hours", 3)
        log("GOOGLE_WEATHER", "forecast/hours", 1, quotaRefusal = true)
        log("GOOGLE_WEATHER", "forecast/days", 1)
        log("NWS", "points/{id}", 2)

        ActivityScenario.launch(UsageStatsActivity::class.java).use { scenario ->
            awaitLoaded(scenario)
            scenario.onActivity { activity ->
                val api = activity.findViewById<LinearLayout>(R.id.usage_stats_api)
                val google = api.findViewWithTag<LinearLayout>("usage_source_GOOGLE_WEATHER")
                assertNotNull(google)
                assertEquals("Google Weather", (google.getChildAt(0) as TextView).text)
                // Today · This month · Last month · 90 days
                assertEquals("5", (google.getChildAt(1) as TextView).text)
                assertEquals("5", (google.getChildAt(4) as TextView).text)

                val texts = (0 until api.childCount).flatMap { i ->
                    when (val child = api.getChildAt(i)) {
                        is LinearLayout -> listOf((child.getChildAt(0) as TextView).text.toString())
                        is TextView -> listOf(child.text.toString())
                        else -> emptyList()
                    }
                }
                assertEquals(
                    listOf("", "Google Weather", "Errors: 1 · Quota refusals (429): 1", "forecast/hours", "forecast/days", "NWS", "points/{id}"),
                    texts,
                )
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.usage_stats_pacific_note).visibility)
            }
        }
    }

    @Test
    fun `screen says so when nothing has been recorded`() {
        ActivityScenario.launch(UsageStatsActivity::class.java).use { scenario ->
            awaitLoaded(scenario)
            scenario.onActivity { activity ->
                val api = activity.findViewById<LinearLayout>(R.id.usage_stats_api)
                assertEquals(activity.getString(R.string.usage_stats_empty), (api.getChildAt(0) as TextView).text)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.usage_stats_pacific_note).visibility)
            }
        }
    }

    /** The load runs on Dispatchers.IO, then posts to the main looper: idle until "Loading…" is gone. */
    private fun awaitLoaded(scenario: ActivityScenario<UsageStatsActivity>) {
        val loading = context.getString(R.string.usage_stats_loading)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            scenario.onActivity { activity ->
                shadowOf(activity.mainLooper).idle()
                val api = activity.findViewById<LinearLayout>(R.id.usage_stats_api)
                done = api.childCount > 0 && (api.getChildAt(0) as? TextView)?.text != loading
            }
            if (done) return
            Thread.sleep(20)
        }
        throw AssertionError("usage stats never finished loading")
    }
}
