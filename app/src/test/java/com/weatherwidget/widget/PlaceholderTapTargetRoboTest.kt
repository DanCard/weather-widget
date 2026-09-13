package com.weatherwidget.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.mockAppWidgetManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Both placeholder renders promise the user an action — "No location — tap to set", "Tap to refresh" —
 * and neither used to bind one.
 *
 * They build a fresh `RemoteViews` and push it with `partialPush = false`, which replaces the entire
 * view tree. Every PendingIntent from the previous render dies with it, including the root dead-zone
 * catch-all that `setupDeadZoneCatchAll` installs on normal renders. The result: nothing happens on a
 * stock launcher, and on One UI Home an unclaimed tap falls through to launching MainActivity — the
 * exact behaviour that catch-all exists to prevent.
 *
 * That is worst for the no-location state, where the instructed tap is the user's escape hatch if
 * device following can't reach them.
 *
 * These assert only that the root claims the tap. Where it goes is the intent's business; that it goes
 * anywhere at all is the regression.
 */
@Category(LongDuration::class)
class PlaceholderTapTargetRoboTest : RobolectricTest() {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Inflates the pushed RemoteViews and reports whether the root would consume a tap. */
    private fun rootClaimsTap(views: android.widget.RemoteViews): Boolean {
        val applied = views.apply(context, FrameLayout(context))
        val root = applied.findViewById<View>(R.id.widget_root)
        return root != null && root.hasOnClickListeners()
    }

    @Test
    fun `no-location placeholder claims the tap it tells the user to make`() = runTest {
        val captured = mockAppWidgetManager(widgetId = 1)

        WidgetRenderer.updateWidgetNoLocation(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 1,
        )

        assertTrue("no-location render pushed nothing", captured.viewsSlot.isCaptured)
        assertTrue(
            "\"tap to set\" must open something — this is the only escape when device following cannot reach it",
            rootClaimsTap(captured.viewsSlot.captured),
        )
    }

    @Test
    fun `error placeholder claims the tap it tells the user to make`() = runTest {
        val captured = mockAppWidgetManager(widgetId = 1)

        WidgetRenderer.updateWidgetError(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 1,
        )

        assertTrue("error render pushed nothing", captured.viewsSlot.isCaptured)
        assertTrue(
            "\"tap to refresh\" must actually refresh",
            rootClaimsTap(captured.viewsSlot.captured),
        )
    }

    /**
     * The setup-screen interstitial is a full push too, and it exists to answer "did my choice
     * register, for the right place?" — so it must carry the place name, and a tap must go somewhere
     * (a refresh) rather than fall through to MainActivity on One UI.
     */
    @Test
    fun `fetching-location placeholder names the place and claims the tap`() = runTest {
        val captured = mockAppWidgetManager(widgetId = 1)

        WidgetRenderer.updateWidgetFetchingLocation(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 1,
            placeName = "San Francisco",
        )

        assertTrue("fetching-location render pushed nothing", captured.viewsSlot.isCaptured)
        val views = captured.viewsSlot.captured
        assertTrue("interstitial must let a tap retry the fetch", rootClaimsTap(views))
        val applied = views.apply(context, FrameLayout(context))
        val body = applied.findViewById<android.widget.TextView>(R.id.day2_low).text.toString()
        assertTrue("expected the place name in \"$body\"", body.contains("San Francisco"))
    }

    /**
     * The layout ships its header populated ("72°", "NWS", nav arrows) and none of the placeholders
     * used to touch it, so a placeholder showed either those defaults or the previous render's
     * leftovers. Every placeholder must blank the data views and offer a bound settings gear — the
     * way to Settings → Set Location… from a widget that has nothing else to offer.
     */
    @Test
    fun `every placeholder blanks the header and binds the settings gear`() = runTest {
        val painters: List<Pair<String, suspend (android.appwidget.AppWidgetManager) -> Unit>> = listOf(
            "loading" to { mgr -> WidgetRenderer.updateWidgetLoading(context, mgr, 1) },
            "no_location" to { mgr -> WidgetRenderer.updateWidgetNoLocation(context, mgr, 1) },
            "error" to { mgr -> WidgetRenderer.updateWidgetError(context, mgr, 1) },
            "fetching_location" to { mgr -> WidgetRenderer.updateWidgetFetchingLocation(context, mgr, 1, "Denver") },
        )
        for ((name, paint) in painters) {
            val captured = mockAppWidgetManager(widgetId = 1)
            paint(captured.appWidgetManager)
            assertTrue("$name pushed nothing", captured.viewsSlot.isCaptured)
            val applied = captured.viewsSlot.captured.apply(context, FrameLayout(context))
            val temp = applied.findViewById<android.widget.TextView>(R.id.current_temp).text.toString()
            val source = applied.findViewById<android.widget.TextView>(R.id.api_source).text.toString()
            assertTrue("$name left current_temp=\"$temp\"", temp.isEmpty())
            assertTrue("$name left api_source=\"$source\"", source.isEmpty())
            assertTrue(
                "$name nav arrows must be hidden",
                applied.findViewById<View>(R.id.nav_left_zone).visibility == View.GONE &&
                    applied.findViewById<View>(R.id.nav_right_zone).visibility == View.GONE,
            )
            val gear = applied.findViewById<View>(R.id.settings_touch_zone)
            assertTrue("$name settings gear must be visible and bound", gear.visibility == View.VISIBLE && gear.hasOnClickListeners())
        }
    }
}
