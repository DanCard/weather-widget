package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * A past day's stored high/low is kept when the recompute's provider readings miss either end of the
 * day. Fold-4 2026-10-21: Synoptic's first 24 h fetch held only 22:43–23:55 of the day before, and the
 * recompute wrote 61.9/60.4 over a stored 68.3/51.0
 * (plans/261022-past-day-blend-needs-whole-day-coverage.md).
 */
@Category(ShortDuration::class)
class StoredBlendGuardTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.parse("2026-10-22")
    private val yesterday = today.minusDays(1)

    @Before
    fun setUp() = ActualsProviderResolver.installPreferenceSource { source ->
        if (source == WeatherSource.SILURIAN) WeatherSource.SYNOPTIC else null
    }

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun reading(date: LocalDate, hour: Int, minute: Int = 0, api: String = WeatherSource.SYNOPTIC.id) =
        ObservationReading(
            stationId = "G4110",
            stationName = "G4110",
            timestamp = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            temperature = 60f,
            condition = "observed",
            locationLat = 37.4168,
            locationLon = -122.0890,
            distanceKm = 2f,
            api = api,
            stationType = StationType.OFFICIAL,
        )

    private val lateEvening = (0 until 15).map { i ->
        val minutes = 22 * 60 + 43 + i * 5
        reading(yesterday, minutes / 60, minutes % 60)
    }

    private fun keep(
        readings: List<ObservationReading>,
        date: LocalDate = yesterday,
        storedHigh: Float? = 68.3f,
        storedLow: Float? = 51.0f,
        storedActualsSource: String? = null,
    ) = StoredBlendGuard.keep(
        date = date,
        today = today,
        source = WeatherSource.SILURIAN.id,
        storedActualsSource = storedActualsSource,
        storedHigh = storedHigh,
        storedLow = storedLow,
        readings = readings,
        zone = zone,
    )

    @Test
    fun `a past day seen only late in the evening keeps the stored temps`() {
        assertEquals(StoredBlendGuard.Keep.UNCOVERED, keep(lateEvening))
    }

    @Test
    fun `a past day seen at dawn and in the afternoon is rewritten`() {
        assertEquals(StoredBlendGuard.Keep.NONE, keep(lateEvening + reading(yesterday, 4) + reading(yesterday, 14)))
    }

    @Test
    fun `coverage is judged on the provider's readings, not another feed's`() {
        val nwsWholeDay = (0..23).map { reading(yesterday, it, api = WeatherSource.NWS.id) }
        assertEquals(StoredBlendGuard.Keep.UNCOVERED, keep(lateEvening + nwsWholeDay))
    }

    @Test
    fun `nothing stored is always written`() {
        assertEquals(StoredBlendGuard.Keep.NONE, keep(lateEvening, storedHigh = null, storedLow = null))
    }

    @Test
    fun `today is never kept`() {
        assertEquals(StoredBlendGuard.Keep.NONE, keep(listOf(reading(today, 23)), date = today))
    }

    @Test
    fun `a pull-derived past day stays frozen`() {
        assertEquals(
            StoredBlendGuard.Keep.PULL_FROZEN,
            keep(lateEvening + reading(yesterday, 4) + reading(yesterday, 14), storedActualsSource = "nws_station_pull"),
        )
    }
}
