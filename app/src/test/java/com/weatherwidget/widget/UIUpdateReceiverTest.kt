package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.weatherwidget.test.category.LongDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class UIUpdateReceiverTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var shadowPowerManager: ShadowPowerManager
    private lateinit var receiver: UIUpdateReceiver
    private lateinit var mockWorkManager: WorkManager

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        mockWorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager
        
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowPowerManager = shadowOf(powerManager)
        mockkConstructor(UIUpdateScheduler::class)
        coEvery { anyConstructed<UIUpdateScheduler>().scheduleNextUpdate() } returns Unit
        
        receiver = UIUpdateReceiver()
        // Inject UnconfinedTestDispatcher for synchronous deterministic execution
        receiver.ioDispatcher = UnconfinedTestDispatcher()
    }

    @Test
    fun `onReceive skips ui work but schedules next update when screen is off`() = runTest {
        shadowPowerManager.setIsInteractive(false)
        val intent = Intent()

        receiver.onReceive(context, intent)

        verify(exactly = 0) { mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        coVerify(exactly = 1) { anyConstructed<UIUpdateScheduler>().scheduleNextUpdate() }
    }

    @Test
    fun `onReceive triggers update and schedules next update when screen is on`() = runTest {
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
        coVerify(exactly = 1) { anyConstructed<UIUpdateScheduler>().scheduleNextUpdate() }
    }
}
