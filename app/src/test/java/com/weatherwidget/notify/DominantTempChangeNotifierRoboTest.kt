package com.weatherwidget.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DominantTempChangeNotifierRoboTest {

    @Test
    fun `ensureChannel migrates legacy channel and binds custom temperature_change sound`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(
            NotificationChannel("dominant_temp_change", "Legacy", NotificationManager.IMPORTANCE_DEFAULT),
        )

        DominantTempChangeNotifier.ensureChannel(context)

        assertNull(manager.getNotificationChannel("dominant_temp_change"))
        val channel = manager.getNotificationChannel(DominantTempChangeNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(DominantTempChangeNotifier.soundUri(context), channel!!.sound)
    }
}
