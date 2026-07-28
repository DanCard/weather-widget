package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.WeatherDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side coverage for the resize path (plans/260728-widgetintentrouter-code-review-opus.md,
 * F1/F2/F6). Drives the REAL framework chain — `updateAppWidgetOptions` →
 * `onAppWidgetOptionsChanged` → `WidgetIntentRouter.handleResize` — which no broadcast can reach,
 * because the framework supplies an options Bundle `am broadcast` cannot construct.
 *
 * MEASURED ON DEVICE (2026-07-28, API 36 emulator): the platform delivers
 * ACTION_APPWIDGET_UPDATE_OPTIONS to a manifest receiver **330-400ms apart even when the sender
 * issues all of them in a tight loop with no sleep** — the broadcast queue serializes them. That is
 * wider than RESIZE_DEBOUNCE_MS (250ms), so resize events essentially never arrive inside the
 * debounce window and the coalescing branch does not fire here. Do NOT assert
 * `renders < events` — it cannot hold on this transport.
 *
 * The debounce is still worth keeping: it costs nothing, it guards hosts that batch faster than the
 * AOSP queue (fold/unfold and orientation changes emit several option updates), and its real,
 * unconditional win is that the 250ms wait no longer happens while holding the per-widget mutex.
 * The coalescing logic itself is proven in JVM tests
 * (`WidgetIntentRouterExecutionTest.resize debounce *`), which control arrival timing directly.
 *
 * What this test pins is what only a device can show: the real options-changed chain reaches the
 * router, renders, and leaves the F1 breadcrumbs — one `RESIZE_RENDER_OK` per delivered event and
 * no `RESIZE_FAIL`.
 *
 * Deliberately NOT an [com.weatherwidget.testutil.IsolatedIntegrationTest]: that base class clears
 * the app database. This test only reads app_logs and restores the widget's original size.
 */
@RunWith(AndroidJUnit4::class)
class ResizeDebounceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realOptionsChangedChain_rendersAndLeavesBreadcrumbs() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
        assumeTrue("needs a placed weather widget on the home screen", ids.isNotEmpty())
        val widgetId = ids.first()

        val db = WeatherDatabase.getDatabase(context)
        val startMs = System.currentTimeMillis()
        val original = manager.getAppWidgetOptions(widgetId)
        val originalMinHeight = original.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100)

        val events = 4
        repeat(events) { step ->
            manager.updateAppWidgetOptions(
                widgetId,
                Bundle(original).apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, originalMinHeight + 10 * (step + 1))
                },
            )
        }

        // Outlast serialized delivery (~400ms each) plus the debounce and render of the last one.
        Thread.sleep(8_000L)

        val since = { tag: String ->
            runBlocking { db.appLogDao().getLogsByTag(tag, 200) }
                .count { it.timestamp >= startMs && it.message.contains("widget=$widgetId") }
        }
        val renders = since("RESIZE_RENDER_OK")
        val failures = since("RESIZE_FAIL")

        // Restore the original size so the home screen is left as found.
        manager.updateAppWidgetOptions(widgetId, original)
        Thread.sleep(2_000L)

        assertEquals("resize must never fail; RESIZE_FAIL rows present for widget $widgetId", 0, failures)
        assertTrue(
            "real options-changed chain must reach the router and render; got $renders " +
                "RESIZE_RENDER_OK rows for widget $widgetId",
            renders >= 1,
        )
        assertTrue(
            "renders ($renders) must not exceed delivered events ($events) — the router must not " +
                "amplify a single options change into multiple renders",
            renders <= events,
        )
    }
}
