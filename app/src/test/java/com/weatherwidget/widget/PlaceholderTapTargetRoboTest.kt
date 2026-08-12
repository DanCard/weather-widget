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
}
