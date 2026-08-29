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

    /** Triggers passed to GpsResampler.resample, in order, by the receiver under test. */
    private val resampleTriggers = mutableListOf<String>()

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

        // Stand in for the Hilt-resolved GpsResampler: the production default reaches through
        // EntryPointAccessors, which has no component under a plain Robolectric application.
        resampleTriggers.clear()
        receiver.resampleLocation = { _, trigger -> resampleTriggers.add(trigger) }

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
        
        // Find the specific intent sent to the internal action receiver.
        val actionIntent = broadcastIntents.find {
            it.component?.className == WidgetActionReceiver::class.java.name
        }
        
        assertNotNull("Expected broadcast to WidgetActionReceiver", actionIntent)
        assertEquals(WidgetActions.ACTION_REFRESH, actionIntent?.action)
        assertTrue(
            "Unplugged unlock must remain cache-only even at the default high battery reading",
            actionIntent?.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false) == true,
        )
    }

    @Test
    fun `onReceive with USER_PRESENT while charging sends network-capable refresh broadcast`() {
        mockkObject(BatteryStatePolicy)
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns true

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        val actionIntent =
            shadowOf(context as android.app.Application).broadcastIntents.find {
                it.component?.className == WidgetActionReceiver::class.java.name
            }
        assertNotNull("Expected broadcast to WidgetActionReceiver", actionIntent)
        assertEquals(WidgetActions.ACTION_REFRESH, actionIntent?.action)
        assertFalse(
            "Charging unlock should remain network-capable",
            actionIntent?.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false) == true,
        )
    }

    @Test
    fun `onReceive ignores other actions`() {
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        
        receiver.onReceive(context, intent)
        
        val shadowApplication = shadowOf(context as android.app.Application)
        val broadcastIntents = shadowApplication.broadcastIntents
        
        // Ensure no relevant broadcasts were sent
        val actionIntent = broadcastIntents.find {
            it.component?.className == WidgetActionReceiver::class.java.name
        }
        
        assertTrue("Did not expect broadcast to WidgetActionReceiver", actionIntent == null)
    }

    @Test
    fun `onReceive with SCREEN_OFF does not send refresh broadcast`() {
        val intent = Intent(Intent.ACTION_SCREEN_OFF)

        receiver.onReceive(context, intent)

        val shadowApplication = shadowOf(context as android.app.Application)
        val actionIntent =
            shadowApplication.broadcastIntents.find {
                it.component?.className == WidgetActionReceiver::class.java.name &&
                    it.action == WidgetActions.ACTION_REFRESH
            }

        assertTrue("Did not expect refresh broadcast on screen off", actionIntent == null)
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

    @Test
    fun `onReceive with POWER_CONNECTED resamples location`() {
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertEquals(listOf("power_connected"), resampleTriggers)
    }

    @Test
    fun `onReceive with USER_PRESENT resamples location`() {
        mockkObject(BatteryStatePolicy)
        every { BatteryStatePolicy.isEffectivelyCharging(any()) } returns false

        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        assertEquals(listOf("user_present"), resampleTriggers)
    }

    @Test
    fun `every event delegates to the resampler, which owns the rate limit`() {
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        // This asserted "the second event must not resample" until 2026-08-28, when the receiver
        // kept its own debounce against its own prefs key. Two throttles with different windows
        // guarded one operation; the limit now lives once, in GpsResampler.maybeResample, and is
        // tested there (GpsResampleCooldownTest). The receiver's job is to delegate, every time --
        // if it filtered here too, the central cooldown could never widen or narrow the real
        // behaviour.
        assertEquals(
            listOf("power_connected", "power_connected"),
            resampleTriggers,
        )
    }

    @Test
    fun `location resample runs even when the current-temp refresh is debounced`() {
        // The two debounces are deliberately independent: a plug-in that is too soon for another
        // current-temp fetch is still the moment the device most likely finished moving.
        com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "screen_on_receiver_prefs")
            .edit()
            .putLong("last_power_connected_refresh_ms", System.currentTimeMillis())
            .commit()

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertEquals(listOf("power_connected"), resampleTriggers)
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
