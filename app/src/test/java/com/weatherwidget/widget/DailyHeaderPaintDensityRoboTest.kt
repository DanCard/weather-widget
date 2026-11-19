package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The header's cached paints must follow screen density. Android keeps the process alive across a
 * density change (Settings → Display → Screen zoom; verified on the emulator 2026-11-19: same pid
 * through `wm density 480`), and the cache key once lacked density, so the header kept text sized
 * for the old density while everything around it rescaled.
 *
 * Real header layout + real graph layout resolver: every header size is dp-based, so doubling the
 * density roughly doubles the header's ink bottom. Both densities use the same `labelScale`, which
 * is the case the old key could not tell apart.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyHeaderPaintDensityRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 7, 27)
    private val days = (0 until 4).map { i ->
        DailyForecastGraphRenderer.DayData(
            date = start.plusDays(i.toLong()),
            label = "D$i",
            solidLineHigh = 60f + i,
            solidLineLow = 45f + i,
        )
    }
    private val header = DailyForecastGraphRenderer.HeaderRenderData(currentTempText = "72°", dateText = "Mon, Jul 27")

    private fun inkBottomAt(density: Float): Float {
        // Same dp size at each density, so only the px scale differs.
        val widthPx = (400 * density).toInt()
        val heightPx = (200 * density).toInt()
        val layout = DailyGraphLayoutResolver.resolve(
            days = days, widthPx = widthPx, heightPx = heightPx, columns = 4, bitmapScale = 1f,
            density = density, useCelsius = false,
        )
        return DailyForecastHeaderRenderer.resolveHeaderInkBottom(header, widthPx, layout, 0f, widthPx.toFloat())
            .also { println("DailyHeaderPaintDensity: density=$density widthPx=$widthPx inkBottom=$it") }
    }

    @Test
    fun `header text rescales when density changes in the same process`() {
        val atTwo = inkBottomAt(2f)
        val atFour = inkBottomAt(4f) // a stale cache would reuse the 2.0 paints here
        val backAtTwo = inkBottomAt(2f)

        assertEquals("doubling density doubles the header (within 5%)", 2f, atFour / atTwo, 0.1f)
        assertEquals("returning to the first density restores it", atTwo, backAtTwo, 0.01f)
    }
}
