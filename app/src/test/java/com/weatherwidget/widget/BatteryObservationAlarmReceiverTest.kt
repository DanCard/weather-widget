package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
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
class BatteryObservationAlarmReceiverTest {
    private lateinit var context: Context
    private lateinit var receiver: BatteryObservationAlarmReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        receiver = BatteryObservationAlarmReceiver().apply { ioDispatcher = UnconfinedTestDispatcher() }
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).turnScreenOn(true)
        mockkObject(CurrentTempUpdateScheduler)
        every { CurrentTempUpdateScheduler.enqueueImmediateUpdate(any(), any(), any(), any(), any(), any(), any()) } just Runs
        mockkObject(BatterySnapshotProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `fire on battery with screen on fetches the primary source now and arms the next alarm`() {
        every { BatterySnapshotProvider.snapshot(any()) } returns BatterySnapshot(isCharging = false, batteryLevel = 75)

        receiver.onReceive(context, Intent())

        verify {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = any(),
                reason = "battery_screen_on_loop",
                opportunistic = false,
                force = false,
                targetSourceId = WidgetStateManager(context).getPrimarySource().id,
                userInteraction = false,
                expedited = true,
            )
        }
        assertEquals(1, shadowOf(alarmManager()).scheduledAlarms.size)
        assertEquals(1, kotlinx.coroutines.runBlocking { WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag("BATTERY_OBS_ALARM", 10) }.size)
    }

    @Test
    fun `fire after plugging in or below 70 percent ends the loop`() {
        every { BatterySnapshotProvider.snapshot(any()) } returns BatterySnapshot(isCharging = true, batteryLevel = 100)
        receiver.onReceive(context, Intent())
        every { BatterySnapshotProvider.snapshot(any()) } returns BatterySnapshot(isCharging = false, batteryLevel = 69)
        receiver.onReceive(context, Intent())

        verify(exactly = 0) { CurrentTempUpdateScheduler.enqueueImmediateUpdate(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(0, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    @Test
    fun `fire with the screen off ends the loop`() {
        every { BatterySnapshotProvider.snapshot(any()) } returns BatterySnapshot(isCharging = false, batteryLevel = 90)
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).turnScreenOn(false)

        receiver.onReceive(context, Intent())

        verify(exactly = 0) { CurrentTempUpdateScheduler.enqueueImmediateUpdate(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
