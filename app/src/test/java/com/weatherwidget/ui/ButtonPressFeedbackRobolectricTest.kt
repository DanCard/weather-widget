package com.weatherwidget.ui

import android.content.Context
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.util.WeatherThemeTokens
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * plans/261113-button-press-feedback.md: full-screen buttons showed nothing when pressed because
 * the solid `rounded_button_*` shapes replaced the theme's ripple background. These tests drive the
 * real view state (`isPressed`) and jump the theme-supplied StateListAnimator to its end, so they
 * fail if the animator is missing *or* is still the framework's elevation-only default (which never
 * scales).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ButtonPressFeedbackRobolectricTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        listOf("weather_widget_prefs", "widget_state_prefs", "weather_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /** Press [view], settle its animator, return the scale; then release and return the settled scale. */
    private fun pressAndRelease(view: View): Pair<Float, Float> {
        val animator = view.stateListAnimator
        assertNotNull("${view.javaClass.simpleName} has no stateListAnimator", animator)
        view.isPressed = true
        animator.jumpToCurrentState()
        val pressed = view.scaleX
        println("ButtonPressFeedback: ${view.javaClass.simpleName} pressed scaleX=$pressed scaleY=${view.scaleY}")
        assertEquals("scaleX and scaleY must shrink together", pressed, view.scaleY, 0.0001f)
        view.isPressed = false
        animator.jumpToCurrentState()
        println("ButtonPressFeedback: ${view.javaClass.simpleName} released scaleX=${view.scaleX}")
        return pressed to view.scaleX
    }

    @Test
    fun `settings refresh data button ripples and shrinks when pressed`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val refresh = activity.findViewById<Button>(R.id.refresh_data_button)
                assertTrue(
                    "Refresh Data background must be a ripple, was ${refresh.background?.javaClass?.simpleName}",
                    refresh.background is RippleDrawable,
                )
                val (pressed, released) = pressAndRelease(refresh)
                assertEquals(WeatherThemeTokens.BUTTON_PRESS_SCALE, pressed, 0.0001f)
                assertEquals(1f, released, 0.0001f)
            }
        }
    }

    @Test
    fun `image buttons and borderless buttons also shrink when pressed`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val back = activity.findViewById<ImageButton>(R.id.back_button)
                assertEquals(WeatherThemeTokens.BUTTON_PRESS_SCALE, pressAndRelease(back).first, 0.0001f)

                // Same resolution path as activity_main's style="?android:attr/borderlessButtonStyle".
                val borderless = Button(activity, null, android.R.attr.borderlessButtonStyle)
                assertEquals(WeatherThemeTokens.BUTTON_PRESS_SCALE, pressAndRelease(borderless).first, 0.0001f)
            }
        }
    }

    @Test
    fun `disabled button does not shrink when pressed`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val refresh = activity.findViewById<Button>(R.id.refresh_data_button)
                refresh.isEnabled = false
                assertEquals(1f, pressAndRelease(refresh).first, 0.0001f)
            }
        }
    }
}
