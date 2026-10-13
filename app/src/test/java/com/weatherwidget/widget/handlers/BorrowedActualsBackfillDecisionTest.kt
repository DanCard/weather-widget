package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Emulator 2026-10-13, end to end through the real pieces the daily view chains: provider
 * resolution ([backfillActualsSource] → `ActualsProviderResolver`), the daily-view gate
 * ([DailyHistoryBackfillCoordinator.shouldProbeHistoryBackfill]) and the coverage decision
 * ([evaluateHourlyBackfillNeed]).
 *
 * Google displayed at Mountain View; the device was off overnight. NWS rows stop at 14:20
 * yesterday and resume at 08:10 today, while Synoptic reported all night. Before the fix the gate
 * asked "is NWS displayed?" and never got as far as the decision.
 */
@Category(ShortDuration::class)
class BorrowedActualsBackfillDecisionTest {
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 13, 9, 25)
    private val lat = 37.417
    private val lon = -122.089

    private fun obs(api: String, station: String, t: LocalDateTime, temp: Float) = ObservationEntity(
        stationId = station,
        stationName = station,
        timestamp = t.atZone(zone).toInstant().toEpochMilli(),
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = api,
    )

    /** Every 20 min from [from] to [until], at two stations so none is a singleton. */
    private fun series(api: String, from: LocalDateTime, until: LocalDateTime): List<ObservationEntity> =
        generateSequence(from) { it.plusMinutes(20) }.takeWhile { !it.isAfter(until) }
            .flatMap { t -> listOf(obs(api, "${api}_A", t, 55f), obs(api, "${api}_B", t, 56f)) }
            .toList()

    private val emulatorMorning =
        series(WeatherSource.NWS.id, now.minusHours(72), LocalDateTime.of(2026, 10, 12, 14, 20)) +
            series(WeatherSource.NWS.id, LocalDateTime.of(2026, 10, 13, 8, 10), LocalDateTime.of(2026, 10, 13, 9, 15)) +
            series("SYNOPTIC", now.minusHours(72), now)

    @Test
    fun `Google inside NWS coverage reaches the decision and requests the NWS backfill`() {
        val provider = backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon, preference = { null })
        assertEquals(WeatherSource.NWS, provider)
        assertTrue(DailyHistoryBackfillCoordinator.shouldProbeHistoryBackfill(provider, now.toLocalDate(), now.toLocalDate()))

        val decision = evaluateHourlyBackfillNeed(provider, now.minusHours(72), now, emulatorMorning, now)

        assertTrue(decision.reason, decision.shouldRequest)
    }

    @Test
    fun `a dense Synoptic night does not hide the NWS gap`() {
        // Judged on the provider's rows only: Synoptic's 00:00–08:10 coverage is not NWS's.
        val decision = evaluateHourlyBackfillNeed(WeatherSource.NWS, now.minusHours(72), now, emulatorMorning, now)
        assertTrue(decision.reason, decision.reason.startsWith("max_gap_min") || decision.reason.startsWith("day_start_uncovered"))
    }

    @Test
    fun `once NWS covers the night, no backfill is requested`() {
        val healed = series(WeatherSource.NWS.id, now.minusHours(72), now.minusMinutes(5))
        val provider = backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon, preference = { null })
        val decision = evaluateHourlyBackfillNeed(provider, now.minusHours(72), now, healed, now)
        assertFalse(decision.reason, decision.shouldRequest)
    }
}
