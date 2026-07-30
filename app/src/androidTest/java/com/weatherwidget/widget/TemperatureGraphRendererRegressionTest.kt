package com.weatherwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.shared.graph.HourData
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TemperatureGraphRendererRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unobstructedFetchAgeStaysBelowDotAndBaseFillPaintRemainsImmutable() {
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)
        val hours =
            listOf(50f, 52f, 54f, 52f, 50f).mapIndexed { index, temperature ->
                HourData(
                    dateTime = start.plusHours(index.toLong()),
                    temperature = temperature,
                    label = "${start.plusHours(index.toLong()).hour}h",
                    showLabel = false,
                    isCurrentHour = index == 2,
                )
            }
        val observedAt =
            start
                .plusHours(2)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val cachedFillPaint = TemperatureGraphStyle.ensurePaints(context, 1f).expectedFillPaint
        assertNull(cachedFillPaint.shader)
        var fetchDot: FetchDotDebug? = null
        val fetchInput =
            TemperatureFetchDotRenderer.Input(
                context = context,
                canvas = Canvas(Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)),
                widthPx = 800,
                heightPx = 300,
                labelScale = 1f,
                graphTop = 20f,
                graphHeight = 200f,
                minTemp = 40f,
                tempRange = 20f,
                fetchTime = start.plusHours(2),
                fetchDotX = 400f,
                lastObservedTemp = 50f,
                observedAt = observedAt,
                currentTime = start.plusHours(2).plusMinutes(25),
                hours = hours,
                paints = TemperatureGraphStyle.ensurePaints(context, 1f),
                useCelsius = false,
                onResolved = { fetchDot = it },
            )
        val fetchPlan = requireNotNull(TemperatureFetchDotRenderer.plan(fetchInput))
        val obstacles = TemperatureGraphObstacleRegistry()
        TemperatureFetchDotRenderer.reserve(fetchPlan, obstacles)
        TemperatureFetchDotRenderer.draw(fetchPlan, fetchInput, obstacles)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start.plusHours(2).plusMinutes(25),
            appliedDelta = 2f,
            observedAt = observedAt,
            lastObservedTemp = 52f,
            useCelsius = false,
        )

        val resolved = requireNotNull(fetchDot)
        val fetchY = requireNotNull(resolved.fetchY)
        val ageY = requireNotNull(resolved.stalenessLabelY)
        assertTrue(
            "Unobstructed age label must remain below the fetch dot: " +
                "ageY=$ageY fetchY=$fetchY plan=${fetchPlan.staleness} " +
                "obstacles=${obstacles.snapshot()}",
            ageY > fetchY,
        )
        assertNull("Render-specific gradient leaked into cached PaintSet", cachedFillPaint.shader)
    }

    @Test
    fun parallelSameScaleRendersMatchSerialBitmaps() {
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)

        fun hours(base: Float): List<HourData> =
            (0..6).map { index ->
                HourData(
                    dateTime = start.plusHours(index.toLong()),
                    temperature = base + index * 2f,
                    label = "${start.plusHours(index.toLong()).hour}h",
                    showLabel = index % 2 == 0,
                    isCurrentHour = index == 2,
                )
            }

        fun render(
            values: List<HourData>,
            width: Int,
            height: Int,
            lastObservedTemp: Float,
        ) = TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = values,
            widthPx = width,
            heightPx = height,
            currentTime = start.plusHours(2),
            appliedDelta = 2f,
            observedAt =
                start
                    .plusHours(2)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            lastObservedTemp = lastObservedTemp,
            useCelsius = false,
        )

        val coldHours = hours(20f)
        val hotHours = hours(80f)
        val expectedCold = render(coldHours, 640, 280, 25f)
        val expectedHot = render(hotHours, 920, 420, 85f)
        val executor = Executors.newFixedThreadPool(2)

        try {
            repeat(4) {
                val cold = executor.submit<Bitmap> { render(coldHours, 640, 280, 25f) }
                val hot = executor.submit<Bitmap> { render(hotHours, 920, 420, 85f) }
                assertTrue(expectedCold.sameAs(cold.get(20, TimeUnit.SECONDS)))
                assertTrue(expectedHot.sameAs(hot.get(20, TimeUnit.SECONDS)))
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
