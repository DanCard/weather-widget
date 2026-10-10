package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The composition Android `DailyActualsStore` and desktop `loadDailyActuals` used to do separately.
 * The first test is the desktop regression: its copy had no late-start gate, so a site whose rows
 * began at noon drew the noon reading as today's observed low (Samsung 2026-08-22 class).
 */
@Category(ShortDuration::class)
class DailyActualsAssemblerTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.parse("2026-06-03")
    private val yesterday = today.minusDays(1)
    private val lat = 37.4168
    private val lon = -122.0890

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun obs(date: LocalDate, hour: Int, temp: Float, api: String, station: String = "KNUQ") = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
        temperature = temp,
        condition = "observed",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 2f,
        api = api,
        stationType = StationType.OFFICIAL,
    )

    /** Coldest at the first hour, warming 1° per hour. */
    private fun hours(range: IntRange, api: String, date: LocalDate = today) =
        range.map { obs(date, it, 50f + (it - range.first), api) }

    private fun row(
        date: LocalDate,
        hi: Float?,
        lo: Float?,
        source: String = "NWS",
        at: Pair<Double, Double> = lat to lon,
    ) = DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = source,
        locationLat = at.first,
        locationLon = at.second,
        computedHighTemp = hi,
        computedLowTemp = lo,
        condition = "x",
        updatedAt = 1L,
    )

    private fun assemble(
        sources: List<WeatherSource> = listOf(WeatherSource.NWS),
        pastRows: List<DailyHistory> = emptyList(),
        donors: List<DailyHistory> = emptyList(),
        observations: List<ObservationReading> = emptyList(),
    ) = DailyActualsAssembler.assemble(
        activeSources = sources,
        pastRows = pastRows,
        yesterdayDonors = donors,
        observations = observations,
        hourlyForecasts = emptyList(),
        latitude = lat,
        longitude = lon,
        today = today,
        zone = zone,
        nowMs = today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli(),
        personalStationWeight = 1.0,
    )

    @Test
    fun `today's rows starting at noon null the low but keep the high`() {
        val result = assemble(observations = hours(12..20, "NWS"))
        val todayRow = result.bySource["NWS"]?.get(today)
        assertNotNull("the live blend must still produce today", todayRow)
        assertNull("noon onward is 'lowest since we started watching', not the day's low", todayRow!!.computedLowTemp)
        assertEquals(58f, todayRow.computedHighTemp!!, 0.5f)
        assertEquals(listOf("NWS"), result.suppressedTodayLows.map { it.source })
        assertEquals(50f, result.suppressedTodayLows.single().low, 0.5f)
    }

    @Test
    fun `today watched from midnight keeps its low`() {
        val result = assemble(observations = hours(0..20, "NWS"))
        assertEquals(50f, result.bySource["NWS"]!![today]!!.computedLowTemp!!, 0.5f)
        assertTrue(result.suppressedTodayLows.isEmpty())
    }

    @Test
    fun `a borrowing source is judged by its provider's rows`() {
        // Silurian has no observations of its own; METAR rows from midnight cover its day.
        val result = assemble(sources = listOf(WeatherSource.SILURIAN), observations = hours(0..20, WeatherSource.METAR.id))
        val todayRow = result.bySource["SILURIAN"]?.get(today)
        assertNotNull("Silurian borrows METAR actuals", todayRow)
        assertEquals(50f, todayRow!!.computedLowTemp!!, 0.5f)
        assertTrue(result.suppressedTodayLows.isEmpty())
    }

    @Test
    fun `a stored past row wins over the live blend for the same day`() {
        val result = assemble(
            pastRows = listOf(row(yesterday, hi = 80f, lo = 60f)),
            observations = hours(0..23, "NWS", date = yesterday) + hours(0..20, "NWS"),
        )
        assertEquals(80f, result.bySource["NWS"]!![yesterday]!!.computedHighTemp!!, 0f)
    }

    @Test
    fun `a persisted today row never stands in for the live blend`() {
        val result = assemble(pastRows = listOf(row(today, hi = 99f, lo = 10f)))
        assertNull(result.bySource["NWS"]?.get(today))
    }

    @Test
    fun `same-day fragments inside the box resolve to the nearest`() {
        val far = row(yesterday, hi = 70f, lo = 50f, at = lat + 0.009 to lon)
        val near = row(yesterday, hi = 71f, lo = 51f, at = lat + 0.001 to lon)
        // Order must not matter: desktop's old associateBy kept whichever row came last.
        for (rows in listOf(listOf(far, near), listOf(near, far))) {
            assertEquals(71f, assemble(pastRows = rows).bySource["NWS"]!![yesterday]!!.computedHighTemp!!, 0f)
        }
    }

    @Test
    fun `yesterday from a previous site fills a day this site never measured`() {
        val result = assemble(donors = listOf(row(yesterday, hi = 66f, lo = 44f, at = 38.5 to -121.5)))
        val filled = result.bySource["NWS"]!![yesterday]!!
        assertTrue(filled.isActualsBorrowed)
        assertEquals(66f, filled.computedHighTemp!!, 0f)
    }

    @Test
    fun `inactive sources are dropped from past rows`() {
        val result = assemble(pastRows = listOf(row(yesterday, hi = 70f, lo = 50f, source = "OPEN_METEO")))
        assertNull(result.bySource["OPEN_METEO"])
    }
}
