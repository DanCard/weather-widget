package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsDailyExtremesFetchTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 8, 8)
    private val nowMs = today.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun epoch(date: LocalDate) = date.toEpochDay() * 86_400_000L

    private fun reading(date: LocalDate, hour: Int, temp: Float, stationId: String, distanceKm: Float, stationType: String = "OFFICIAL") =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli(),
            temperature = temp,
            condition = "Clear",
            locationLat = 37.4168,
            locationLon = -122.0890,
            distanceKm = distanceKm,
            stationType = stationType,
            api = "NWS",
        )

    private fun coveredDay(date: LocalDate, stationId: String, distanceKm: Float, low: Float, high: Float) =
        listOf(
            reading(date, 3, low, stationId, distanceKm),
            reading(date, 15, high, stationId, distanceKm),
            reading(date, 20, (low + high) / 2f, stationId, distanceKm),
        )

    /**
     * The endpoint caps a response at 500 features and returns the NEWEST ones, so one request
     * spanning several days silently drops the earliest (measured: KSJC over 7 days returned 500
     * rows covering only 3). Requests must therefore be per calendar day.
     */
    @Test
    fun `issues one request per day, never a single spanning request`() = runBlocking {
        val d5 = LocalDate.of(2026, 8, 5)
        val d6 = LocalDate.of(2026, 8, 6)
        val d7 = LocalDate.of(2026, 8, 7)
        val windows = mutableListOf<Pair<String, String>>()

        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d7), epoch(d5), epoch(d6)), // deliberately unsorted
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, start, end ->
            windows += start to end
            // Serve only the day actually asked for, the way a per-day request behaves. A stub
            // that ignored the window would hide exactly the truncation bug this test exists for.
            listOf(d5, d6, d7)
                .firstOrNull { it.atStartOfDay(zone).toInstant().toString() == start }
                ?.let { coveredDay(it, "KNUQ", 3.83f, 60.8f, 70f + it.dayOfMonth) }
                .orEmpty()
        }

        assertEquals("one request per calendar day", 3, windows.size)
        assertEquals("windows must be distinct", 3, windows.distinct().size)
        windows.forEach { (start, end) ->
            assertTrue("each window spans a single day", start < end)
        }
        assertEquals(75f, result[epoch(d5)]?.station?.high)
        assertEquals(76f, result[epoch(d6)]?.station?.high)
        assertEquals(77f, result[epoch(d7)]?.station?.high)
    }

    @Test
    fun `a day whose request fails does not abort the remaining days`() = runBlocking {
        val d5 = LocalDate.of(2026, 8, 5)
        val d6 = LocalDate.of(2026, 8, 6)

        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d5), epoch(d6)),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, start, _ ->
            if (start == d5.atStartOfDay(zone).toInstant().toString()) {
                emptyList() // simulate a failed/empty response for the first day
            } else {
                coveredDay(d6, "KNUQ", 3.83f, 61.0f, 74.0f)
            }
        }

        assertNull(result[epoch(d5)])
        assertEquals(74.0f, result[epoch(d6)]?.station?.high)
    }

    @Test
    fun `nearest official station wins across the fetched pool`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("KNUQ", "KSJC"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { stationId, _, _ ->
            when (stationId) {
                "KNUQ" -> coveredDay(d, "KNUQ", 3.83f, 60.8f, 75.2f)
                else -> coveredDay(d, "KSJC", 15.94f, 59.0f, 80.6f)
            }
        }

        assertEquals("KNUQ", result[epoch(d)]?.station?.stationId)
        assertEquals(75.2f, result[epoch(d)]?.station?.high)
    }

    @Test
    fun `dates beyond the endpoint lookback are dropped without a request`() = runBlocking {
        var calls = 0
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(today.minusDays(30))),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ -> calls++; emptyList() }

        assertTrue(result.isEmpty())
        assertEquals("unrecoverable dates must not cost a request", 0, calls)
    }

    @Test
    fun `today and future dates are never requested`() = runBlocking {
        var calls = 0
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(today), epoch(today.plusDays(1))),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ -> calls++; emptyList() }

        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `a failed fetch yields nothing rather than a wrong value`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ -> emptyList() }

        assertTrue(result.isEmpty())
    }

    /**
     * Blend and station extreme are independent. The per-station coverage guard governs only the
     * api actual, so a day where the *pool* spans both windows but no single station does still
     * produces a blend — here KNUQ reports only pre-dawn and KSJC only the afternoon.
     */
    @Test
    fun `pool coverage split across stations yields a blend but no station extreme`() = runBlocking {
        val good = LocalDate.of(2026, 8, 5)
        val split = LocalDate.of(2026, 8, 6)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(good), epoch(split)),
            stationIdsNearestFirst = listOf("KNUQ", "KSJC"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { stationId, start, _ ->
            when {
                start == good.atStartOfDay(zone).toInstant().toString() && stationId == "KNUQ" ->
                    coveredDay(good, "KNUQ", 3.83f, 60.8f, 75.2f)
                start == split.atStartOfDay(zone).toInstant().toString() && stationId == "KNUQ" ->
                    listOf(reading(split, 3, 61.0f, "KNUQ", 3.83f))    // pre-dawn only
                start == split.atStartOfDay(zone).toInstant().toString() ->
                    listOf(reading(split, 15, 74.0f, "KSJC", 15.94f))  // afternoon only
                else -> emptyList()
            }
        }

        assertEquals(75.2f, result[epoch(good)]?.station?.high)
        assertNotNull("the pool spans the day, so it blends", result[epoch(split)])
        assertNull("but no single station qualifies", result[epoch(split)]?.station)
    }

    @Test
    fun `blend includes personal stations even when no official station exists`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("AW020"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ ->
            coveredDay(d, "AW020", 2.22f, 61.0f, 77.0f).map { it.copy(stationType = "PERSONAL") }
        }

        val day = result[epoch(d)]
        assertNotNull("a PWS-only pool must still blend", day)
        assertNull("but a PWS can never supply the api actual", day?.station)
        assertTrue("blend must reflect the PWS reading", day!!.blendHigh > 70f)
    }

    /** The Personal Weather Stations preference must actually reach the blend math. */
    @Test
    fun `personal station weight changes the blend`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        // A hot PWS very close in, and a cooler official station further out.
        suspend fun blendAt(weight: Double): Float? = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("AW020", "KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = weight,
            zone = zone,
            nowMs = nowMs,
        ) { stationId, _, _ ->
            when (stationId) {
                "AW020" -> coveredDay(d, "AW020", 2.22f, 61.0f, 90.0f).map { it.copy(stationType = "PERSONAL") }
                else -> coveredDay(d, "KNUQ", 3.83f, 60.8f, 70.0f)
            }
        }[epoch(d)]?.blendHigh

        val full = blendAt(1.0)
        val ignored = blendAt(0.0)

        assertNotNull(full)
        assertNotNull(ignored)
        assertNotEquals("the discount preference must reach the blend", full, ignored)
        assertTrue("ignoring the hot PWS must pull the blend toward the official station", ignored!! < full!!)
    }

    @Test
    fun `a day whose every station returns nothing is absent, so no stored value is overwritten`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("KNUQ", "KSJC"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ -> emptyList() }

        assertTrue(result.isEmpty())
    }

    /**
     * Regression: the endpoint's retention is a rolling window from now, so the oldest day in range
     * arrives truncated at the current wall-clock hour. Measured on the emulator 2026-08-08 09:00,
     * every station's 2026-08-01 series started at hour 09 and the blend wrote a low 5.18 F too
     * warm over a correct stored value. A partial day must produce nothing at all.
     */
    @Test
    fun `a day truncated by endpoint retention writes nothing`() = runBlocking {
        val d = LocalDate.of(2026, 8, 1)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ ->
            // 09:00 onward only — the overnight minimum has aged out of the endpoint.
            (9..23).map { reading(d, it, 70f + it, "KNUQ", 3.83f) }
        }

        assertTrue("a partial day must not overwrite a good stored blend", result.isEmpty())
    }

    @Test
    fun `a day missing only the afternoon also writes nothing`() = runBlocking {
        val d = LocalDate.of(2026, 8, 5)
        val result = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = listOf(epoch(d)),
            stationIdsNearestFirst = listOf("KNUQ"),
            userLat = 37.4168,
            userLon = -122.0890,
            personalStationWeight = 1.0,
            zone = zone,
            nowMs = nowMs,
        ) { _, _, _ -> (0..9).map { reading(d, it, 60f + it, "KNUQ", 3.83f) } }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty input costs no request`() = runBlocking {
        var calls = 0
        val result = NwsDailyExtremesFetch.resolveForDates(emptyList(), listOf("KNUQ"), 37.4168, -122.0890, 1.0, zone, nowMs) { _, _, _ -> calls++; emptyList() }
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }
}
