package com.weatherwidget.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Delivery contract between the app and the launcher's widget host, exercised against a stand-in
 * launcher so the "widget stuck on the bare widget_weather layout" failure becomes deterministic.
 *
 * Context (2026-07-22): a Samsung widget sat on the XML defaults while its header stayed live. The
 * app was healthy the whole time — it had data, rendered fine, and logged `state=data`. What it
 * could not see is that the launcher no longer held the bound view tree it believed it had already
 * established. See [[widget_defaults_mean_unbound_layout]].
 *
 * These tests deliberately do NOT simulate the trip that triggered it (location change, hours idle,
 * plugging in). Those drive WorkManager, battery policy and doze, none of which is where the bug
 * lives, and they would make the test slow and brittle. What they simulate is the state that trip
 * produced: the launcher's cached tree is gone and the app does not know.
 *
 * MODEL, NOT FRAMEWORK: the rule that partial updates are ignored until the widget has received a
 * full update lives in AppWidgetService, not in RemoteViews, so Robolectric does not enforce it —
 * [FakeLauncher] encodes it. A green test here confirms the app behaves correctly against the
 * documented contract; it is not independent evidence that Samsung's host behaves that way. Only
 * the on-device WIDGET_PUSH breadcrumbs can settle that.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class LauncherCacheDropRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var appLogDao: AppLogDao
    private lateinit var launcher: FakeLauncher
    private lateinit var appWidgetManager: AppWidgetManager

    private val appWidgetId = 4242

    /**
     * Stands in for the launcher's widget host.
     *
     * - [full] models `updateAppWidget`: inflate fresh from XML, then run the actions. Anything the
     *   pushed RemoteViews does not populate is left at its XML default.
     * - [partial] models `partiallyUpdateAppWidget`: merge into the tree the host already holds,
     *   and ignore it entirely until a full update has established that tree.
     * - [dropCache] models the host losing its cached RemoteViews — reboot, provider-package
     *   update, or the host being rebuilt while the phone sits idle.
     */
    private class FakeLauncher(private val context: Context) {
        private var tree: View = bareInflate(context)
        private var hasReceivedFull = false

        var ignoredPartials = 0
            private set

        fun full(views: RemoteViews) {
            tree = views.apply(context, FrameLayout(context) as ViewGroup)
            hasReceivedFull = true
        }

        fun partial(views: RemoteViews) {
            if (!hasReceivedFull) {
                ignoredPartials++
                return
            }
            views.reapply(context, tree)
        }

        fun dropCache() {
            tree = bareInflate(context)
            hasReceivedFull = false
        }

        /**
         * The signal that actually distinguishes a bound widget from a never-bound one. The
         * `day2_*` TextViews read "--°" in every state including healthy renders, because in GRAPH
         * mode the day columns are painted into this bitmap.
         */
        val isBound: Boolean
            get() = tree.findViewById<ImageView>(R.id.graph_view).drawable != null

        companion object {
            fun bareInflate(context: Context): View =
                RemoteViews(context.packageName, R.layout.widget_weather)
                    .apply(context, FrameLayout(context) as ViewGroup)
        }
    }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = TestDatabase.create()
        appLogDao = db.appLogDao()
        launcher = FakeLauncher(context)
        WidgetPushDispatcher.resetForTest()

        appWidgetManager = mockk(relaxed = true)
        val fullSlot = slot<RemoteViews>()
        val partialSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(fullSlot)) } answers {
            launcher.full(fullSlot.captured)
        }
        every { appWidgetManager.partiallyUpdateAppWidget(appWidgetId, capture(partialSlot)) } answers {
            launcher.partial(partialSlot.captured)
        }
    }

    @After
    fun teardown() {
        db.close()
        WidgetPushDispatcher.resetForTest()
    }

    /**
     * A complete-body push. Hand-built rather than driven through DailyViewHandler because these
     * tests are about delivery, not rendering — that the real handlers populate `graph_view` is
     * covered separately in CurrentTempTouchRoutingRoboTest.
     */
    private fun completeBodyViews(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_weather).apply {
            setImageViewBitmap(
                R.id.graph_view,
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
            )
            setViewVisibility(R.id.graph_view, View.VISIBLE)
        }

    private suspend fun push(partialPush: Boolean, caller: String = "DAILY") {
        WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = completeBodyViews(),
            partialPush = partialPush,
            caller = caller,
            appLogDao = appLogDao,
        )
    }

    @Test
    fun `a bare launcher tree is unbound and a full push binds it`() = runTest {
        assertFalse("A freshly inflated tree must start unbound", launcher.isBound)

        push(partialPush = false)

        assertTrue("A full push must bind the body", launcher.isBound)
    }

    @Test
    fun `the first partial of a process is promoted so it cannot be silently dropped`() = runTest {
        // Nothing full behind it this process: the dispatcher promotes rather than let the host
        // discard the update.
        push(partialPush = true)

        assertTrue("An unbacked complete-body partial must be promoted to full", launcher.isBound)
        assertEquals(0, launcher.ignoredPartials)
    }

    /**
     * The defect, reproduced. After the host loses its tree the app has no way to notice: its
     * `fullPushedThisProcess` set still says this widget was backed by a full push, so every
     * subsequent complete-body repaint goes out partial and is discarded. The widget stays on the
     * XML defaults for as long as the app happens not to issue a full push.
     *
     * This is why the field widget sat blank: the partials at 13:40:26 and 13:40:34 were both
     * wasted, and only the unrelated DAILY full push at 13:40:41 brought it back.
     *
     * Asserts current behaviour on purpose. When the dispatcher learns to recover, this flips to
     * `assertTrue(launcher.isBound)` — and that inversion is the definition of done for the fix.
     */
    @Test
    fun `KNOWN DEFECT - partials after a launcher cache drop are all discarded`() = runTest {
        push(partialPush = false)
        assertTrue(launcher.isBound)

        launcher.dropCache()

        repeat(5) { push(partialPush = true) }

        assertFalse(
            "Documents the defect: the app cannot see the host lost its tree, so it keeps " +
                "sending partials that never land",
            launcher.isBound,
        )
        assertEquals("Every one of those repaints was wasted", 5, launcher.ignoredPartials)
    }

    @Test
    fun `a full push is what recovers a dropped cache`() = runTest {
        push(partialPush = false)
        launcher.dropCache()
        repeat(3) { push(partialPush = true) }
        assertFalse(launcher.isBound)

        push(partialPush = false)

        assertTrue("A full push re-establishes the tree the host lost", launcher.isBound)
    }
}
