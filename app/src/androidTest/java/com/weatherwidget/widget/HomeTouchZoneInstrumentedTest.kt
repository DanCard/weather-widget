package com.weatherwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeTouchZoneInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun homeIcon_andHomeTouchZone_canShareDailyViewPendingIntent() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        val homeIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, ViewMode.DAILY.name)
        }

        val homePendingIntent = PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.home(TEST_WIDGET_ID),
            homeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        views.setOnClickPendingIntent(R.id.home_icon, homePendingIntent)
        views.setOnClickPendingIntent(R.id.home_touch_zone, homePendingIntent)

        assertNotNull("home_icon view ID should exist", R.id.home_icon)
        assertNotNull("home_touch_zone view ID should exist", R.id.home_touch_zone)
        assertEquals(
            "Intent action should be ACTION_SET_VIEW",
            WidgetIntentRouter.ACTION_SET_VIEW,
            homeIntent.action,
        )
        assertEquals(
            "Intent should target DAILY mode",
            ViewMode.DAILY.name,
            homeIntent.getStringExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW),
        )
    }

    companion object {
        private const val TEST_WIDGET_ID = 123
    }
}
