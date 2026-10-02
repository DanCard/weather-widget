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
        assertNull("a stale-only blend must not become an observation", NwsBlend.build(stale, lat, lon, nowMs = now))
    }

    @Test
    fun `no row without a usable station`() {
        assertNull(NwsBlend.build(emptyList(), lat, lon, nowMs = now))
        assertNull(NwsBlend.build(listOf(reading("KNUQ", 5, 60f, 3f, qcFailed = true)), lat, lon, nowMs = now))
    }

    @Test
    fun `fresh stations blend into one row with the nearest condition and newest timestamp`() {
        val blend = NwsBlend.build(
            listOf(
                reading("KNUQ", ageMin = 10, temp = 60f, km = 3f, condition = "Fog"),
                reading("KSJC", ageMin = 5, temp = 70f, km = 12f, condition = "Clear"),
            ),
            lat,
            lon,
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
        val blend = NwsBlend.build(listOf(reading("KNUQ", 5, 60f, 3f)), lat, lon, rowLatitude = 37.417, rowLongitude = -122.089, nowMs = now)!!
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
        val blend = NwsBlend.build(listOf(prior, reading("KNUQ", 5, 60f, 3f)), lat, lon, nowMs = now)!!
        assertEquals(60f, blend.temperature, 0f)
    }
}
