package com.weatherwidget.desktop.theme

import com.weatherwidget.shared.util.WeatherThemeTokens
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Desktop half of plans/261113-button-press-feedback.md: press target and release easing. */
@Category(ShortDuration::class)
class ButtonPressAnimationTest {

    @Test
    fun pressedEnabledButtonShrinksToSharedScale() {
        assertEquals(WeatherThemeTokens.BUTTON_PRESS_SCALE, buttonPressScaleTarget(pressed = true, enabled = true), 0f)
        assertEquals(1f, buttonPressScaleTarget(pressed = false, enabled = true), 0f)
        assertEquals("disabled buttons never shrink", 1f, buttonPressScaleTarget(pressed = true, enabled = false), 0f)
    }

    @Test
    fun releaseEasingOvershootsThenSettlesLikeAndroid() {
        val easing = overshoot(WeatherThemeTokens.BUTTON_PRESS_OUT_OVERSHOOT_TENSION)
        assertEquals(0f, easing.transform(0f), 1e-6f)
        assertEquals(1f, easing.transform(1f), 1e-6f)
        val peak = (0..100).maxOf { easing.transform(it / 100f) }
        println("ButtonPressAnimation: overshoot peak=$peak")
        assertTrue("release must run past full size for a visible bounce, peak=$peak", peak > 1.05f)
        // Android OvershootInterpolator(2) at t=0.5: (-.5)^2*(3*-.5+2)+1 = 1.125.
        assertEquals(1.125f, easing.transform(0.5f), 1e-6f)
    }
}
