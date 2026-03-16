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

class TemperatureGraphRendererContinuityTest {

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
    fun `fetch dot and ghost line start at same Y position`() {
        val context = mockk<Context>(relaxed = true)
        val start = LocalDateTime.of(2026, 3, 16, 10, 0)
        
        // Setup hours: forecast is 60, observation is 65.
        // appliedDelta = 5.0
        val hours = (0..7).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 3,
                isActual = offset <= 2,
                actualTemperature = if (offset <= 2) 65f else null
            )
        }
        
        // Fetch happened at 12:30 (index 2.5)
        val fetchedAtMs = start.plusHours(2).plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTime = start.plusHours(3)
        val appliedDelta = 5.0f

        var resolvedFetchY: Float? = null
        var ghostLineStartY: Float? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = currentTime,
            appliedDelta = appliedDelta,
            observedTempFetchedAt = fetchedAtMs,
            onFetchDotResolved = { resolvedFetchY = it.fetchY },
            onGhostLineDebug = { ghostLineStartY = it.startY }
        )

        println("Fetch Dot Y: $resolvedFetchY")
        println("Ghost Line Start Y: $ghostLineStartY")
        
        assertTrue("Fetch dot Y should be resolved", resolvedFetchY != null)
        assertTrue("Ghost line start Y should be resolved", ghostLineStartY != null)
        
        assertEquals("Fetch dot and ghost line should start at the same Y", resolvedFetchY!!, ghostLineStartY!!, 0.01f)
    }
    
    private fun assertTrue(msg: String, condition: Boolean) {
        if (!condition) throw AssertionError(msg)
    }
}
