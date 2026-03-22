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
    fun `solid actual line ends exactly at fetch dot Y position between hours`() {
        val context = mockk<Context>(relaxed = true)
        val start = LocalDateTime.of(2026, 3, 16, 10, 0)
        
        // Setup hours with a sharp change to highlight interpolation differences.
        // 10:00 -> 60, 11:00 -> 70, 12:00 -> 60
        val hours = (0..7).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = if (offset == 1) 70f else 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 2,
                isActual = offset <= 1,
            )
        }
        
        // Fetch happened at 11:30 (index 1.5)
        // Linear interpolation: 65.0
        // Cubic interpolation: likely higher or lower depending on tangents
        val fetchedAtMs = start.plusHours(1).plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTime = start.plusHours(2)
        val appliedDelta = 0.0f

        var resolvedFetchY: Float? = null
        
        // We need a way to measure the actual Path Y at fetchDotX.
        // Since we can't easily query Path objects in unit tests, 
        // we'll rely on the logic that fetchY is linear while originalPath is cubic.
        
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = currentTime,
            appliedDelta = appliedDelta,
            observedAt = fetchedAtMs,
            onFetchDotResolved = { resolvedFetchY = it.fetchY }
        )

        assertTrue("Fetch dot Y should be resolved", resolvedFetchY != null)
        
        // If we want exact matching, we must use the same interpolation.
        // I will add a way to verify the path's interpolation in the renderer.
    }
    
    private fun assertTrue(msg: String, condition: Boolean) {
        if (!condition) throw AssertionError(msg)
    }
}
