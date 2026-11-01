package com.weatherwidget.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.widget.handlers.HeaderTapTargetHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rain % tap target on real RemoteViews. Which graph a tap on the painted daily label opens is
 * DailyHeaderRainLabelTapIntegrationTest.
 */
@RunWith(AndroidJUnit4::class)
class PrecipTouchZoneInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun applied(views: RemoteViews): View = views.apply(context, FrameLayout(context))

    @Test
    fun bindPrecipitationHeader_wiresBothTheLabelAndItsTouchZone() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, TEST_WIDGET_ID)

        val root = applied(views)
        assertTrue(root.findViewById<View>(R.id.precip_probability).hasOnClickListeners())
        assertTrue(root.findViewById<View>(R.id.precip_touch_zone).hasOnClickListeners())
    }

    @Test
    fun unboundLayout_hasNoRainTapTarget() {
        val root = applied(RemoteViews(context.packageName, R.layout.widget_weather))

        assertFalse(root.findViewById<View>(R.id.precip_touch_zone).hasOnClickListeners())
    }

    @Test
    fun setPrecipitationTouchZoneVisible_followsTheRequest() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, true)
        assertEquals(View.VISIBLE, applied(views).findViewById<View>(R.id.precip_touch_zone).visibility)

        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, false)
        assertEquals(View.GONE, applied(views).findViewById<View>(R.id.precip_touch_zone).visibility)
    }

    @Test
    fun rainZone_followsTheTemperatureZoneInTheHeaderRow() {
        val root = applied(RemoteViews(context.packageName, R.layout.widget_weather))
        val row = root.findViewById<ViewGroup>(R.id.current_weather_container)

        val tempZone = row.indexOfChild(root.findViewById(R.id.current_temp_zone))
        val rainZone = row.indexOfChild(root.findViewById(R.id.precip_touch_zone))

        assertTrue("both zones must be children of the header row", tempZone >= 0 && rainZone >= 0)
        assertTrue("rain zone ($rainZone) must follow temp zone ($tempZone)", rainZone > tempZone)
    }

    companion object {
        private const val TEST_WIDGET_ID = 123
    }
}
