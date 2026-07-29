package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetManifestContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `provider and command receiver are non-exported with system metadata intact`() {
        val packageManager = context.packageManager
        val provider =
            packageManager.getReceiverInfo(
                ComponentName(context, WeatherWidgetProvider::class.java),
                PackageManager.GET_META_DATA,
            )
        val actions =
            packageManager.getReceiverInfo(
                ComponentName(context, WidgetActionReceiver::class.java),
                PackageManager.GET_META_DATA,
            )

        assertFalse(provider.exported)
        assertFalse(actions.exported)
        assertNotNull(provider.metaData)
        assertTrue(provider.metaData.containsKey(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))

        val updateReceivers =
            packageManager.queryBroadcastReceivers(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName),
                0,
            )
        assertTrue(
            updateReceivers.any {
                it.activityInfo.name == WeatherWidgetProvider::class.java.name
            },
        )
        val customReceivers =
            packageManager.queryBroadcastReceivers(
                Intent(WidgetActions.ACTION_TOGGLE_VIEW).setPackage(context.packageName),
                0,
            )
        assertTrue(customReceivers.isEmpty())
    }
}
