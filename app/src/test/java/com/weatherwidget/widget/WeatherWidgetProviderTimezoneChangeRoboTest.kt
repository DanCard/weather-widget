package com.weatherwidget.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A timezone change must repaint the widget: the framework resets the process default zone, but
 * nothing redrew "today"/NOW until the next scheduled paint (up to an hour). The manifest half is
 * the part that was missing — a handler alone never receives the broadcast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderTimezoneChangeRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun registeredForProvider(action: String): Boolean =
        context.packageManager
            .queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            .any { it.activityInfo.name == WeatherWidgetProvider::class.java.name }

    @Test
    fun `manifest registers the provider for TIMEZONE_CHANGED`() {
        assertTrue(registeredForProvider(Intent.ACTION_TIMEZONE_CHANGED))
        // Control: proves the query can see this provider's filter at all.
        assertTrue(registeredForProvider(Intent.ACTION_LOCALE_CHANGED))
    }

    @Test
    fun `timezone and locale changes repaint from cache, other actions do not`() {
        assertTrue(WeatherWidgetProvider.isCacheRepaintBroadcast(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(WeatherWidgetProvider.isCacheRepaintBroadcast(Intent.ACTION_LOCALE_CHANGED))
        assertFalse(WeatherWidgetProvider.isCacheRepaintBroadcast("android.appwidget.action.APPWIDGET_UPDATE"))
        assertFalse(WeatherWidgetProvider.isCacheRepaintBroadcast(null))
    }
}
