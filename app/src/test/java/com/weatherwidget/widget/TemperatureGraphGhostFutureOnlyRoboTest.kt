package com.weatherwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The ghost (forecast shifted by the observed-minus-forecast delta) is a projection forward from
 * the latest observation, so neither its dotted stroke nor its gradient fill may paint left of the
 * fetch dot. The fill once spanned the full window, painting a grey "ghost" under the past.
 *
 * Real renderer + series resolver + style, rasterized with native graphics: sample a column left of
 * the fetch dot and one right of it, in the band between the ghost curve and the plot bottom where
 * only the ghost fill paints.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemperatureGraphGhostFutureOnlyRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `ghost fill paints only right of the fetch dot`() {
        val start = LocalDateTime.of(2026, 11, 26, 10, 0)
        val nowHour = 6
        val hours = (0..12).map { i ->
            val past = i <= nowHour
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = 70f,
                label = "${10 + i}h",
                showLabel = i % 3 == 0,
                isCurrentHour = i == nowHour,
                isActual = past,
                actualTemperature = if (past) 60f else null,
                isObservedActual = past,
            )
        }
        val observedAt = start.plusHours(nowHour.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var fetchDotX: Float? = null
        val width = 1200
        val height = 500
        val bitmap: Bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = width,
            heightPx = height,
            currentTime = start.plusHours(nowHour.toLong()).plusMinutes(10),
            appliedDelta = -10f,
            observedAt = observedAt,
            lastObservedTemp = 60f,
            onGhostLineDebug = { fetchDotX = it.startX },
            useCelsius = false,
        )
        val dotX = requireNotNull(fetchDotX) { "ghost gate must pass in this narrow, now-visible view" }

        fun paintedRows(x: Int): Int = (0 until height).count { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        // Rows painted in a column: the fill fades out downward, so its visible run is ~70 rows here
        // (pre-fix the past column also painted ~74); without it only the thin strokes remain (~4).
        val pastX = (dotX * 0.25f).toInt()
        val futureX = (dotX + (width - dotX) * 0.6f).toInt()
        val pastPainted = paintedRows(pastX)
        val futurePainted = paintedRows(futureX)
        println("GhostFutureOnly: width=$width dotX=$dotX pastX=$pastX pastPainted=$pastPainted futureX=$futureX futurePainted=$futurePainted")

        assertTrue("future column carries the ghost fill (tall painted run)", futurePainted >= 40)
        assertTrue(
            "past column has no ghost fill: only the thin forecast/actual strokes paint (rows=$pastPainted)",
            pastPainted <= 12,
        )
        assertEquals("fetch dot sits inside the plot", true, dotX in 0f..width.toFloat())
    }
}
