package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The blocking rule (plans/261111-tests-never-paint-real-widgets.md): only a widget that was on the
 * launcher before the tests started, and only while the app runs under tests.
 */
@Category(ShortDuration::class)
class TestModeWidgetGuardTest {
    private val real = setOf(69)

    @Test
    fun `under tests the user's real widget is blocked and test widgets are not`() {
        assertTrue(TestModeWidgetGuard.blocks(69, testMode = true, protected = real))
        assertFalse(TestModeWidgetGuard.blocks(913, testMode = true, protected = real))
    }

    @Test
    fun `outside tests nothing is blocked`() {
        assertFalse(TestModeWidgetGuard.blocks(69, testMode = false, protected = real))
    }
}
