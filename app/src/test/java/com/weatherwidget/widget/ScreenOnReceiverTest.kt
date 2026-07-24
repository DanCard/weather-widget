package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetActions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
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
@Config(sdk = [35])
@Category(LongDuration::class)
class ScreenOnReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: ScreenOnReceiver

    @org.junit.After
    fun tearDown() {
        unmockkAll()
        // WeatherDatabase.isTesting (flipped on by setDatabaseForTesting in @Before)
        // is a JVM-wide static. Restore to false so it does not leak into later
        // tests that rely on the default.
        WeatherDatabase.setIsTesting(false)
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
    fun `onReceive with USER_PRESENT on battery sends cache-only refresh broadcast`() {
        mockkObject(BatteryStatePolicy)
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns false
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
        assertEquals(WidgetActions.ACTION_REFRESH, providerIntent?.action)
        assertTrue(
            "Unplugged unlock must remain cache-only even at the default high battery reading",
            providerIntent?.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false) == true,
        )
    }

    @Test
    fun `onReceive with USER_PRESENT while charging sends network-capable refresh broadcast`() {
        mockkObject(BatteryStatePolicy)
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns true

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        val providerIntent =
            shadowOf(context as android.app.Application).broadcastIntents.find {
                it.component?.className == WeatherWidgetProvider::class.java.name
            }
        assertNotNull("Expected broadcast to WeatherWidgetProvider", providerIntent)
        assertEquals(WidgetActions.ACTION_REFRESH, providerIntent?.action)
        assertFalse(
            "Charging unlock should remain network-capable",
            providerIntent?.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false) == true,
        )
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
                    it.action == WidgetActions.ACTION_REFRESH
            }

        assertTrue("Did not expect refresh broadcast on screen off", providerIntent == null)
    }

    @Test
    fun `onReceive with SCREEN_OFF on battery cancels current-temp and non-primary loops`() {
        mockkObject(CurrentTempUpdateScheduler)
        mockkObject(NonPrimaryObservationScheduler)
        every { CurrentTempUpdateScheduler.cancel(any()) } just Runs
        every { NonPrimaryObservationScheduler.cancel(any()) } just Runs

        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

        verify { CurrentTempUpdateScheduler.cancel(context) }
        verify { NonPrimaryObservationScheduler.cancel(context) }
    }

    @Test
    fun `onReceive with SCREEN_OFF while charging does not cancel current-temp loop but cancels non-primary loop`() {
        mockkObject(CurrentTempUpdateScheduler)
        mockkObject(NonPrimaryObservationScheduler)
        mockkObject(BatteryStatePolicy)
        every { CurrentTempUpdateScheduler.cancel(any()) } just Runs
        every { NonPrimaryObservationScheduler.cancel(any()) } just Runs
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns true

        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

        verify(exactly = 0) { CurrentTempUpdateScheduler.cancel(any()) }
        verify { NonPrimaryObservationScheduler.cancel(context) }
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
    fun `onReceive with USER_PRESENT on battery logs cache-only policy`() = runTest {
        mockkObject(BatteryStatePolicy)
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns false
        val beforeCount = unlockPolicyLogCount()

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        assertTrue("Expected UNLOCK_REFRESH_POLICY log entry", unlockPolicyLogCount() > beforeCount)

        val latest =
            WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("UNLOCK_REFRESH_POLICY", 1).firstOrNull()
        assertNotNull("Expected latest UNLOCK_REFRESH_POLICY log", latest)
        assertTrue("Expected uiOnly=true in log message", latest!!.message.contains("uiOnly=true"))
    }

    @Test
    fun `onReceive with USER_PRESENT while charging schedules non-primary update`() {
        mockkObject(NonPrimaryObservationScheduler)
        mockkObject(BatteryStatePolicy)
        every { NonPrimaryObservationScheduler.scheduleNextUpdate(any(), any()) } just Runs
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns true

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        verify { NonPrimaryObservationScheduler.scheduleNextUpdate(context, isScreenInteractive = true) }
    }

    @Test
    fun `onReceive with USER_PRESENT on battery cancels non-primary update`() {
        mockkObject(NonPrimaryObservationScheduler)
        mockkObject(BatteryStatePolicy)
        every { NonPrimaryObservationScheduler.cancel(any()) } just Runs
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns false

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        verify { NonPrimaryObservationScheduler.cancel(context) }
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
