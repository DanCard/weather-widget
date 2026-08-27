package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Which clear readings can see the sky they are reporting on. */
@Category(ShortDuration::class)
class CeilometerBlindSpotTest {

    private fun reading(
        station: String,
        distanceKm: Float = 5f,
        raw: String? = null,
        isMetar: Boolean = raw != null,
        lowBase: Int? = null,
        midBase: Int? = null,
        highBase: Int? = null,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = 0L,
        temperature = 60f,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        stationType = "OFFICIAL",
        api = WeatherSource.NWS.id,
        rawMetar = raw,
        isMetar = isMetar,
        cloudBaseLowMeters = lowBase,
        cloudBaseMidMeters = midBase,
        cloudBaseHighMeters = highBase,
    )

    // --- classification -----------------------------------------------------

    @Test
    fun `a five-minute ASOS row is automated by construction`() {
        val row = reading("KNUQ", raw = null, isMetar = false)
        assertTrue(CeilometerBlindSpot.isAutomatedClear(row, cover = 0))
    }

    @Test
    fun `a CLR metar is automated`() {
        val row = reading("KNUQ", raw = "KNUQ 272035Z AUTO 36007KT 10SM CLR 24/16 A2998 RMK AO2")
        assertTrue(CeilometerBlindSpot.isAutomatedClear(row, cover = 0))
    }

    /** A human observer looked at the whole sky. That reading is trusted. */
    @Test
    fun `an SKC metar is a human observation and is never dropped`() {
        val row = reading("KPAO", raw = "KPAO 271947Z 33007KT 10SM SKC 23/16 A2998")
        assertFalse(CeilometerBlindSpot.isAutomatedClear(row, cover = 0))
    }

    @Test
    fun `CAVOK is a human observation too`() {
        val row = reading("KPAO", raw = "KPAO 271947Z 33007KT CAVOK 23/16 A2998")
        assertFalse(CeilometerBlindSpot.isAutomatedClear(row, cover = 0))
    }

    /** Absence of an SKC marker is not proof of automation; keep the measurement. */
    @Test
    fun `an unclassifiable metar is trusted`() {
        val row = reading("KPAO", raw = null, isMetar = true)
        assertFalse(CeilometerBlindSpot.isAutomatedClear(row, cover = 0))
    }

    @Test
    fun `a non-clear automated reading is not a clear reading`() {
        val row = reading("KNUQ", raw = "KNUQ 272035Z AUTO 36007KT 10SM CLR 24/16", isMetar = false)
        assertFalse(CeilometerBlindSpot.isAutomatedClear(row, cover = 75))
    }

    // --- the filter ---------------------------------------------------------

    private fun filter(vararg entries: Pair<ObservationReading, Int?>) =
        CeilometerBlindSpot.filterBlindClears(entries.toList(), { it.first }, { it.second })

    /** The measured 2026-08-27 scene: an 18,000 ft deck the nearest station cannot see. */
    @Test
    fun `a blind clear is dropped against a layer above the ceiling`() {
        val knuq = reading("KNUQ", distanceKm = 3.8f, isMetar = false) to 0
        val kpao = reading("KPAO", distanceKm = 6.1f, raw = "KPAO 272047Z BKN180", midBase = 5486) to 75

        val kept = filter(knuq, kpao)

        assertEquals(listOf(kpao), kept)
    }

    /**
     * The patchy marine layer: an 800 ft deck is well inside every ceilometer's range, so the
     * disagreement is real spatial variation and the 0 is a true measurement.
     */
    @Test
    fun `a blind clear keeps full weight against a low deck`() {
        val knuq = reading("KNUQ", distanceKm = 3.8f, isMetar = false) to 0
        val kpao = reading("KPAO", distanceKm = 6.1f, raw = "KPAO 272047Z BKN008", lowBase = 244) to 75

        assertEquals(listOf(knuq, kpao), filter(knuq, kpao))
    }

    @Test
    fun `nothing is dropped when no station reports a base at all`() {
        val knuq = reading("KNUQ", distanceKm = 3.8f, isMetar = false) to 0
        val other = reading("KSJC", distanceKm = 15.9f, isMetar = false) to 44

        assertEquals(listOf(knuq, other), filter(knuq, other))
    }

    /** A high layer nobody is actually reporting cover for cannot displace a measurement. */
    @Test
    fun `a high base with no cover does not trigger the rule`() {
        val knuq = reading("KNUQ", distanceKm = 3.8f, isMetar = false) to 0
        val quiet = reading("KSJC", distanceKm = 15.9f, isMetar = false, highBase = 9000) to 0

        assertEquals(listOf(knuq, quiet), filter(knuq, quiet))
    }

    @Test
    fun `the ceiling boundary is exclusive`() {
        fun at(base: Int) = filter(
            reading("KNUQ", distanceKm = 3.8f, isMetar = false) to 0,
            reading("KPAO", distanceKm = 6.1f, raw = "KPAO BKN", midBase = base) to 75,
        ).size

        assertEquals("a layer exactly at the ceiling is visible to the ceilometer", 2, at(CeilometerBlindSpot.ASOS_CEILING_M))
        assertEquals("one metre above it is not", 1, at(CeilometerBlindSpot.ASOS_CEILING_M + 1))
    }

    /** A degraded answer beats no answer: the bucket must never be emptied. */
    @Test
    fun `the list is never emptied`() {
        val a = reading("KNUQ", distanceKm = 3.8f, isMetar = false, highBase = 9000) to 0
        val b = reading("KSQL", distanceKm = 9f, isMetar = false, highBase = 9000) to 0

        // Both are blind clears, and neither reports cover above the ceiling, so nothing qualifies.
        assertEquals(listOf(a, b), filter(a, b))
    }

    @Test
    fun `an SKC station is kept even against a high layer`() {
        val kpao = reading("KPAO", distanceKm = 6.1f, raw = "KPAO 271947Z 10SM SKC 23/16") to 0
        val ksjc = reading("KSJC", distanceKm = 15.9f, raw = "KSJC BKN180", midBase = 5486) to 75

        assertEquals(listOf(kpao, ksjc), filter(kpao, ksjc))
    }
}
