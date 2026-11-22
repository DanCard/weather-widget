package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
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
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class BugReportActivityRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)

        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
    }

    @After
    fun tearDown() {
        database.close()
        WeatherDatabase.setIsTesting(false)
        WeatherDatabase.resetInstanceForTesting()
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
    }

    private fun clearTestPrefs(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `diagnostics preview contains device info and battery status`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            // Inject test dispatcher and re-load preview synchronously
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val previewText = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()

            assertTrue("Preview should contain device info section", previewText.contains("--- DEVICE INFO ---"))
            assertTrue("Preview should contain battery section", previewText.contains("--- BATTERY & POWER ---"))
            assertTrue("Preview should show active widgets", previewText.contains("--- ACTIVE WIDGETS ---"))
            assertTrue("Preview should show database stats", previewText.contains("--- DATABASE STATS ---"))
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
    fun `diagnostics preview reports display density timezone launcher and power state`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val previewText = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()
            println("BugReportActivityRobolectricTest preview:\n$previewText")

            assertTrue("Preview should contain display section", previewText.contains("--- DISPLAY ---"))
            assertTrue("Density should come from the device qualifier", previewText.contains("Density: 480 dpi (scale 3.00)"))
            assertTrue("Screen dp should come from the device qualifier", previewText.contains("Screen dp: 384x832"))
            assertTrue("Preview should report font scale", previewText.contains("Font scale: 1.00"))
            assertTrue("Preview should report the timezone", previewText.contains("Timezone: "))
            assertTrue("Preview should report the launcher", previewText.contains("Launcher: "))
            assertTrue("Preview should report battery optimization", previewText.contains("Battery optimization exempt: "))
            assertTrue("Preview should report location permissions", previewText.contains("Location permission: fine="))
        }
    }

    @Test
    fun `diagnostics preview reports each widget's launcher-given size and view`() {
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
        val shadowManager = shadowOf(appWidgetManager)
        val provider = android.content.ComponentName(context, com.weatherwidget.widget.WeatherWidgetProvider::class.java)
        val widgetId = shadowManager.createWidget(com.weatherwidget.widget.WeatherWidgetProvider::class.java, R.layout.widget_weather)
        appWidgetManager.updateAppWidgetOptions(
            widgetId,
            android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 384)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 420)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
            },
        )
        assertTrue("Test widget must be bound to the provider", appWidgetManager.getAppWidgetIds(provider).contains(widgetId))

        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val previewText = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()
            println("BugReportActivityRobolectricTest widget preview:\n$previewText")

            assertTrue("Widget line should name its view", previewText.contains("- Widget #$widgetId: API=") && previewText.contains("| View="))
            assertTrue("Widget size should be the portrait host size (minW x maxH)", previewText.contains("size=384x220dp"))
            assertTrue("Widget options should be echoed", previewText.contains("options=min:384x150,max:420x220"))
            assertTrue(
                "A widget not yet rendered in this process says so instead of guessing",
                previewText.contains("header: not rendered since app process start"),
            )
        }
    }

    @Test
    fun `checkbox toggles change metadata presence in diagnostics preview`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val previewTextBefore = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()
            assertTrue("Metadata should be included by default", previewTextBefore.contains("Sources order:"))

            val metadataCheckbox = activity.findViewById<CheckBox>(R.id.bug_report_include_metadata_checkbox)
            metadataCheckbox.isChecked = false
            shadowOf(context.mainLooper).idle()

            val previewTextAfter = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()
            assertTrue("Metadata should be excluded after unchecking", previewTextAfter.contains("[DB/Config metadata excluded]"))
        }
    }

    @Test
    fun `sending bug report with empty description shows toast error`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val descriptionEdit = activity.findViewById<EditText>(R.id.bug_report_description)
            descriptionEdit.setText("")

            val sendButton = activity.findViewById<Button>(R.id.bug_report_send_button)
            sendButton.performClick()

            shadowOf(context.mainLooper).idle()

            val latestToast = ShadowToast.getLatestToast()
            assertNotNull("Toast should be shown for empty description", latestToast)
            assertEquals("Please write a brief description of the issue before sending.", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `sending bug report with description fires send intent`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val descriptionEdit = activity.findViewById<EditText>(R.id.bug_report_description)
            descriptionEdit.setText("App crashes when rotating device.")

            val sendButton = activity.findViewById<Button>(R.id.bug_report_send_button)
            sendButton.performClick()

            shadowOf(context.mainLooper).idle()

            // Verify a chooser intent was started
            val shadowActivity = shadowOf(activity)
            val nextIntent = shadowActivity.nextStartedActivity
            assertNotNull("An intent should be started", nextIntent)
            assertEquals(Intent.ACTION_CHOOSER, nextIntent.action)

            val targetIntent = nextIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull("Chooser target intent should not be null", targetIntent)
            assertEquals(Intent.ACTION_SENDTO, targetIntent?.action)
            assertEquals("mailto:", targetIntent?.data?.toString())
            val emails = targetIntent?.getStringArrayExtra(Intent.EXTRA_EMAIL)
            assertNotNull("Emails extra should not be null", emails)
            assertEquals("daniecarde55@gmail.com", emails?.firstOrNull())
            assertEquals("Weather widget bug report", targetIntent?.getStringExtra(Intent.EXTRA_SUBJECT))
            
            val bodyText = targetIntent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            assertTrue("Body should contain screenshot tip", bodyText.contains("Please attach a screenshot"))
            assertTrue("Body should contain description", bodyText.contains("App crashes when rotating device."))
            assertTrue("Body should contain App version details", bodyText.contains("## Application Version"))
        }
    }

    @Test
    fun `diagnostics preview reports each widget's latest header decision`() {
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(appWidgetManager).createWidget(com.weatherwidget.widget.WeatherWidgetProvider::class.java, R.layout.widget_weather)
        val measured = com.weatherwidget.shared.util.HeaderFit.Measured(width = 420f, rightCluster = 150f, gap = 12f, baseButtons = 3, buttonWidth = 100f)
        val hidden = com.weatherwidget.shared.util.HeaderFit.Plan(placement = com.weatherwidget.shared.util.HeaderFit.ButtonPlacement.HIDDEN)
        com.weatherwidget.widget.handlers.HeaderFitPersistLog.record(context, widgetId, "daily", 140f, 1f, hidden, measured)

        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val previewText = activity.findViewById<TextView>(R.id.bug_report_diagnostics_preview_text).text.toString()
            println("BugReportActivityRobolectricTest header preview:\n$previewText")
            assertTrue(
                "Widget section should carry the latest header decision",
                previewText.contains("  header: view=daily widthDp=140") && previewText.contains("placement=HIDDEN"),
            )
        }
    }
}
