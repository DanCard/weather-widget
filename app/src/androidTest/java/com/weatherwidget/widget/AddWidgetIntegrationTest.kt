package com.weatherwidget.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.testutil.dateEpoch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * End-to-end "add a widget" test: performs the exact allocate → bind sequence a launcher
 * runs when the user drops the widget on the home screen, then asserts the provider
 * received the system's APPWIDGET_UPDATE broadcast and painted full content (the
 * WIDGET_RENDER_OK breadcrumb in app_logs).
 *
 * The launcher's drag-and-drop gesture itself is outside our control (and flaky on
 * emulators); this covers everything app-side of an add: provider registration with
 * AppWidgetService (manifest + widget-info XML), bind acceptance, onUpdate delivery,
 * and a successful first paint from cache without any network.
 *
 * Each stage asserts separately so a failure pinpoints where the add pipeline broke.
 */
@RunWith(AndroidJUnit4::class)
class AddWidgetIntegrationTest : IsolatedIntegrationTest("add_widget") {

    private lateinit var host: AppWidgetHost
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @Before
    override fun setup() {
        super.setup()
        grantBindPermission()
        seedForecasts()
        host = AppWidgetHost(context, TEST_HOST_ID)
    }

    @After
    override fun cleanup() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Unbinds the instance; the system broadcasts APPWIDGET_DELETED and
            // onDeleted clears the widget's persisted state.
            host.deleteAppWidgetId(widgetId)
            widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
        super.cleanup()
    }

    @Test
    fun addWidget_bindsPaintsAndHandlesResize() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, WeatherWidgetProvider::class.java)

        // Stage 1: the provider is registered with the system at all.
        val installed = manager.installedProviders.any { it.provider == provider }
        assertTrue(
            "WeatherWidgetProvider is not registered with AppWidgetService " +
                "(manifest receiver or appwidget-provider XML broken)",
            installed,
        )

        // Stage 2: allocate + bind — the actual "add" the launcher performs on drop.
        widgetId = host.allocateAppWidgetId()
        val bound = manager.bindAppWidgetIdIfAllowed(widgetId, provider, placementOptions())
        assertTrue(
            "bindAppWidgetIdIfAllowed refused widget $widgetId " +
                "(grantbind not applied, or AppWidgetService rejected the provider)",
            bound,
        )
        assertTrue(
            "Bound widget $widgetId missing from getAppWidgetIds",
            manager.getAppWidgetIds(provider).contains(widgetId),
        )

        // Stage 3: binding makes AppWidgetService broadcast APPWIDGET_UPDATE to the
        // provider; wait for the paint pipeline's success breadcrumb for this widget.
        val deadline = System.currentTimeMillis() + RENDER_TIMEOUT_MS
        var rendered = false
        while (!rendered && System.currentTimeMillis() < deadline) {
            rendered = db.appLogDao().getLogsByTag("WIDGET_RENDER_OK", 50)
                .any { it.message.contains("widget=$widgetId ") }
            if (!rendered) delay(POLL_INTERVAL_MS)
        }

        if (!rendered) {
            val trace = (
                db.appLogDao().getLogsByTag("HOURLY_PAINT_TRACE", 50) +
                    db.appLogDao().getLogsByTag("WIDGET_LIFECYCLE", 50)
                )
                .sortedBy { it.timestamp }
                .joinToString("\n") { "${it.getFormattedTime()} ${it.tag}: ${it.message}" }
            fail(
                "Widget $widgetId bound successfully but never painted full content " +
                "within ${RENDER_TIMEOUT_MS}ms. Paint trace:\n$trace",
            )
        }

        // Stage 4: the bound widget also receives the real options-changed callback while the
        // provider is non-exported. This avoids relying on a pre-placed launcher widget.
        val resizeStartMs = System.currentTimeMillis()
        val originalOptions = manager.getAppWidgetOptions(widgetId)
        manager.updateAppWidgetOptions(
            widgetId,
            Bundle(originalOptions).apply {
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    originalOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) + 10,
                )
            },
        )
        val resizeDeadline = System.currentTimeMillis() + RENDER_TIMEOUT_MS
        var resized = false
        while (!resized && System.currentTimeMillis() < resizeDeadline) {
            resized =
                db.appLogDao().getLogsByTag("RESIZE_RENDER_OK", 50).any {
                    it.timestamp >= resizeStartMs && it.message.contains("widget=$widgetId")
                }
            if (!resized) delay(POLL_INTERVAL_MS)
        }
        assertTrue(
            "Bound widget $widgetId did not receive a successful options-changed render",
            resized,
        )
    }

    /**
     * The instrumentation host app needs BIND_APPWIDGET to bind without the
     * system's REQUEST_BIND confirmation dialog; launchers hold this via their
     * privileged role, tests get it from the shell grant.
     */
    private fun grantBindPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        )
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Roughly a 2x3-cell placement so the graphical layout path is exercised. */
    private fun placementOptions() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
    }

    /**
     * With cached forecasts present, onUpdate takes the full-content render path that
     * logs WIDGET_RENDER_OK; an empty DB would paint the loading placeholder and wait
     * on a network fetch, which tests must not depend on.
     */
    private fun seedForecasts() = runBlocking {
        val today = LocalDate.now()
        db.forecastDao().insertAll(
            (0..2).map { offset ->
                ForecastEntity(
                    targetDate = dateEpoch(today.plusDays(offset.toLong()).toString()),
                    dateOfPrediction = dateEpoch(today.toString()),
                    locationLat = TestLocations.LAT,
                    locationLon = TestLocations.LON,
                    highTemp = 70f + offset,
                    lowTemp = 50f + offset,
                    condition = "Sunny",
                    source = WeatherSource.NWS.id,
                    precipProbability = 0,
                    fetchedAt = System.currentTimeMillis(),
                )
            },
        )
    }

    companion object {
        private const val TEST_HOST_ID = 0x7E57
        private const val RENDER_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 250L
    }
}
