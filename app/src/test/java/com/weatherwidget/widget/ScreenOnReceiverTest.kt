package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

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
}