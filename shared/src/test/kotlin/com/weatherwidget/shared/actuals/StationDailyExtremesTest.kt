package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.data.model.ObservationReading
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Fixtures mirror the real 2026-08-05 station set at the reference location (Pixel backup
 * `20260808_000545`), because the bug that motivated this class was that the *nearest* station is a
 * PWS and the *nearest official* one is 1.6 km further out:
 *
 *   AW020 AE6EO MOUNTAIN VIEW   2.22 km  PERSONAL  61.0 .. 77.0
 *   KNUQ  Moffett Field         3.83 km  OFFICIAL  60.8 .. 75.2
 *   KPAO  Palo Alto Airport     6.05 km  OFFICIAL  62.6 .. 73.4  (13 readings, sparse)
 *   KSJC  San Jose Intl        15.94 km  OFFICIAL  59.0 .. 80.6
 */
@Category(ShortDuration::class)
class StationDailyExtremesTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val date: LocalDate = LocalDate.of(2026, 8, 5)
    private val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun readingAt(
        hour: Int,
        temp: Float,
        stationId: String,
        distanceKm: Float,
        stationType: String = "OFFICIAL",
        api: String = "NWS",
        qcFailed: Boolean = false,
        dayOffset: Long = 0,
    ) = ObservationReading(
        stationId = stationId,
        stationName = "$stationId name",
        timestamp = date.plusDays(dayOffset).atStartOfDay(zone).plusHours(hour.toLong())
            .toInstant().toEpochMilli(),
        temperature = temp,
        condition = "Clear",
        locationLat = 37.4168,
        locationLon = -122.0890,
        distanceKm = distanceKm,
        stationType = stationType,
        api = api,
        qcFailed = qcFailed,
    )

    /** A station covering both guard windows, spanning [low]..[high]. */
    private fun coveredStation(
        stationId: String,
        distanceKm: Float,
        low: Float,
        high: Float,
        stationType: String = "OFFICIAL",
        api: String = "NWS",
    ) = listOf(
        readingAt(3, low, stationId, distanceKm, stationType, api),
        readingAt(9, (low + high) / 2f, stationId, distanceKm, stationType, api),
        readingAt(15, high, stationId, distanceKm, stationType, api),
        readingAt(21, (low + high) / 2f, stationId, distanceKm, stationType, api),
    )

    private fun resolve(observations: List<ObservationReading>, sourceId: String = "NWS") =
        StationDailyExtremes.resolve(observations, sourceId, dayStartMs, dayEndMs, zone)

    @Test
    fun `picks nearest official station and ignores the nearer personal station`() {
        val observations =
            coveredStation("AW020", 2.22f, 61.0f, 77.0f, stationType = "PERSONAL") +
                coveredStation("KNUQ", 3.83f, 60.8f, 75.2f) +
                coveredStation("KSJC", 15.94f, 59.0f, 80.6f)

        val result = resolve(observations)

        assertEquals("KNUQ", result?.stationId)
        assertEquals(75.2f, result?.high)
        assertEquals(60.8f, result?.low)
    }

    @Test
    fun `sparse station failing the afternoon guard falls through to the next nearest official`() {
        // KNUQ reports only 04:00-11:00 — its 71.0 late-morning reading is NOT the day's high.
        val sparseKnuq = listOf(
            readingAt(4, 60.8f, "KNUQ", 3.83f),
            readingAt(8, 65.0f, "KNUQ", 3.83f),
            readingAt(11, 71.0f, "KNUQ", 3.83f),
        )
        val observations = sparseKnuq + coveredStation("KSJC", 15.94f, 59.0f, 80.6f)

        val result = resolve(observations)

        assertEquals("KSJC", result?.stationId)
        assertEquals(80.6f, result?.high)
    }

    @Test
    fun `station missing pre-dawn coverage also falls through`() {
        val afternoonOnlyKnuq = listOf(
            readingAt(12, 70.0f, "KNUQ", 3.83f),
            readingAt(15, 75.2f, "KNUQ", 3.83f),
            readingAt(19, 68.0f, "KNUQ", 3.83f),
        )
        val observations = afternoonOnlyKnuq + coveredStation("KSJC", 15.94f, 59.0f, 80.6f)

        assertEquals("KSJC", resolve(observations)?.stationId)
    }

    @Test
    fun `returns null when every official station fails the guard`() {
        val observations =
            listOf(readingAt(11, 71.0f, "KNUQ", 3.83f)) +
                listOf(readingAt(13, 73.4f, "KPAO", 6.05f))

        assertNull(resolve(observations))
    }

    @Test
    fun `returns null when only personal stations are present`() {
        assertNull(resolve(coveredStation("AW020", 2.22f, 61.0f, 77.0f, stationType = "PERSONAL")))
    }

    @Test
    fun `synthetic backfill and blend rows never qualify`() {
        val observations =
            coveredStation("NWS_MAIN", 0.0f, 55.0f, 95.0f) +
                coveredStation("NWS_BLEND", 0.0f, 56.0f, 94.0f) +
                coveredStation("KNUQ", 3.83f, 60.8f, 75.2f)

        val result = resolve(observations)

        assertEquals("KNUQ", result?.stationId)
        assertEquals(75.2f, result?.high)
    }

    @Test
    fun `qc-failed readings are excluded from the extremes`() {
        val observations = coveredStation("KNUQ", 3.83f, 60.8f, 75.2f) +
            listOf(readingAt(14, 120.0f, "KNUQ", 3.83f, qcFailed = true))

        assertEquals(75.2f, resolve(observations)?.high)
    }

    @Test
    fun `readings from other days and other sources are excluded`() {
        val observations = coveredStation("KNUQ", 3.83f, 60.8f, 75.2f) +
            listOf(
                readingAt(15, 99.0f, "KNUQ", 3.83f, dayOffset = 1),
                readingAt(15, 98.0f, "KNUQ", 3.83f, dayOffset = -1),
                readingAt(15, 97.0f, "KOTHER", 1.0f, api = "OPEN_METEO"),
            )

        val result = resolve(observations)

        assertEquals("KNUQ", result?.stationId)
        assertEquals(75.2f, result?.high)
        assertEquals(4, result?.readingCount)
    }

    @Test
    fun `empty input yields null`() {
        assertNull(resolve(emptyList()))
    }

    @Test
    fun `distance uses the station minimum despite gps jitter across rows`() {
        val jittery = listOf(
            readingAt(3, 60.8f, "KNUQ", 3.90f),
            readingAt(15, 75.2f, "KNUQ", 3.83f),
        )
        val nearerButSparse = listOf(readingAt(15, 73.4f, "KPAO", 3.85f))

        val result = resolve(jittery + nearerButSparse)

        assertEquals("KNUQ", result?.stationId)
        assertEquals(3.83f, result?.distanceKm)
    }
}
