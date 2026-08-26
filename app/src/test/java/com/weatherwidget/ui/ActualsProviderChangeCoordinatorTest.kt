package com.weatherwidget.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ActualsProviderChangeCoordinatorTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        context.getSharedPreferences("widget_state_prefs_test_default", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        stateManager = WidgetStateManager(context)
        workManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkAll()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        context.getSharedPreferences("widget_state_prefs_test_default", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `provider change immediately repaints cache and queues required targeted refresh`() {
        val repaint = slot<OneTimeWorkRequest>()
        val refresh = slot<OneTimeWorkRequest>()

        ActualsProviderChangeCoordinator.apply(
            context = context,
            widgetStateManager = stateManager,
            displaySource = WeatherSource.OPEN_METEO,
            chosenProvider = WeatherSource.SYNOPTIC,
        )

        assertEquals(WeatherSource.SYNOPTIC, stateManager.getActualsProvider(WeatherSource.OPEN_METEO))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_UI),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                capture(repaint),
            )
        }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_ONE_TIME),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                capture(refresh),
            )
        }

        assertEquals(
            ActualsProviderChangeCoordinator.REASON,
            repaint.captured.workSpec.input.getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON),
        )
        with(refresh.captured.workSpec.input) {
            assertTrue(getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false))
            assertEquals(
                ActualsProviderChangeCoordinator.REASON,
                getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON),
            )
            assertEquals(
                WeatherSource.OPEN_METEO.id,
                getString(WeatherWidgetWorker.KEY_TARGET_SOURCE),
            )
        }
    }
}
