package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



/**
 * Regression tests ensuring the fetch dot uses the real lastObservedTemp value
 * passed through from the header resolver, not a reconstructed graph-derived value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TruthCurveLinearRenderingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Verifies that the fetch dot uses lastObservedTemp directly.
     *
     * The dot label and position must reflect the lastObservedTemp passed in, and
     * the observedAt timestamp on the debug callback must be the real observation time.
     */
    @Test
    fun `fetch dot observedAt reflects actual observation time not blend candidate time`() {
        val startTime = LocalDateTime.of(2026, 3, 23, 17, 0)
        val observationTime = startTime.plusMinutes(25) // 17:25 — the real observation
        val renderTime = startTime.plusMinutes(33)       // 17:33 — "now"

        val observedAtMs = observationTime
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val hours = (0..5).map { i ->
            HourData(
                dateTime = startTime.plusHours(i.toLong()),
                temperature = 73f - i * 2f,   // falling forecast: 73, 71, 69, 67, 65, 63
                label = "${startTime.plusHours(i.toLong()).hour}",
                showLabel = true,
                isActual = i == 0,
                actualTemperature = if (i == 0) 75f else null,
                isCurrentHour = i == 0,
            )
        }

        var dotDebug: FetchDotDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 200,
            currentTime = renderTime,
            observedAt = observedAtMs,
            lastObservedTemp = 75.0f,
            onFetchDotResolved = { dotDebug = it },
        )

        assertNotNull("Fetch dot should be rendered when observedAt is within the graph window", dotDebug)
        assertEquals(
            "observedAt on the dot must be the real observation time, not a drifted blend candidate",
            observedAtMs,
            dotDebug!!.observedAt,
        )

        // Age label: renderTime (17:33) - observationTime (17:25) = 8 minutes
        // Dot shows lastObservedTemp = 75.0°
        assertEquals("Age label should reflect lastObservedTemp and true observation age", "75° (8m)", dotDebug?.ageText)
    }

    /**
     * Verifies that the fetch dot renders when lastObservedTemp is provided.
     */
    @Test
    fun `fetch dot renders when lastObservedTemp is provided`() {
        val startTime = LocalDateTime.of(2026, 3, 23, 10, 0)
        val temps = listOf(80f, 70f, 60f)

        val observedAtMs = startTime.plusMinutes(30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val hours = temps.mapIndexed { i, temp ->
            HourData(
                dateTime = startTime.plusHours(i.toLong()),
                temperature = temp,
                label = "${startTime.plusHours(i.toLong()).hour}",
                showLabel = true,
                isActual = i <= 1,
                actualTemperature = if (i <= 1) temp else null,
                isCurrentHour = i == 1,
            )
        }

        var dotDebug: FetchDotDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 600,
            heightPx = 200,
            currentTime = startTime.plusHours(2),
            observedAt = observedAtMs,
            lastObservedTemp = 75.0f,
            appliedDelta = 0f,
            onFetchDotResolved = { dotDebug = it },
        )

        assertNotNull("Fetch dot should appear within the graph window", dotDebug)
        assertNotNull("fetchDotX must be resolved", dotDebug!!.fetchDotX)
    }
}
