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

        // FileProvider caches each authority's roots statically; Robolectric gives every test a new
        // cache dir, so a stale root would reject this test's zip path.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
        com.weatherwidget.widget.WidgetViewsHistory.resetForTest()
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

    /** Clicks Send and returns the chooser's target intent plus the zip's entries (name -> bytes). */
    private fun sendAndReadZip(
        activity: BugReportActivity,
        description: String,
    ): Pair<Intent, Map<String, ByteArray>> {
        activity.findViewById<EditText>(R.id.bug_report_description).setText(description)
        activity.findViewById<Button>(R.id.bug_report_send_button).performClick()
        shadowOf(context.mainLooper).idle()

        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("An intent should be started", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uri = target.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("The zip must be attached", uri)
        assertEquals("${context.packageName}.bugreports", uri!!.authority)
        // Read the shared file itself: the content-resolver path closes a ParcelFileDescriptor,
        // which Robolectric can't do in JVMs without `--add-opens java.base/java.io`.
        val zipFile = java.io.File(java.io.File(context.cacheDir, BugReportActivity.BUG_REPORT_DIR), uri.lastPathSegment!!)
        assertTrue("attached uri must name the written zip: $zipFile", zipFile.isFile)
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        println("BugReportActivityRobolectricTest zip entries: ${entries.mapValues { it.value.size }}")
        return target to entries
    }

    @Test
    fun `sending bug report attaches a zip with summary logs widget images and database`() {
        // Real Room rows across 80 h: routine and key-event tags inside and outside each window.
        val now = System.currentTimeMillis()
        val hour = 60 * 60 * 1000L
        kotlinx.coroutines.runBlocking {
            val dao = database.appLogDao()
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 80 * hour, tag = "SYNC_CANCELLED", message = "too old", level = "INFO"))
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 30 * hour, tag = "SNAPSHOT_SKIP", message = "old routine"))
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 30 * hour, tag = "FETCH_METEO_FAIL", message = "old warn\twith tab", level = "WARN"))
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 5 * hour, tag = "CONFIG", message = "old config"))
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 1 * hour, tag = "SNAPSHOT_SKIP", message = "recent routine"))
            dao.insert(com.weatherwidget.data.local.AppLogEntity(timestamp = now - 60_000, tag = "INTERACTION_E2E", message = "action=ACTION_SHOW_TOAST widget=12"))
        }
        // A widget the app has actually pushed, so the report can draw it.
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(appWidgetManager).createWidget(com.weatherwidget.widget.WeatherWidgetProvider::class.java, R.layout.widget_weather)
        appWidgetManager.updateAppWidgetOptions(
            widgetId,
            android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
            },
        )
        // As the renderers push it: the graph is a bitmap on R.id.graph_view. A capture that inflates
        // with the activity's AppCompat inflater fails here (AppCompatImageView isn't remotable).
        val views = android.widget.RemoteViews(context.packageName, R.layout.widget_weather).apply {
            setImageViewBitmap(R.id.graph_view, android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888))
        }
        kotlinx.coroutines.runBlocking {
            com.weatherwidget.widget.WidgetPushDispatcher.push(appWidgetManager, widgetId, views, partialPush = false, caller = "DAILY", appLogDao = database.appLogDao())
        }

        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.loadPreview()
            shadowOf(context.mainLooper).idle()

            val (target, entries) = sendAndReadZip(activity, "App crashes when rotating device.")

            assertEquals(Intent.ACTION_SEND, target.action)
            assertEquals("application/zip", target.type)
            assertEquals("daniecarde55@gmail.com", target.getStringArrayExtra(Intent.EXTRA_EMAIL)?.firstOrNull())
            val subject = target.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            assertTrue("Subject carries the description: $subject", subject.startsWith("Weather widget bug report: App crashes when rotating dev"))
            val body = target.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            assertTrue("Body has the description", body.contains("App crashes when rotating device."))
            assertTrue("Body lists recent taps", body.contains("INTERACTION_E2E action=ACTION_SHOW_TOAST widget=12"))
            assertTrue("Body lists the zip contents", body.contains("--- ATTACHED ZIP ---"))

            assertEquals(
                listOf("summary.txt", "logs.tsv", "widget-$widgetId.png", "weather_database.db"),
                entries.keys.toList(),
            )

            val tsv = entries.getValue("logs.tsv").decodeToString().trimEnd('\n').lines()
            println("BugReportActivityRobolectricTest logs.tsv:\n${tsv.joinToString("\n")}")
            assertEquals(com.weatherwidget.shared.util.BugReportLogs.TSV_HEADER, tsv.first())
            val messages = tsv.drop(1).map { it.split('\t')[5] }
            assertTrue("old WARN kept as key event, tab escaped", messages.contains("old warn\\twith tab"))
            assertTrue("old CONFIG kept as key event", messages.contains("old config"))
            assertTrue("recent routine kept", messages.contains("recent routine"))
            assertTrue("old routine dropped", messages.none { it == "old routine" })
            assertTrue("rows past 72 h dropped", messages.none { it == "too old" })
            assertEquals("key", tsv.first { it.endsWith("old config") }.split('\t')[2])
            assertEquals("recent", tsv.first { it.endsWith("recent routine") }.split('\t')[2])

            val png = entries.getValue("widget-$widgetId.png")
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            val density = context.resources.displayMetrics.density
            assertEquals("image is the widget's width in px", (320 * density).toInt(), bitmap.width)
            assertEquals("image is the widget's height in px", (180 * density).toInt(), bitmap.height)

            // The database copy opens with real SQLite and holds the seeded rows.
            val dbFile = java.io.File(context.cacheDir, "check.db").apply { writeBytes(entries.getValue("weather_database.db")) }
            android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM app_logs WHERE message = 'old config'", null).use { c ->
                    c.moveToFirst()
                    assertEquals(1, c.getInt(0))
                }
            }
            assertTrue("summary.txt carries header detail", entries.getValue("summary.txt").decodeToString().contains("--- HEADER DETAIL ---"))
        }
    }

    @Test
    fun `unchecking boxes leaves only the summary in the zip`() {
        val intent = Intent(context, BugReportActivity::class.java)
        ActivityScenario.launch<BugReportActivity>(intent).onActivity { activity ->
            activity.ioDispatcher = testDispatcher
            activity.findViewById<CheckBox>(R.id.bug_report_include_logs_checkbox).isChecked = false
            activity.findViewById<CheckBox>(R.id.bug_report_include_metadata_checkbox).isChecked = false
            shadowOf(context.mainLooper).idle()

            val (_, entries) = sendAndReadZip(activity, "Small issue")
            assertEquals(listOf("summary.txt"), entries.keys.toList())
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
                "Widget section should carry the latest header decision in short form",
                previewText.contains("  header: daily 140dp buttons=HIDDEN"),
            )
        }
    }
}
