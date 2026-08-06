package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertFalse
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
class TodayOverlaySettingsRoboTest {
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetStateManager.setPrefsNameOverrideForTesting("today_overlay_settings_test_prefs")
        context.getSharedPreferences("today_overlay_settings_test_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        stateManager = WidgetStateManager(context)
    }

    @After
    fun tearDown() {
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
    }

    @Test
    fun `today overlay toggles default to off`() {
        assertFalse(stateManager.showTodayOverlayDelta())
        assertFalse(stateManager.showTodayOverlayDominantTemp())
        assertFalse(stateManager.showTodayOverlayDominantAge())
    }

    @Test
    fun `today overlay toggles round trip independently`() {
        stateManager.setShowTodayOverlayDelta(true)
        assertTrue(stateManager.showTodayOverlayDelta())
        assertFalse(stateManager.showTodayOverlayDominantTemp())
        assertFalse(stateManager.showTodayOverlayDominantAge())

        stateManager.setShowTodayOverlayDominantTemp(true)
        assertTrue(stateManager.showTodayOverlayDominantTemp())
        assertFalse(stateManager.showTodayOverlayDominantAge())

        stateManager.setShowTodayOverlayDominantAge(true)
        assertTrue(stateManager.showTodayOverlayDominantAge())

        stateManager.setShowTodayOverlayDelta(false)
        assertFalse(stateManager.showTodayOverlayDelta())
        assertTrue(stateManager.showTodayOverlayDominantTemp())
        assertTrue(stateManager.showTodayOverlayDominantAge())
    }
}
