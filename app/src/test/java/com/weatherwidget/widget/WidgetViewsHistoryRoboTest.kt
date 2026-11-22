package com.weatherwidget.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WidgetViewsHistory] holds what a bug-report image replays: the last full tree and the partials
 * since. Replaying them must land on the newest partial's state, the way the launcher merges them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class WidgetViewsHistoryRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = WidgetViewsHistory.resetForTest()

    private fun views(apiText: String? = null) = RemoteViews(context.packageName, R.layout.widget_weather).apply {
        apiText?.let { setTextViewText(R.id.api_source, it) }
    }

    @Test
    fun partialsReplayOnTheFullTreeInOrder() {
        WidgetViewsHistory.recordFull(7, views("NWS"), "DAILY")
        WidgetViewsHistory.recordPartial(7, views("Meteo"), bodyComplete = false)
        WidgetViewsHistory.recordPartial(7, views("Silurian"), bodyComplete = false)

        val snap = WidgetViewsHistory.snapshot(7)!!
        val view: View = snap.full.apply(context, FrameLayout(context))
        snap.partials.forEach { it.reapply(context, view) }
        val text = view.findViewById<TextView>(R.id.api_source).text.toString()
        println("WidgetViewsHistoryRoboTest replayed api_source=$text partials=${snap.partials.size}")
        assertEquals("Silurian", text)
        assertEquals("DAILY", snap.fullCaller)
        assertFalse(snap.partialOverflow)
    }

    @Test
    fun fullPushDropsEarlierPartials() {
        WidgetViewsHistory.recordFull(7, views(), "DAILY")
        WidgetViewsHistory.recordPartial(7, views("x"), bodyComplete = true)
        WidgetViewsHistory.recordFull(7, views(), "CLOUD_COVER")
        assertEquals(0, WidgetViewsHistory.snapshot(7)!!.partials.size)
        assertEquals("CLOUD_COVER", WidgetViewsHistory.snapshot(7)!!.fullCaller)
    }

    @Test
    fun partialsPastTheCapDropOldestAndFlagIt() {
        WidgetViewsHistory.recordFull(7, views(), "DAILY")
        repeat(WidgetViewsHistory.MAX_PARTIALS + 3) { WidgetViewsHistory.recordPartial(7, views("p$it"), bodyComplete = false) }
        val snap = WidgetViewsHistory.snapshot(7)!!
        assertEquals(WidgetViewsHistory.MAX_PARTIALS, snap.partials.size)
        assertTrue(snap.partialOverflow)
    }

    @Test
    fun partialWithoutFullIsIgnoredAndForgetClears() {
        WidgetViewsHistory.recordPartial(8, views("x"), bodyComplete = true)
        assertNull("an unbacked partial can't be replayed", WidgetViewsHistory.snapshot(8))
        WidgetViewsHistory.recordFull(8, views(), "DAILY")
        WidgetViewsHistory.forget(8)
        assertNull(WidgetViewsHistory.snapshot(8))
    }

    @Test
    fun completeBodyPartialSupersedesEarlierPartialsSoBitmapsDoNotAccumulate() {
        WidgetViewsHistory.recordFull(7, views("NWS"), "DAILY")
        // Routine data paints: each a complete-body partial carrying a new graph bitmap.
        repeat(10) { WidgetViewsHistory.recordPartial(7, views("body$it"), bodyComplete = true) }
        WidgetViewsHistory.recordPartial(7, views("header"), bodyComplete = false)
        val snap = WidgetViewsHistory.snapshot(7)!!
        println("WidgetViewsHistoryRoboTest held partials after 10 body paints + 1 header: ${snap.partials.size}")
        assertEquals("only the last body partial and the header partial after it are held", 2, snap.partials.size)
        assertFalse(snap.partialOverflow)

        val view: View = snap.full.apply(context, FrameLayout(context))
        snap.partials.forEach { it.reapply(context, view) }
        assertEquals("header", view.findViewById<TextView>(R.id.api_source).text.toString())
    }
}
