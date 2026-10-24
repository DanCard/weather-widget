package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The one definition of the `NWS_BLEND` row. The first test is the desktop regression: when every
 * station was past the IDW's 3h decay, desktop still wrote a blend row carrying the hourly forecast
 * or the nearest station's stale reading.
 */
@Category(ShortDuration::class)
class NwsBlendTest {
    private val lat = 37.4168
    private val lon = -122.0890
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun reading(
        station: String,
        ageMin: Long,
        temp: Float,
        km: Float,
        condition: String = "Clear",
        qcFailed: Boolean = false,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = now - ageMin * minute,
        temperature = temp,
        condition = condition,
        locationLat = lat,
        locationLon = lon,
        distanceKm = km,
        api = "NWS",
        fetchedAt = now - ageMin * minute + 30_000L,
        qcFailed = qcFailed,
    )

    @Test
    fun `no row when every station is too stale to weight`() {
        val stale = listOf(reading("KNUQ", ageMin = 200, temp = 60f, km = 3f), reading("KSJC", ageMin = 240, temp = 62f, km = 12f))
        assertNull("a stale-only blend must not become an observation", NwsBlend.build(stale, lat, lon, personalStationWeight = 1.0, nowMs = now))
    }

    @Test
    fun `no row without a usable station`() {
        assertNull(NwsBlend.build(emptyList(), lat, lon, personalStationWeight = 1.0, nowMs = now))
        assertNull(NwsBlend.build(listOf(reading("KNUQ", 5, 60f, 3f, qcFailed = true)), lat, lon, personalStationWeight = 1.0, nowMs = now))
    }

    @Test
    fun `fresh stations blend into one row with the nearest condition and newest timestamp`() {
        val blend = NwsBlend.build(
            listOf(
                reading("KNUQ", ageMin = 10, temp = 60f, km = 3f, condition = "Fog"),
                reading("KSJC", ageMin = 5, temp = 70f, km = 12f, condition = "Clear"),
            ),
            lat,
            lon, personalStationWeight = 1.0,
            nowMs = now,
        )
        assertNotNull(blend)
        assertEquals(NwsBlend.STATION_ID, blend!!.stationId)
        assertEquals(NwsBlend.STATION_TYPE, blend.stationType)
        assertEquals("NWS", blend.api)
        assertEquals("Fog", blend.condition)
        assertEquals(now - 5 * minute, blend.timestamp)
        assertTrue("IDW lands between the stations, nearer the closer one", blend.temperature in 60f..65f)
    }

    @Test
    fun `row is filed at the storage key, blended at the centre`() {
        val blend = NwsBlend.build(listOf(reading("KNUQ", 5, 60f, 3f)), lat, lon, personalStationWeight = 1.0, rowLatitude = 37.417, rowLongitude = -122.089, nowMs = now)!!
        assertEquals(37.417, blend.locationLat, 0.0)
        assertEquals(-122.089, blend.locationLon, 0.0)
    }

    @Test
    fun `newer QC failed row does not hide a station's newest usable row`() {
        val clean = reading("KPAO", ageMin = 20, temp = 72f, km = 5f)
        val rejected = reading("KPAO", ageMin = 10, temp = 50f, km = 5f, qcFailed = true)
        assertEquals(listOf(clean), NwsBlend.latestUsableByStation(listOf(rejected, clean)))
    }

    @Test
    fun `an existing blend row is never a blend input`() {
        val prior = reading(NwsBlend.STATION_ID, ageMin = 1, temp = 99f, km = 0f)
        val blend = NwsBlend.build(listOf(prior, reading("KNUQ", 5, 60f, 3f)), lat, lon, personalStationWeight = 1.0, nowMs = now)!!
        assertEquals(60f, blend.temperature, 0f)
    }

    /** The one input rule both platforms share (plans/261009-desktop-stops-storing-nws-blend.md, step 3). */
    @Test
    fun `current blends only NWS rows inside the blend's own window`() {
        val rows = listOf(
            reading("KNUQ", ageMin = 45, temp = 60f, km = 3.8f),
            reading("KPAO", ageMin = 200, temp = 90f, km = 6.0f), // past the 3 h decay
            reading("SYN1", ageMin = 5, temp = 99f, km = 1.0f).copy(api = "SYNOPTIC"),
        )
        val blend = NwsBlend.current(rows, lat, lon, personalStationWeight = 1.0, now)!!
        assertEquals(60f, blend.temperature, 0.01f)
        assertEquals(now - 45 * minute, blend.timestamp)
    }

    @Test
    fun `current merges nearby fetch sites instead of collapsing to the nearest`() {
        val home = reading("KNUQ", ageMin = 70, temp = 60f, km = 3.8f)
        val walk = reading("KNUQ", ageMin = 10, temp = 66f, km = 4.1f).copy(locationLat = lat + 0.007)
        val blend = NwsBlend.current(listOf(home, walk), lat, lon, personalStationWeight = 1.0, now)!!
        assertEquals(66f, blend.temperature, 0.01f)
    }

    // The fetch-time reading (plans/261024-nws-fetch-time-current-reading-shared.md). Android took the
    // condition from the nearest station and the time from the newest reading among all latest
    // readings, a QC-failed one included; the temperature already skipped it.
    @Test
    fun `a QC-failed nearest station gives the fetch reading neither its condition nor its time`() {
        val reading = NwsBlend.fetchReading(
            listOf(
                reading("AW020", ageMin = 2, temp = 99f, km = 1f, condition = "Hail", qcFailed = true),
                reading("KNUQ", ageMin = 20, temp = 60f, km = 4f, condition = "Clear"),
            ),
            lat, lon, personalStationWeight = 1.0, nowMs = now,
        )
        assertNotNull(reading)
        assertEquals(60f, reading!!.temperature, 0.01f)
        assertEquals("Clear", reading.condition)
        assertEquals(now - 20 * minute, reading.observedAt)
    }

    @Test
    fun `no fetch reading when nothing blends`() {
        // Desktop reported the nearest station's 3 h+ old temperature (or the forecast) as current.
        val stale = listOf(reading("KNUQ", ageMin = 200, temp = 60f, km = 3f))
        assertNull(NwsBlend.fetchReading(stale, lat, lon, personalStationWeight = 1.0, nowMs = now))
        assertTrue(NwsBlend.idwLine(null, stale, storedCount = 4, totalMs = 12).startsWith("blended=none°F from 1 stations: KNUQ(3.0km)"))
    }
}
