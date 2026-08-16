package com.weatherwidget.data.local

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure unit coverage for [LocationMatch.selectNearestSiteWith] — the distance-ranked site collapse
 * that additionally refuses to settle on a site with nothing for the caller's source.
 *
 * Regression guard for the Current Observations screen going blank for an entire activity lifetime
 * (2026-08-15, Samsung Fold): a ~0.8 km GPS excursion minted the fragment `37.411/-122.095`, which
 * only ever received the synthetic `<SOURCE>_MAIN` backfill rows. It won on distance against the
 * user's real site 0.006° away — five NWS stations, 300+ rows — and the NWS filter downstream then
 * removed every row it had returned.
 */
@Category(ShortDuration::class)
class LocationMatchSelectNearestSiteWithTest {

    private data class Row(val id: String, val locationLat: Double, val locationLon: Double)

    // The user's real site, and the excursion fragment, exactly as stored on the device that day.
    private val siteLat = 37.417
    private val siteLon = -122.089
    private val excursionLat = 37.411
    private val excursionLon = -122.095

    /** The screen's rule: `<SOURCE>_MAIN` rows are synthetic and never count as an NWS station. */
    private fun isNwsStation(row: Row) = !row.id.endsWith("_MAIN")

    private fun select(rows: List<Row>, lat: Double, lon: Double, isUsable: (Row) -> Boolean = ::isNwsStation) =
        LocationMatch.selectNearestSiteWith(rows, lat, lon, { it.locationLat }, { it.locationLon }, isUsable)

    private val incidentRows = listOf(
        Row("AW020", siteLat, siteLon),
        Row("KNUQ", siteLat, siteLon),
        Row("KPAO", siteLat, siteLon),
        Row("LOAC1", siteLat, siteLon),
        Row("KSJC", siteLat, siteLon),
        Row("OPEN_METEO_MAIN", excursionLat, excursionLon),
        Row("SILURIAN_MAIN", excursionLat, excursionLon),
        Row("TOMORROW_IO_MAIN", excursionLat, excursionLon),
    )

    @Test
    fun `skips the nearer site when it holds nothing usable`() {
        // Queried AT the excursion coordinate: the old helper returns the three _MAIN rows, which the
        // caller's source filter then discards, leaving the screen empty.
        assertEquals(
            listOf("OPEN_METEO_MAIN", "SILURIAN_MAIN", "TOMORROW_IO_MAIN"),
            LocationMatch.selectNearestSite(
                incidentRows,
                excursionLat,
                excursionLon,
                { it.locationLat },
                { it.locationLon },
            ).map { it.id },
        )

        assertEquals(
            listOf("AW020", "KNUQ", "KPAO", "LOAC1", "KSJC"),
            select(incidentRows, excursionLat, excursionLon).map { it.id },
        )
    }

    @Test
    fun `the skipped site really is the nearest one`() {
        // Proves the test above exercises the real ordering rather than a coordinate that would have
        // lost on distance anyway.
        val toExcursion = kotlin.math.abs(excursionLat - excursionLat) + kotlin.math.abs(excursionLon - excursionLon)
        val toRealSite = kotlin.math.abs(siteLat - excursionLat) + kotlin.math.abs(siteLon - excursionLon)
        assertEquals(true, toExcursion < toRealSite)
        // ...and that both sites are inside the ±TOLERANCE_DEG box the DAO actually queries.
        assertEquals(true, toRealSite <= 2 * LocationMatch.TOLERANCE_DEG)
    }

    @Test
    fun `keeps the nearest site when it has usable rows`() {
        // No regression of the distance ordering: queried at the real site, the excursion fragment
        // must not be reached for.
        assertEquals(
            listOf("AW020", "KNUQ", "KPAO", "LOAC1", "KSJC"),
            select(incidentRows, siteLat, siteLon).map { it.id },
        )
    }

    @Test
    fun `matches selectNearestSite when everything is usable`() {
        val lat = 37.4168
        val lon = -122.089
        assertEquals(
            LocationMatch.selectNearestSite(incidentRows, lat, lon, { it.locationLat }, { it.locationLon }),
            select(incidentRows, lat, lon) { true },
        )
    }

    @Test
    fun `falls back to the nearest site when no site qualifies`() {
        // Nothing anywhere is usable, so the caller must not be handed a distant site's rows as if
        // they were an answer — it gets the plain nearest-site result and renders its own empty state.
        val onlySynthetic = incidentRows.filter { !isNwsStation(it) } +
            Row("OPEN_METEO_MAIN", siteLat, siteLon)

        assertEquals(
            listOf("OPEN_METEO_MAIN"),
            select(onlySynthetic, siteLat, siteLon).map { it.id },
        )
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<Row>(), select(emptyList(), siteLat, siteLon))
    }

    @Test
    fun `sub-precision fragments of the winning site are kept together`() {
        // Same guarantee as selectNearestSite: sameSite, not float equality, decides membership.
        val rows = listOf(
            Row("KSJC", 37.4168014526367, -122.088897705078),
            Row("AW020", 37.4168434143066, -122.088996887207),
        )
        assertEquals(2, select(rows, siteLat, siteLon).size)
    }
}
