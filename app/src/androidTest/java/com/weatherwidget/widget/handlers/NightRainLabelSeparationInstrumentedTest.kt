package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.RainLabelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Real fonts (Robolectric measures 1 px per character, so labels never overlap there): the widget
 * renderer with the shared night-label layout draws Fri ".24in" and Sat "100%" apart. Since the scored
 * placement (plans/261116-night-rain-label-scored-placement-redesign.md) labels keep their INK apart, and
 * their line boxes may overlap in blank space, so this checks the rendered pixels.
 * Columns, temperatures and the 559×402 bitmap at scale 0.4316 copy the emulator widget of
 * 2026-11-06 19:27, where the two overlapped. (plans/261106-night-rain-labels-overlap-each-other.md)
 */
@RunWith(AndroidJUnit4::class)
class NightRainLabelSeparationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Col(val high: Float, val low: Float, val icon: Int, val day: String? = null, val peak: String? = null, val night: String? = null)

    private val today = LocalDate.of(2026, 11, 6)
    private val sunny = R.drawable.ic_weather_partly_cloudy
    private val rain = R.drawable.ic_weather_rain

    /** Tue 11-03 … Sat 11-14, as the emulator showed them. */
    private val cols = listOf(
        Col(65.9f, 48.4f, rain, day = "90%", night = "40%"),
        Col(68.2f, 55.1f, sunny),
        Col(75.9f, 49.6f, sunny),
        Col(72.8f, 47.6f, sunny),
        Col(71f, 51f, sunny),
        Col(71f, 53f, sunny),
        Col(71f, 52f, sunny),
        Col(73f, 51f, sunny),
        Col(73f, 51f, sunny),
        Col(64f, 52f, sunny, night = "40%"),
        Col(62f, 55f, rain, day = ".1in", peak = "7pm", night = ".24in"),
        Col(62f, 57f, rain, day = "90%", peak = "4pm", night = "100%"),
    )

    private fun days() = cols.mapIndexed { i, c ->
        val date = today.minusDays(3).plusDays(i.toLong())
        DailyForecastGraphRenderer.DayData(
            date = date,
            label = if (date == today) "Today" else date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            solidLineHigh = c.high,
            solidLineLow = c.low,
            iconRes = c.icon,
            isRainy = c.icon == rain,
            isToday = date == today,
            isPast = date.isBefore(today),
            daysFromToday = (date.toEpochDay() - today.toEpochDay()).toInt(),
            columnIndex = i,
            rainData = DailyForecastGraphRenderer.RainLabelData(
                dailyPrecipProbability = if (c.day != null) 100 else 0,
                nighttimePrecipProbability = if (c.night != null) 100 else 0,
                dailyRainLabelText = c.day,
                dailyRainPeakLabelText = c.peak,
                nightRainLabelText = c.night,
            ),
        )
    }

    /**
     * Rain-label-coloured pixels ([com.weatherwidget.widget.DailyBarRenderer.COLOR_FORECAST] #5AC8FA, or
     * its anti-aliased blend with the dark background) inside the rectangle where the two labels' line
     * boxes overlap. None means their ink cannot touch: outside that rectangle the boxes are apart.
     */
    private fun inkInBoxOverlap(bitmap: Bitmap, a: DailyRainLabelPlacement, b: DailyRainLabelPlacement): Int {
        // Whole pixels inside both boxes: truncating 301.97 counted row 301, which holds the upper
        // label's own bottom edge (its ink ends at 301.7) and is 97 % outside the lower box.
        val left = kotlin.math.ceil(maxOf(a.leftX, b.leftX)).toInt().coerceAtLeast(0)
        val right = kotlin.math.floor(minOf(a.rightX, b.rightX)).toInt().coerceAtMost(bitmap.width)
        val top = kotlin.math.ceil(maxOf(a.topY, b.topY)).toInt().coerceAtLeast(0)
        val bottom = kotlin.math.floor(minOf(a.bottomY, b.bottomY)).toInt().coerceAtMost(bitmap.height)
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF
            if (bl > 60 && r < 0.55f * bl && kotlin.math.abs(g - 0.8f * bl) < 0.15f * bl) count++
        }
        Log.i(TAG, "box overlap ${a.text}/${b.text}: x=$left..$right y=$top..$bottom labelPixels=$count")
        return count
    }

    @Test
    fun neighbouringNightLabelsAreDrawnApart() {
        val labels = mutableListOf<DailyRainLabelPlacement>()
        val result = DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = days(),
            widthPx = 559,
            heightPx = 402,
            bitmapScale = 0.43159938f,
            numColumns = cols.size,
            onRainLabelDrawn = labels::add,
            useCelsius = false,
        )
        val night = labels.filter { it.kind == RainLabelKind.NIGHT }.sortedBy { it.centerX }
        night.forEach {
            Log.i(TAG, "night ${it.date} \"${it.text}\" box=(${it.leftX},${it.topY},${it.rightX},${it.bottomY}) placement=${it.placement}")
        }
        assertEquals(listOf("40%", "40%", ".24in", "100%"), night.map { it.text })
        val fri = night.first { it.text == ".24in" }
        val sat = night.first { it.text == "100%" }
        assertEquals(
            "Fri .24in (${fri.leftX}..${fri.rightX}, ${fri.topY}..${fri.bottomY}) ink touches Sat 100% " +
                "(${sat.leftX}..${sat.rightX}, ${sat.topY}..${sat.bottomY})",
            0,
            inkInBoxOverlap(result.bitmap, fri, sat),
        )
        night.zipWithNext().forEach { (a, b) -> assertEquals("${a.text} ink touches ${b.text}", 0, inkInBoxOverlap(result.bitmap, a, b)) }
        assertTrue("Sat 100% stays inside the bitmap", sat.rightX <= 559f)
    }

    private companion object {
        const val TAG = "NightLabelSeparationIT"
    }
}
