package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TemperatureGraphJunctionTest {

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class)
        mockkStatic(Color::class)
        mockkStatic(Paint::class)
        mockkStatic(Typeface::class)
        mockkStatic(TypedValue::class)
        mockkConstructor(Canvas::class)
        mockkConstructor(Paint::class)
        mockkConstructor(Path::class)

        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } returns mockk()
        every { Color.parseColor(any()) } returns 0
        every { Color.red(any()) } returns 0
        every { Color.green(any()) } returns 0
        every { Color.blue(any()) } returns 0
        every { Color.rgb(any<Int>(), any<Int>(), any<Int>()) } returns 0
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns 0
        
        every { Typeface.create(any<Typeface>(), any()) } returns mockk()
        
        // Mock TypedValue.applyDimension for dpToPx
        every { TypedValue.applyDimension(any(), any(), any()) } answers { it.invocation.args[1] as Float }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `solid actual line ends exactly at fetch dot Y position`() {
        val context = mockk<Context>(relaxed = true)
        val start = LocalDateTime.of(2026, 3, 16, 10, 0)
        
        // Setup hours: forecast is 60.
        val hours = (0..7).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 2,
                isActual = offset <= 1,
            )
        }
        
        // Fetch happened at 11:00 (index 1)
        val fetchedAtMs = start.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTime = start.plusHours(2)
        val appliedDelta = 5.0f

        var resolvedFetchY: Float? = null
        var originalPoints: List<Pair<Float, Float>>? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = currentTime,
            appliedDelta = appliedDelta,
            observedTempFetchedAt = fetchedAtMs,
            onFetchDotResolved = { resolvedFetchY = it.fetchY },
            onPointsResolved = { originalPoints = it.original }
        )

        assertTrue("Fetch dot Y should be resolved", resolvedFetchY != null)
        assertTrue("Original points should be resolved", originalPoints != null)
        
        // In this test, fetch is exactly at index 1.
        val solidLineEndY = originalPoints!![1].second
        
        println("Fetch Dot Y: $resolvedFetchY")
        println("Solid Line End Y (Original Path): $solidLineEndY")
        
        // They MUST match exactly. If they don't, there's a visual gap.
        assertEquals("Solid line end and fetch dot should be at same Y", resolvedFetchY!!, solidLineEndY, 0.01f)
    }
    
    private fun assertTrue(msg: String, condition: Boolean) {
        if (!condition) throw AssertionError(msg)
    }
}
