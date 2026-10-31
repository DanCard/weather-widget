package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.BuiltInApiKeys
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Settings → Weather Data Sources in two groups: In rotation (dragged by its handles, ✕ removes) and
 * Available (+ adds). plans/261015-settings-sources-drag-reorder-and-two-groups.md, tests 3–7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class SettingsSourceListRoboTest {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase

    private val seeded = listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearPrefs()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder().setExecutor { _ -> }
                .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        WidgetStateManager(context).setVisibleSourcesOrder(seeded)
    }

    @After
    fun tearDown() {
        clearPrefs()
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun clearPrefs() {
        listOf("weather_widget_prefs", "widget_state_prefs", "weather_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun withSettings(block: (SettingsActivity) -> Unit) {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun stored(): List<WeatherSource> = WidgetStateManager(context).getEnabledSourcesOrder()

    private fun SettingsActivity.recycler(): RecyclerView = findViewById(R.id.api_sources_rotation)

    private fun SettingsActivity.holder(position: Int): SettingsActivity.RotationAdapter.Holder {
        idle()
        return recycler().findViewHolderForAdapterPosition(position) as SettingsActivity.RotationAdapter.Holder
    }

    private fun SettingsActivity.availableNames(): List<String> {
        val container = findViewById<LinearLayout>(R.id.api_sources_available)
        return (0 until container.childCount).map {
            container.getChildAt(it).findViewById<TextView>(R.id.source_name).text.toString()
        }
    }

    private fun SettingsActivity.availableRow(source: WeatherSource): android.view.View {
        val container = findViewById<LinearLayout>(R.id.api_sources_available)
        return (0 until container.childCount).map { container.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.source_name).text == source.displayName }
    }

    private fun sourceOrderLogs(): List<String> {
        // AppLogWidgetStateEventLogger writes on its own scope.
        Thread.sleep(200)
        idle()
        return runBlocking { db.appLogDao().getLogsByTag("SOURCE_ORDER", 50) }.map { it.message }
    }

    // 3
    @Test
    fun `enabled sources are in rotation in stored order, the rest available at full opacity`() {
        withSettings { activity ->
            assertEquals(seeded.map { it.id }, activity.rotationAdapter.ids)
            assertEquals(3, activity.recycler().childCount)
            assertEquals(
                (0 until 3).map { activity.holder(it).name.text.toString() },
                seeded.map { it.displayName },
            )
            assertEquals(
                WeatherSourceOrdering.available(seeded.map { it.id }).map { it.displayName },
                activity.availableNames(),
            )
            val container = activity.findViewById<LinearLayout>(R.id.api_sources_available)
            (0 until container.childCount).forEach { assertEquals(1f, container.getChildAt(it).alpha) }
        }
    }

    // 4
    @Test
    fun `a long-press on a row's text starts a drag`() {
        withSettings { activity ->
            val row = activity.holder(1)
            assertEquals(0f, row.itemView.elevation)
            assertTrue(row.text.performLongClick())
            assertTrue("the row is lifted", row.itemView.elevation > 0f)
            assertEquals("a long-press alone reorders nothing", seeded, stored())
        }
    }

    @Test
    fun `a drag moves rows on screen and saves once, on drop`() {
        withSettings { activity ->
            val callback = activity.sourceDragCallback
            val recycler = activity.recycler()
            val dragged = activity.holder(0)
            callback.onSelectedChanged(dragged, ItemTouchHelper.ACTION_STATE_DRAG)
            callback.onMove(recycler, dragged, activity.holder(1))
            callback.onMove(recycler, dragged, activity.holder(2))
            assertEquals("not saved mid-drag", seeded, stored())
            assertEquals(listOf("SILURIAN", "TOMORROW_IO", "OPEN_METEO"), activity.rotationAdapter.ids)

            callback.clearView(recycler, dragged)
            assertEquals(listOf(WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO, WeatherSource.OPEN_METEO), stored())
            // The preference layer logs "Order changed:" on every save: one save per drag.
            val logs = sourceOrderLogs()
            assertEquals("one save per drag: $logs", 1, logs.count { it.startsWith("Order changed:") })
            assertEquals("one Drag line: $logs", 1, logs.count { it.startsWith("Drag:") })
            val dragLogs = runBlocking { db.appLogDao().getLogsByTag("SOURCE_DRAG", 50) }.map { it.message }
            assertEquals("start and end: $dragLogs", 2, dragLogs.count { it.startsWith("platform=android phase=") })
        }
    }

    @Test
    fun `a saved drag repaints the widgets so the home button follows the new order`() {
        withSettings { activity ->
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val before = shadowOf(app).broadcastIntents.size
            val callback = activity.sourceDragCallback
            val recycler = activity.recycler()
            val dragged = activity.holder(1)
            callback.onSelectedChanged(dragged, ItemTouchHelper.ACTION_STATE_DRAG)
            callback.onMove(recycler, dragged, activity.holder(0))
            callback.clearView(recycler, dragged)

            val repaint = shadowOf(app).broadcastIntents.drop(before)
                .lastOrNull { it.action == com.weatherwidget.widget.WidgetActions.ACTION_REFRESH }
            assertTrue("drop sends a repaint", repaint != null)
            assertTrue(repaint!!.getBooleanExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, false))
        }
    }

    @Test
    fun `a drag that ends where it started saves nothing`() {
        withSettings { activity ->
            val callback = activity.sourceDragCallback
            val dragged = activity.holder(1)
            callback.onSelectedChanged(dragged, ItemTouchHelper.ACTION_STATE_DRAG)
            callback.clearView(activity.recycler(), dragged)
            assertEquals(seeded, stored())
            assertTrue(sourceOrderLogs().isEmpty())
        }
    }

    // 5
    @Test
    fun `remove sends a source to available, and the last one cannot be removed`() {
        withSettings { activity ->
            activity.holder(1).remove.performClick()
            idle()
            assertEquals(listOf(WeatherSource.OPEN_METEO, WeatherSource.TOMORROW_IO), stored())
            assertTrue(WeatherSource.SILURIAN.displayName in activity.availableNames())

            activity.holder(1).remove.performClick()
            idle()
            activity.holder(0).remove.performClick()
            idle()
            assertEquals(listOf(WeatherSource.OPEN_METEO), stored())
            assertEquals(activity.getString(R.string.must_keep_one_source), ShadowToast.getTextOfLatestToast())
            assertEquals(listOf("OPEN_METEO"), activity.rotationAdapter.ids)
        }
    }

    // 6
    @Test
    fun `add appends a source, from the + or from the row`() {
        withSettings { activity ->
            activity.availableRow(WeatherSource.NWS).findViewById<android.view.View>(R.id.source_add_button).performClick()
            idle()
            assertEquals(seeded + WeatherSource.NWS, stored())
            assertFalse(WeatherSource.NWS.displayName in activity.availableNames())
            assertEquals((seeded + WeatherSource.NWS).map { it.id }, activity.rotationAdapter.ids)
        }
    }

    /**
     * Foldable 2026-10-30: a ScrollView's height hint, less the sections above, stopped the list at
     * five rows; the other enabled sources could be neither seen nor scrolled to.
     */
    @Test
    fun `every enabled source gets a row however short the window`() {
        val all = WeatherSourceOrdering.ALL_CONFIGURABLE
        WidgetStateManager(context).setVisibleSourcesOrder(all)
        withSettings { activity ->
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            content.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
            )
            content.layout(0, 0, content.measuredWidth, content.measuredHeight)
            assertEquals(all.size, activity.recycler().childCount)
        }
    }

    @Test
    fun `adding Google appends it last`() {
        assumeTrue(
            "this build has no Google key",
            BuiltInApiKeys.hasEffectiveKey(WeatherSource.GOOGLE_WEATHER, WidgetStateManager(context)),
        )
        withSettings { activity ->
            activity.availableRow(WeatherSource.GOOGLE_WEATHER).performClick()
            idle()
            assertEquals(seeded + WeatherSource.GOOGLE_WEATHER, stored())
            assertEquals(WeatherSource.GOOGLE_WEATHER.id, activity.rotationAdapter.ids.last())
        }
    }

    @Test
    fun `a source with no key is refused and the page shows the key fields`() {
        val keyless = WeatherSourceOrdering.available(seeded.map { it.id })
            .firstOrNull { it.requiresApiKey && !BuiltInApiKeys.hasEffectiveKey(it, WidgetStateManager(context)) }
        assumeTrue("every key-based source has a key baked into this build", keyless != null)
        withSettings { activity ->
            activity.availableRow(keyless!!).performClick()
            idle()
            assertEquals(seeded, stored())
            assertEquals(
                activity.getString(R.string.api_key_required_to_enable, keyless.displayName),
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    // 7
    @Test
    fun `rows carry Move up and Move down accessibility actions that reorder`() {
        withSettings { activity ->
            fun labels(position: Int): List<String> {
                val info = AccessibilityNodeInfo.obtain()
                activity.holder(position).itemView.onInitializeAccessibilityNodeInfo(info)
                return info.actionList.mapNotNull { it.label?.toString() }
            }
            val up = activity.getString(R.string.move_up)
            val down = activity.getString(R.string.move_down)
            assertEquals(listOf(down), labels(0).filter { it == up || it == down })
            assertEquals(listOf(up, down), labels(1).filter { it == up || it == down })
            assertEquals(listOf(up), labels(2).filter { it == up || it == down })

            val upId = activity.holder(2).a11yActionIds.first()
            assertTrue(activity.holder(2).itemView.performAccessibilityAction(upId, null))
            idle()
            assertEquals(listOf(WeatherSource.OPEN_METEO, WeatherSource.TOMORROW_IO, WeatherSource.SILURIAN), stored())
            // Rebound: the row now first has no Move up.
            assertEquals(listOf(down), labels(0).filter { it == up || it == down })
        }
    }
}
