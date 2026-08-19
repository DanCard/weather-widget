package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The today-column overlay is handed an `observedAt` that a DIFFERENT code path derived (on Android,
 * `WidgetRenderer` via the current-temp resolution window) and keeps the dominant station only when
 * its own blend lands on the same reading. These tests pin the hazard that made the station rows
 * vanish on 2026-08-19: the emitted series is a function of the whole observation input set, so two
 * callers resolving "now" over different windows can disagree about which reading is latest while
 * both are individually correct.
 *
 * See plans/260819-today-overlay-station-drop-and-dead-opportunistic-loop.md.
 */
@Category(ShortDuration::class)
class TodayColumnOverlayObservedAtSkewTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val day = LocalDateTime.of(2026, 8, 19, 0, 0)
    private val lat = 37.42
    private val lon = -122.08

    private fun ms(hour: Int, minute: Int): Long =
        day.withHour(hour).withMinute(minute).atZone(zone).toInstant().toEpochMilli()

    private fun reading(
        station: String,
        atMs: Long,
        temp: Float,
        distanceKm: Float,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = atMs,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        stationType = "OFFICIAL",
        api = "NWS",
        fetchedAt = atMs,
    )

    /**
     * `KAAA` owns the small hours and reports once more at 15:47; `KBBB` owns the late morning and
     * stops at 11:00, far enough back that its interpolation reach (3 h) cannot cover 15:47. So at
     * 15:47 exactly one station resolves, and whether that lone reading is emitted depends on which
     * station the day's row set makes dominant — which is what the window size decides.
     */
    private val smallHoursRows: List<ObservationReading> =
        (0 until 48).map { i -> reading("KAAA", ms(0, 0) + i * 5 * 60_000L, 60f, 4f) }

    private val lateMorningRows: List<ObservationReading> =
        (0 until 13).map { i -> reading("KBBB", ms(8, 0) + i * 15 * 60_000L, 70f, 5f) }

    private val loneTailRow = reading("KAAA", ms(15, 47), 73.4f, 4f)

    /** What a 12-hour current-temp window sees at 16:00: from 04:00 onward. */
    private val narrowRows: List<ObservationReading> =
        (smallHoursRows + lateMorningRows + loneTailRow).filter { it.timestamp >= ms(4, 0) }

    /** What the overlay's 36-hour load sees: everything. */
    private val wideRows: List<ObservationReading> = smallHoursRows + lateMorningRows + loneTailRow

    private val nowMs = ms(16, 0)

    private fun resolveObservedAt(rows: List<ObservationReading>, lookaheadHours: Long): Long? =
        ActualsAggregator.resolveCurrentObservationDetails(
            observations = rows,
            hourlyForecasts = emptyList(),
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            nowMs = nowMs,
            lookaheadHours = lookaheadHours,
            zoneId = zone,
            personalStationWeight = 1.0,
        )?.observedAt

    private fun overlay(
        rows: List<ObservationReading>,
        observedAt: Long,
        lookaheadHours: Long,
    ) = TodayColumnOverlayContentResolver.resolveAt(
        observations = rows,
        hourlyForecasts = emptyList(),
        displaySourceId = "NWS",
        userLat = lat,
        userLon = lon,
        nowMs = nowMs,
        observedAt = observedAt,
        currentObservedTemp = 73.4f,
        personalStationWeight = 1.0,
        useCelsius = false,
        forecastDelta = -5f,
        lookaheadHours = lookaheadHours,
        zoneId = zone,
    )

    /**
     * The hazard itself. Both answers are individually defensible; they simply are not the same
     * number, which is all it takes for an exact-equality gate to drop the station.
     */
    @Test
    fun `observedAt depends on the observation window, not just on now`() {
        val narrow = resolveObservedAt(narrowRows, lookaheadHours = 3L)
        val wide = resolveObservedAt(wideRows, lookaheadHours = 3L)

        assertNotNull("narrow window resolved nothing", narrow)
        assertNotNull("wide window resolved nothing", wide)
        assertEquals("wide window should reach the 15:47 lone reading", ms(15, 47), wide)
        assertTrue(
            "narrow window should NOT reach 15:47 (lone non-dominant station is skipped), got $narrow",
            narrow!! < ms(15, 47),
        )
    }

    /** The fix: caller and overlay resolve over the same rows, so the station survives. */
    @Test
    fun `station row survives when the caller's window matches the overlay's`() {
        val observedAt = resolveObservedAt(narrowRows, lookaheadHours = 3L)!!

        val content = overlay(narrowRows, observedAt, lookaheadHours = 3L)

        assertNotNull(content)
        assertNull("expected no drop reason", content!!.dominantNullReason)
        assertNotNull("station temperature row should render", content.dominantTempText)
        assertNotNull(content.dominantContribution)
    }

    /**
     * The regression: the caller derived `observedAt` over the narrow window, the overlay re-derives
     * over the wide one, and the station rows disappear while the delta row keeps rendering — which
     * is exactly what "the dominant station stopped reporting" looked like on screen.
     */
    @Test
    fun `station row is dropped when the overlay re-derives over a different window`() {
        val callerObservedAt = resolveObservedAt(narrowRows, lookaheadHours = 3L)!!

        val content = overlay(wideRows, callerObservedAt, lookaheadHours = 3L)

        assertNotNull(content)
        assertNull("station temperature row should be gone", content!!.dominantTempText)
        assertTrue(
            "expected observed_at_skew, got ${content.dominantNullReason}",
            content.dominantNullReason.orEmpty().startsWith("observed_at_skew"),
        )
        assertNotNull("the delta row still renders, hiding the failure", content.deltaValueText)
    }
}
