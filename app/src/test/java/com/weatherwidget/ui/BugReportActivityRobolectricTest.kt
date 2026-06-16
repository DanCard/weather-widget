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
@Config(sdk = [34])
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
            assertEquals("mailto:daniecarde55@gmail.com", targetIntent?.data?.toString())
            assertEquals("Weather widget bug report", targetIntent?.getStringExtra(Intent.EXTRA_SUBJECT))
            
            val bodyText = targetIntent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            assertTrue("Body should contain description", bodyText.contains("App crashes when rotating device."))
            assertTrue("Body should contain App version details", bodyText.contains("## Application Version"))
        }
    }
}
