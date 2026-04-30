package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.coEvery
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class ScreenOnReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: ScreenOnReceiver

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = ScreenOnReceiver()
        // Inject UnconfinedTestDispatcher for synchronous deterministic execution
        receiver.ioDispatcher = UnconfinedTestDispatcher()

        // Inject TestDatabase with allowMainThreadQueries to avoid background offloading flakiness
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())

        com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "screen_on_receiver_prefs")
            .edit()
            .clear()
            .commit()

        runBlocking {
            WeatherDatabase.getDatabase(context).appLogDao().clearAllLogs()
        }
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
    fun `onReceive with POWER_CONNECTED records lazy refresh timestamp`() {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "screen_on_receiver_prefs")
        assertEquals(0L, prefs.getLong("last_power_connected_refresh_ms", 0L))

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        val stored = prefs.getLong("last_power_connected_refresh_ms", 0L)
        assertTrue("Expected power-connected lazy refresh timestamp to be stored", stored > 0L)
    }

    @Test
    fun `onReceive with POWER_CONNECTED is debounced within window`() {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "screen_on_receiver_prefs")

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        val first = prefs.getLong("last_power_connected_refresh_ms", 0L)
        assertTrue("Expected first power-connected timestamp", first > 0L)

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        val second = prefs.getLong("last_power_connected_refresh_ms", 0L)

        assertEquals("Expected second power-connected event to be debounced", first, second)
    }

    @Test
    fun `onReceive with POWER_CONNECTED writes enqueued app log`() = runTest {
        val beforeCount = powerConnectedLogCount()

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertTrue("Expected POWER_CONNECTED_EVENT log entry", powerConnectedLogCount() > beforeCount)

        val latest =
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("POWER_CONNECTED_EVENT", 10).firstOrNull()
        assertNotNull("Expected latest POWER_CONNECTED_EVENT log", latest)
        assertTrue("Expected enqueued result in log message", latest!!.message.contains("result=enqueued"))
    }

    @Test
    fun `onReceive with POWER_CONNECTED debounce writes skip app log`() = runTest {
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        val beforeCount = powerConnectedLogCount()

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertTrue("Expected debounced POWER_CONNECTED_EVENT log entry", powerConnectedLogCount() > beforeCount)

        val latest =
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("POWER_CONNECTED_EVENT", 10).firstOrNull()
        assertNotNull("Expected latest POWER_CONNECTED_EVENT log", latest)
        assertTrue("Expected debounce skip result in log message", latest!!.message.contains("result=debounced_skip"))
    }

    @Test
    fun `onReceive with USER_PRESENT writes unlock policy app log`() = runTest {
        val beforeCount = unlockPolicyLogCount()

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        assertTrue("Expected UNLOCK_REFRESH_POLICY log entry", unlockPolicyLogCount() > beforeCount)

        val latest =
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("UNLOCK_REFRESH_POLICY", 1).firstOrNull()
        assertNotNull("Expected latest UNLOCK_REFRESH_POLICY log", latest)
        assertFalse("Expected uiOnly field in log message", latest!!.message.contains("uiOnly=").not())
    }

    private fun unlockPolicyLogCount(): Int {
        return runBlocking {
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("UNLOCK_REFRESH_POLICY", 100).size
        }
    }

    private fun powerConnectedLogCount(): Int {
        return runBlocking {
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("POWER_CONNECTED_EVENT", 100).size
        }
    }
}
