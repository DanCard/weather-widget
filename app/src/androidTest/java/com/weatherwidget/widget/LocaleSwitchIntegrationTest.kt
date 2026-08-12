package com.weatherwidget.widget

import android.app.LocaleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
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

@RunWith(AndroidJUnit4::class)
class LocaleSwitchIntegrationTest : IsolatedIntegrationTest("locale_switch") {

    private lateinit var host: AppWidgetHost
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var originalLocales: LocaleList

    @Before
    override fun setup() {
        super.setup()
        grantBindPermission()
        seedForecasts()
        host = AppWidgetHost(context, TEST_HOST_ID)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            originalLocales = localeManager.applicationLocales
        }
    }

    @After
    override fun cleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = originalLocales
        }
        
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            host.deleteAppWidgetId(widgetId)
            widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
        super.cleanup()
    }

    @Test
    fun testLocaleSwitchReRendersWidget() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@runBlocking
        }

        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, WeatherWidgetProvider::class.java)

        // Stage 1: bind the widget
        widgetId = host.allocateAppWidgetId()
        val bound = manager.bindAppWidgetIdIfAllowed(widgetId, provider, placementOptions())
        assertTrue("Failed to bind widget", bound)

        // Wait for first render
        waitForRender(widgetId)

        // Define the locales to test (per test plan)
        // "en-XA" (accented pseudolocale), "ar" (Arabic, RTL), "zh-CN" (resolves to values-zh-rCN), "id" (resolves to values-in via mapping/aliasing)
        val testLocales = listOf("ar", "en-XA", "zh-CN", "id")
        val localeManager = context.getSystemService(LocaleManager::class.java)

        for (tag in testLocales) {
            // Clear prior logs to isolate this locale switch run
            db.appLogDao().clearAllLogs()

            // Switch locale using LocaleManager
            localeManager.applicationLocales = LocaleList.forLanguageTags(tag)

            // Trigger update via ACTION_REFRESH (cache repaint)
            val intent =
                Intent(context, WidgetActionReceiver::class.java).apply {
                    action = WidgetActions.ACTION_REFRESH
                    putExtra(WidgetActions.EXTRA_UI_ONLY, true)
                }
            context.sendBroadcast(intent)

            // Wait for render
            waitForRender(widgetId)

            // Assert that there are no PROC_EXIT rows
            val procExitLogs = db.appLogDao().getLogsByTag("PROC_EXIT", 10)
            assertTrue("Detected PROC_EXIT error logs during locale change to $tag: $procExitLogs", procExitLogs.isEmpty())
        }
    }

    private fun waitForRender(widgetId: Int) = runBlocking {
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
                "Widget $widgetId failed to paint after action within ${RENDER_TIMEOUT_MS}ms. Paint trace:\n$trace",
            )
        }
    }

    private fun grantBindPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        )
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun placementOptions() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
    }

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
        private const val TEST_HOST_ID = 0x7E58
        private const val RENDER_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 250L
    }
}
