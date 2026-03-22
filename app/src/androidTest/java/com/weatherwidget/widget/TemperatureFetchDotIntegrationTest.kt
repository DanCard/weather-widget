package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TemperatureFetchDotIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fetchDotCallbackReflectsNewActualSeriesAnchorAcrossRenders() {
        val startTime = LocalDateTime.of(2026, 2, 26, 10, 0)
        val currentTime = startTime.plusHours(2)
        val hours =
            (0..7).map { index ->
                val dt = startTime.plusHours(index.toLong())
                TemperatureGraphRenderer.HourData(
                    dateTime = dt,
                    temperature = 60f + index,
                    label = dt.hour.toString(),
                    isCurrentHour = dt == currentTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                    showLabel = true,
                )
            }

        val firstObservedAt = currentTime.minusMinutes(25).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val secondObservedAt = currentTime.minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = mutableListOf<TemperatureGraphRenderer.FetchDotDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 320,
            currentTime = currentTime,
            observedAt = firstObservedAt,
            onFetchDotResolved = { events.add(it) },
        )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 320,
            currentTime = currentTime,
            observedAt = secondObservedAt,
            onFetchDotResolved = { events.add(it) },
        )

        assertEquals("Should emit one fetch-dot callback per render", 2, events.size)
        assertEquals(firstObservedAt, events[0].observedAt)
        assertEquals(secondObservedAt, events[1].observedAt)
        assertTrue(events[0].withinWindow)
        assertTrue(events[1].withinWindow)
        assertNotNull(events[0].fetchDotX)
        assertNotNull(events[1].fetchDotX)
        assertTrue("Later timestamp should resolve to the right", events[1].fetchDotX!! > events[0].fetchDotX!!)
    }

    @Test
    fun stalenessIndicatorAppearsInNarrowViewWithHighFrequencyData() {
        val startTime = LocalDateTime.of(2026, 3, 21, 10, 0)
        val currentTime = startTime.plusHours(2).plusMinutes(30)
        
        // 4 hour duration (narrow) but with many points (every 5 mins) -> 48 points
        val hours = (0..48).map { index ->
            val dt = startTime.plusMinutes(index.toLong() * 5)
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = 65f,
                label = dt.hour.toString(),
                isCurrentHour = dt == currentTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                showLabel = index % 12 == 0,
            )
        }

        val observedAtMs = startTime.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = mutableListOf<TemperatureGraphRenderer.FetchDotDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = currentTime,
            observedAt = observedAtMs,
            onFetchDotResolved = { events.add(it) },
        )

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("30m", event.ageText)
    }
}
