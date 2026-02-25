package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UIUpdateReceiverTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var shadowPowerManager: ShadowPowerManager
    private lateinit var receiver: UIUpdateReceiver
    private lateinit var mockWorkManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        mockWorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager
        
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowPowerManager = shadowOf(powerManager)
        receiver = UIUpdateReceiver()
    }

    @Test
    fun `onReceive aborts update when screen is off`() {
        shadowPowerManager.setIsInteractive(false)
        val intent = Intent()

        receiver.onReceive(context, intent)

        verify(exactly = 0) { mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `onReceive triggers update when screen is on`() {
        shadowPowerManager.setIsInteractive(true)
        val intent = Intent()

        receiver.onReceive(context, intent)

        verify(exactly = 1) { 
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME + "_ui"), 
                eq(ExistingWorkPolicy.REPLACE), 
                any<OneTimeWorkRequest>()
            ) 
        }
    }
}