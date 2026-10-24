package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ObservedPrecip
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

/**
 * The observed inputs every hourly rain / cloud / temperature graph shares, both platforms (audit
 * 261021 I): the elapsed-hour rain gate, the cloud curve capped at now, the borrowed-actuals label.
 */
@Category(ShortDuration::class)
class HourlyActualsInputsTest {
    private val hour = 3_600_000L

    @After
    fun reset() {
        ActualsProviderResolver.resetPreferenceSource()
        ActualsProviderResolver.resetLocationSource()
    }

    @Test
    fun `rain actual only for an hour that has begun`() {
        val now = LocalDateTime.parse("2026-10-24T14:20:00")
        val byHour = mapOf(LocalDateTime.parse("2026-10-24T13:00:00") to 1.2f, LocalDateTime.parse("2026-10-24T14:00:00") to 0.4f, LocalDateTime.parse("2026-10-24T15:00:00") to 9f)
        assertEquals(1.2f, ObservedPrecip.elapsedHourAmount(LocalDateTime.parse("2026-10-24T13:00:00"), now, byHour))
        // The hour in progress has begun: its amount so far counts.
        assertEquals(0.4f, ObservedPrecip.elapsedHourAmount(LocalDateTime.parse("2026-10-24T14:00:00"), now, byHour))
        // A future hour has no actual, whatever the rows hold.
        assertNull(ObservedPrecip.elapsedHourAmount(LocalDateTime.parse("2026-10-24T15:00:00"), now, byHour))
    }

    @Test
    fun `cloud curve spans the window but stops at now`() {
        // Desktop starts the window at its pre-roll point (09:00), not the minute-bearing centre.
        val nine = 1_800_000_000_000L
        val now = nine + 3 * hour + 45 * 60_000L
        val values = mapOf(
            nine - 15 * 60_000L to 10, // before the window
            nine to 20,
            nine + 3 * hour + 45 * 60_000L to 30, // a quarter-hour reading at now
            nine + 4 * hour to 40, // after now
        )
        val drawn = CloudActualSeries.pointsUntil(values, startMs = nine, endMs = nine + 4 * hour, nowMs = now)
        assertEquals(listOf(nine, now), drawn.map { it.timeMs })
    }

    @Test
    fun `an all-future window draws no cloud curve`() {
        assertTrue(CloudActualSeries.pointsUntil(mapOf(10 * hour to 50), 10 * hour, 12 * hour, nowMs = 5 * hour).isEmpty())
    }

    @Test
    fun `borrowed label only for a borrowing source with actuals drawn`() {
        ActualsProviderResolver.installPreferenceSource { if (it == WeatherSource.SILURIAN) WeatherSource.SYNOPTIC else null }
        assertTrue(ActualsProviderResolver.showsBorrowedLabel(WeatherSource.SILURIAN, drawsActuals = true))
        assertFalse("future-only window", ActualsProviderResolver.showsBorrowedLabel(WeatherSource.SILURIAN, drawsActuals = false))
        assertFalse("its own actuals", ActualsProviderResolver.showsBorrowedLabel(WeatherSource.NWS, drawsActuals = true))
    }
}
