package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence for the [GraphRepaintGate] data watermark and the screen-off paint-owed flag.
 *
 * These are the halves the pure gate tests cannot cover: the gate's upgrade branch is only reachable
 * if the store genuinely distinguishes "no watermark key on disk" from "watermark is zero", and the
 * paint-owed debt is only useful if it survives the process death that follows a screen-off skip.
 *
 * See `plans/260818-widget-repaint-gate-data-watermark.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetRenderStateWatermarkRoboTest {

    private val prefsName = "test_widget_render_watermark_prefs"
    private val widgetId = 7
    private val watermark = 1_787_097_000_000L // 2026-08-18 16:50 PDT

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(prefsName)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        stateManager = WidgetStateManager(context)
    }

    @After
    fun tearDown() {
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
    }

    @Test
    fun `watermark round-trips through the render state`() {
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(
                renderMs = 1_000L,
                displayedTemp = "73.1°",
                dataWatermarkMs = watermark,
            ),
        )

        val read = stateManager.getLastGraphRender(widgetId)
        assertEquals(watermark, read?.dataWatermarkMs)
        assertEquals("73.1°", read?.displayedTemp)
    }

    /**
     * The upgrade case. A render recorded before watermark tracking leaves the key absent; reading it
     * back as `0L` would look like a real watermark that never advances, and the gate would skip the
     * rebuild forever. It has to come back null so `watermark_absent` fires once.
     */
    @Test
    fun `render state written without a watermark reads back null`() {
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(renderMs = 1_000L, displayedTemp = "72°"),
        )

        val read = stateManager.getLastGraphRender(widgetId)
        assertEquals(1_000L, read?.renderMs)
        assertNull(read?.dataWatermarkMs)
    }

    /** NONE is a real value, not an absence — it must not be confused with the upgrade case above. */
    @Test
    fun `zero watermark is distinct from an absent one`() {
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(
                renderMs = 1_000L,
                displayedTemp = null,
                dataWatermarkMs = ObservationWatermark.NONE,
            ),
        )

        assertEquals(ObservationWatermark.NONE, stateManager.getLastGraphRender(widgetId)?.dataWatermarkMs)
    }

    /** A later render with no watermark must clear the stored one, not silently keep the old value. */
    @Test
    fun `writing a null watermark clears a previously stored one`() {
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(1_000L, "72°", watermark),
        )
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(2_000L, "72°", null),
        )

        assertNull(stateManager.getLastGraphRender(widgetId)?.dataWatermarkMs)
    }

    @Test
    fun `clearing a widget drops its watermark`() {
        stateManager.setLastGraphRender(
            widgetId,
            WidgetStateManager.LastGraphRenderState(1_000L, "72°", watermark),
        )

        stateManager.clearWidgetState(widgetId)

        assertNull(stateManager.getLastGraphRender(widgetId))
    }

    @Test
    fun `paint owed defaults to false`() {
        assertFalse(stateManager.isPaintOwed())
    }

    /**
     * The skip happens in a worker that may well be the last thing the process does before it is
     * killed; the flag is worthless if it does not outlive that.
     */
    @Test
    fun `paint owed survives a new manager instance`() {
        stateManager.setPaintOwed(true)

        assertTrue(WidgetStateManager(context).isPaintOwed())
    }

    @Test
    fun `paint owed clears`() {
        stateManager.setPaintOwed(true)
        stateManager.setPaintOwed(false)

        assertFalse(WidgetStateManager(context).isPaintOwed())
    }
}
