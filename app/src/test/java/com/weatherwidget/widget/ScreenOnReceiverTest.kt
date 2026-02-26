package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import com.weatherwidget.data.local.WeatherDatabase
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenOnReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: ScreenOnReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = ScreenOnReceiver()
    }

    @Test
    fun `onReceive with USER_PRESENT sends ACTION_REFRESH broadcast`() {
        val intent = Intent(Intent.ACTION_USER_PRESENT)
        
        receiver.onReceive(context, intent)
        
        val shadowApplication = shadowOf(context as android.app.Application)
        val broadcastIntents = shadowApplication.broadcastIntents
        
        assertTrue("Expected at least one broadcast intent", broadcastIntents.isNotEmpty())
        
        // Find the specific intent sent to WeatherWidgetProvider
        val providerIntent = broadcastIntents.find { 
            it.component?.className == WeatherWidgetProvider::class.java.name 
        }
        
        assertNotNull("Expected broadcast to WeatherWidgetProvider", providerIntent)
        assertEquals(WeatherWidgetProvider.ACTION_REFRESH, providerIntent?.action)
    }

    @Test
    fun `onReceive ignores other actions`() {
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        
        receiver.onReceive(context, intent)
        
        val shadowApplication = shadowOf(context as android.app.Application)
        val broadcastIntents = shadowApplication.broadcastIntents
        
        // Ensure no relevant broadcasts were sent
        val providerIntent = broadcastIntents.find { 
            it.component?.className == WeatherWidgetProvider::class.java.name 
        }
        
        assertTrue("Did not expect broadcast to WeatherWidgetProvider", providerIntent == null)
    }

    @Test
    fun `onReceive with SCREEN_OFF does not send refresh broadcast`() {
        val intent = Intent(Intent.ACTION_SCREEN_OFF)

        receiver.onReceive(context, intent)

        val shadowApplication = shadowOf(context as android.app.Application)
        val providerIntent =
            shadowApplication.broadcastIntents.find {
                it.component?.className == WeatherWidgetProvider::class.java.name &&
                    it.action == WeatherWidgetProvider.ACTION_REFRESH
            }

        assertTrue("Did not expect refresh broadcast on screen off", providerIntent == null)
    }

    @Test
    fun `onReceive with USER_PRESENT writes unlock policy app log`() {
        val beforeCount = unlockPolicyLogCount()

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        val foundLog = waitForCondition(timeoutMs = 1000) {
            unlockPolicyLogCount() > beforeCount
        }
        assertTrue("Expected UNLOCK_REFRESH_POLICY log entry", foundLog)

        val latest =
            runBlocking {
                WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("UNLOCK_REFRESH_POLICY").firstOrNull()
            }
        assertNotNull("Expected latest UNLOCK_REFRESH_POLICY log", latest)
        assertFalse("Expected uiOnly field in log message", latest!!.message.contains("uiOnly=").not())
    }

    private fun unlockPolicyLogCount(): Int {
        return runBlocking {
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("UNLOCK_REFRESH_POLICY").size
        }
    }

    private fun waitForCondition(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
