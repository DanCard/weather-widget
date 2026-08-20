package com.weatherwidget.shared.notify

import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DominantTempWatchTest {

    private fun contribution(
        stationId: String = "KNUQ",
        rawTemp: Float = 68f,
        isSynthetic: Boolean = false,
    ) = BlendContribution(
        stationId = stationId,
        stationName = stationId,
        stationType = "OFFICIAL",
        distanceKm = 3f,
        lastReadingMs = 1_700_000_000_000L,
        rawTemp = rawTemp,
        resolvedTemp = rawTemp,
        sourceKind = "observed",
        ageMs = 600_000L,
        weight = 1.0,
        weightShare = 1.0,
        isSynthetic = isSynthetic,
    )

    private val armed = DominantTempWatchState(armed = true)
    private val primed = DominantTempWatchState(
        armed = true,
        baselineStationId = "KNUQ",
        baselineTempF = 68f,
    )

    @Test
    fun `disarmed is idle even with a changed reading`() {
        val decision = DominantTempWatch.evaluate(
            state = DominantTempWatchState.DISARMED,
            dominant = contribution(rawTemp = 99f),
            useCelsius = false,
        )
        assertEquals(DominantTempWatchDecision.Idle, decision)
    }

    @Test
    fun `armed with no dominant station holds`() {
        val decision = DominantTempWatch.evaluate(armed, dominant = null, useCelsius = false)
        assertEquals(DominantTempWatchDecision.Hold("no_dominant"), decision)
    }

    @Test
    fun `synthetic backfill row never primes the baseline`() {
        val decision = DominantTempWatch.evaluate(
            state = armed,
            dominant = contribution(stationId = "OPEN_METEO_MAIN", isSynthetic = true),
            useCelsius = false,
        )
        assertEquals(DominantTempWatchDecision.Hold("synthetic"), decision)
    }

    @Test
    fun `synthetic row cannot fire against an existing baseline either`() {
        val decision = DominantTempWatch.evaluate(
            state = primed,
            dominant = contribution(stationId = "OPEN_METEO_MAIN", rawTemp = 80f, isSynthetic = true),
            useCelsius = false,
        )
        assertEquals(DominantTempWatchDecision.Hold("synthetic"), decision)
    }

    @Test
    fun `first real reading after arming captures the baseline without firing`() {
        val decision = DominantTempWatch.evaluate(armed, contribution(rawTemp = 68f), useCelsius = false)
        val capture = decision as DominantTempWatchDecision.Capture
        assertTrue(capture.state.armed)
        assertEquals("KNUQ", capture.state.baselineStationId)
        assertEquals(68f, capture.state.baselineTempF!!, 0.0001f)
    }

    @Test
    fun `identical reading holds`() {
        val decision = DominantTempWatch.evaluate(primed, contribution(rawTemp = 68f), useCelsius = false)
        assertEquals(DominantTempWatchDecision.Hold("unchanged"), decision)
    }

    @Test
    fun `a change too small to display holds`() {
        // 69.87 and 69.92 both render "69.9°"; firing here would print "was 69.9°" beside "69.9°".
        val state = primed.copy(baselineTempF = 69.87f)
        val decision = DominantTempWatch.evaluate(state, contribution(rawTemp = 69.92f), useCelsius = false)
        assertEquals(DominantTempWatchDecision.Hold("unchanged"), decision)
    }

    @Test
    fun `changed reading fires with the requested message`() {
        val decision = DominantTempWatch.evaluate(primed, contribution(rawTemp = 69.9f), useCelsius = false)
        val fire = decision as DominantTempWatchDecision.Fire
        assertEquals("KNUQ 69.9°, was 68°", fire.body)
        assertEquals(DominantTempWatchStrings().title, fire.title)
    }

    @Test
    fun `caller-supplied wording is used verbatim`() {
        // Android passes localized resources here; the format arg order is the contract.
        val strings = DominantTempWatchStrings(
            title = "Stationstemperatur geändert",
            bodyFormat = "%1\$s %2\$s, vorher %3\$s",
            bodyStationChangedFormat = "%1\$s %2\$s, vorher %3\$s %4\$s",
        )
        val fire = DominantTempWatch.evaluate(primed, contribution(rawTemp = 69.9f), useCelsius = false, strings = strings)
            as DominantTempWatchDecision.Fire
        assertEquals("Stationstemperatur geändert", fire.title)
        assertEquals("KNUQ 69.9°, vorher 68°", fire.body)

        val handover = DominantTempWatch.evaluate(
            primed,
            contribution(stationId = "KSJC", rawTemp = 72f),
            useCelsius = false,
            strings = strings,
        ) as DominantTempWatchDecision.Fire
        assertEquals("KSJC 72°, vorher KNUQ 68°", handover.body)
    }

    @Test
    fun `firing disarms and clears the baseline`() {
        val fire = DominantTempWatch.evaluate(primed, contribution(rawTemp = 69.9f), useCelsius = false)
            as DominantTempWatchDecision.Fire
        assertEquals(false, fire.state.armed)
        assertNull(fire.state.baselineTempF)
        assertNull(fire.state.baselineStationId)
    }

    @Test
    fun `the watch is one-shot - re-evaluating the fired state is idle`() {
        val fire = DominantTempWatch.evaluate(primed, contribution(rawTemp = 69.9f), useCelsius = false)
            as DominantTempWatchDecision.Fire
        val again = DominantTempWatch.evaluate(fire.state, contribution(rawTemp = 71f), useCelsius = false)
        assertEquals(DominantTempWatchDecision.Idle, again)
    }

    @Test
    fun `celsius formats both numbers from the same stored fahrenheit baseline`() {
        // 68F = 20C exactly; 69.9F = 21.06C -> "21.1°".
        val fire = DominantTempWatch.evaluate(primed, contribution(rawTemp = 69.9f), useCelsius = true)
            as DominantTempWatchDecision.Fire
        assertEquals("KNUQ 21.1°, was 20°", fire.body)
    }

    @Test
    fun `a new dominant station with a different value names both stations`() {
        val decision = DominantTempWatch.evaluate(
            state = primed,
            dominant = contribution(stationId = "KSJC", rawTemp = 72f),
            useCelsius = false,
        )
        val fire = decision as DominantTempWatchDecision.Fire
        assertEquals("KSJC 72°, was KNUQ 68°", fire.body)
    }

    @Test
    fun `a handover at an identical temperature still fires`() {
        // The degrees agree but a different thermometer is now driving them — a change in what the
        // app is telling you, and the message has to name the station it replaced or it reads as
        // "KSJC 68°, was 68°".
        val decision = DominantTempWatch.evaluate(
            state = primed,
            dominant = contribution(stationId = "KSJC", rawTemp = 68f),
            useCelsius = false,
        )
        val fire = decision as DominantTempWatchDecision.Fire
        assertEquals("KSJC 68°, was KNUQ 68°", fire.body)
    }

    @Test
    fun `station id casing alone is not a handover`() {
        val decision = DominantTempWatch.evaluate(
            state = primed,
            dominant = contribution(stationId = "knuq", rawTemp = 68f),
            useCelsius = false,
        )
        assertEquals(DominantTempWatchDecision.Hold("unchanged"), decision)
    }

    @Test
    fun `a blank station id holds rather than firing an anonymous notification`() {
        val decision = DominantTempWatch.evaluate(primed, contribution(stationId = "  ", rawTemp = 80f), useCelsius = false)
        assertEquals(DominantTempWatchDecision.Hold("no_station_id"), decision)
    }

    @Test
    fun `a non-finite reading holds`() {
        val decision = DominantTempWatch.evaluate(primed, contribution(rawTemp = Float.NaN), useCelsius = false)
        assertEquals(DominantTempWatchDecision.Hold("non_finite_temp"), decision)
    }
}
