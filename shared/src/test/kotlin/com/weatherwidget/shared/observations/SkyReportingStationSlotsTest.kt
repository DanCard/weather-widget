package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Which stations earn a slot in the Synoptic radius selection. */
@Category(ShortDuration::class)
class SkyReportingStationSlotsTest {

    private fun obs(layers: List<NwsApi.CloudLayer> = emptyList()) = NwsApi.Observation(
        timestamp = "2026-08-27T20:00:00Z",
        temperatureCelsius = 20f,
        textDescription = "Clear",
        cloudLayers = layers,
    )

    private fun station(id: String, km: Double, reportsSky: Boolean, silentReports: Int = 2) =
        SynopticApi.Companion.RadiusStation(
            info = NwsApi.StationInfo(id = id, name = id, lat = 37.4, lon = -122.1),
            distanceKm = km,
            elevationMeters = null,
            observations = buildList {
                repeat(silentReports) { add(obs()) }
                if (reportsSky) add(obs(listOf(NwsApi.CloudLayer(amount = "BKN", baseMeters = 3048.0))))
            },
        )

    /** The eight PWS that held the slots: 601 rows between them, never a sky group. */
    private fun pws(id: String, km: Double) = station(id, km, reportsSky = false, silentReports = 60)

    // --- the predicate ------------------------------------------------------

    @Test
    fun `a station whose every observation is silent does not report sky`() {
        assertFalse(SkyReportingStationSlots.reportsSky(pws("F4751", 2.5)))
    }

    @Test
    fun `one observation carrying layers is enough`() {
        assertTrue(SkyReportingStationSlots.reportsSky(station("KPAO", 6.1, reportsSky = true)))
    }

    // --- selection ----------------------------------------------------------

    /** The measured scene: nearby PWS crowding out the airports that can answer the question. */
    @Test
    fun `sky-reporting stations are admitted past the proximity cap`() {
        val stations = (1..10).map { pws("PWS$it", it * 0.5) } +
            station("KNUQ", 3.8, reportsSky = true) +
            station("KPAO", 6.1, reportsSky = true) +
            station("KSJC", 15.9, reportsSky = true)

        val nearestTen = stations.sortedBy { it.distanceKm }.take(10).map { it.info.id }
        val selected = SkyReportingStationSlots.select(stations, limit = 10).map { it.info.id }

        assertTrue("every station the cap kept before is still kept", nearestTen.all { it in selected })
        assertTrue("the sky reporters are added", listOf("KNUQ", "KPAO", "KSJC").all { it in selected })
        // KNUQ is already inside the nearest ten, so only KPAO and KSJC are added: 10 + 2.
        assertEquals("grown by exactly the shortfall", 12, selected.size)
    }

    @Test
    fun `a selection already meeting the quota does not grow`() {
        val stations = listOf(
            station("KNUQ", 1.0, reportsSky = true),
            station("KPAO", 2.0, reportsSky = true),
            station("KSJC", 3.0, reportsSky = true),
        ) + (1..7).map { pws("PWS$it", 4.0 + it) } + station("KSFO", 30.0, reportsSky = true)

        val selected = SkyReportingStationSlots.select(stations, limit = 10)

        assertEquals("exactly the nearest ten, no growth", 10, selected.size)
        assertFalse("the distant sky station is not needed", selected.any { it.info.id == "KSFO" })
    }

    @Test
    fun `additions are the nearest qualifying stations`() {
        // Four sky stations available and a shortfall of three, so the farthest must be left out.
        val stations = (1..10).map { pws("PWS$it", it * 0.5) } +
            station("SKY_A", 9.0, reportsSky = true) +
            station("SKY_B", 11.0, reportsSky = true) +
            station("SKY_C", 13.0, reportsSky = true) +
            station("FAR_SKY", 40.0, reportsSky = true)

        val selected = SkyReportingStationSlots.select(stations, limit = 10).map { it.info.id }

        assertTrue("the three nearest sky stations are taken", listOf("SKY_A", "SKY_B", "SKY_C").all { it in selected })
        assertFalse("only the shortfall is filled, nearest first", "FAR_SKY" in selected)
    }

    @Test
    fun `a station already kept is never added twice`() {
        val stations = (1..9).map { pws("PWS$it", it * 0.5) } + station("KNUQ", 1.0, reportsSky = true)

        val selected = SkyReportingStationSlots.select(stations, limit = 10).map { it.info.id }

        assertEquals(selected.distinct(), selected)
    }

    /** One sky-reporting station in the area is a fact about the area, not an error. */
    @Test
    fun `fewer sky stations than the quota returns what exists`() {
        val stations = (1..10).map { pws("PWS$it", it * 0.5) } + station("KNUQ", 20.0, reportsSky = true)

        val selected = SkyReportingStationSlots.select(stations, limit = 10).map { it.info.id }

        assertEquals(11, selected.size)
        assertTrue("KNUQ" in selected)
    }

    /** Proximity is still the right axis for temperature; nothing it relies on may be displaced. */
    @Test
    fun `every station kept before is still kept`() {
        val stations = (1..10).map { pws("PWS$it", it * 0.5) } + station("KSJC", 15.9, reportsSky = true)
        val nearestTen = stations.sortedBy { it.distanceKm }.take(10).map { it.info.id }

        val selected = SkyReportingStationSlots.select(stations, limit = 10).map { it.info.id }

        assertTrue(nearestTen.all { it in selected })
    }

    @Test
    fun `the result stays ordered nearest first`() {
        val stations = (1..10).map { pws("PWS$it", it * 0.5) } + station("KSJC", 15.9, reportsSky = true)

        val distances = SkyReportingStationSlots.select(stations, limit = 10).map { it.distanceKm }

        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `an empty response selects nothing`() {
        assertTrue(SkyReportingStationSlots.select(emptyList(), limit = 10).isEmpty())
    }
}
