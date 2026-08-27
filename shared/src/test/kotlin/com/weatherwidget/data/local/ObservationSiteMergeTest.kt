package com.weatherwidget.data.local

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The device-site axis of an observation read. */
@Category(ShortDuration::class)
class ObservationSiteMergeTest {

    private data class Row(
        val station: String,
        val ts: Long,
        val lat: Double,
        val lon: Double,
        val api: String = "NWS",
        val fetchedAt: Long = 0L,
        val distanceKm: Float = 0f,
    )

    private val home = 37.417 to -122.089
    private val court = 37.424 to -122.088   // 783 m away, the measured excursion
    private val town = 37.500 to -122.089    // ~9 km away, inside the read box

    private fun merge(rows: List<Row>, at: Pair<Double, Double> = home) =
        ObservationSiteMerge.merge(
            rows = rows, lat = at.first, lon = at.second,
            latOf = Row::lat, lonOf = Row::lon,
            stationOf = Row::station, timestampOf = Row::ts,
            apiOf = Row::api, fetchedAtOf = Row::fetchedAt,
        )

    /** The report: rows filed at the court must reach the graph when the phone is back home. */
    @Test
    fun `a fragment within the merge tolerance is kept`() {
        val atHome = Row("KNUQ", 1000L, home.first, home.second)
        val atCourt = Row("KSJC", 2000L, court.first, court.second)

        assertEquals(listOf(atHome, atCourt), merge(listOf(atHome, atCourt)))
    }

    /** The protection the single-site collapse was providing: a different place stays out. */
    @Test
    fun `a fragment beyond the merge tolerance is discarded`() {
        val atHome = Row("KNUQ", 1000L, home.first, home.second)
        val elsewhere = Row("KSFO", 2000L, town.first, town.second)

        assertEquals(listOf(atHome), merge(listOf(atHome, elsewhere)))
    }

    /**
     * A genuine relocation: nothing near the new centre. Falling back to the nearest site preserves
     * today's behaviour rather than returning an empty graph.
     */
    @Test
    fun `with no nearby fragment the nearest site is still returned`() {
        val far = Row("KSFO", 2000L, town.first, town.second)
        val farther = Row("KSAC", 2000L, 38.6, -121.5)

        assertEquals(listOf(far), merge(listOf(far, farther)))
    }

    // --- deduplication ------------------------------------------------------

    @Test
    fun `a station reported from both sites survives once, from the nearer site`() {
        val fromHome = Row("KSJC", 1000L, home.first, home.second, distanceKm = 15.9f)
        val fromCourt = Row("KSJC", 1000L, court.first, court.second, distanceKm = 16.2f)

        val merged = merge(listOf(fromCourt, fromHome))

        assertEquals(1, merged.size)
        assertEquals("the nearer site's frame carries the accurate distanceKm", fromHome, merged.single())
    }

    /** The gap window: only the far fragment has the row, so it is used rather than dropped. */
    @Test
    fun `a row only the far fragment holds is kept`() {
        val onlyAtCourt = Row("KSJC", 1000L, court.first, court.second, distanceKm = 16.2f)

        assertEquals(listOf(onlyAtCourt), merge(listOf(onlyAtCourt)))
    }

    /**
     * The `api` in the key is load-bearing: MetarCloudBlender collapses the NWS/Synoptic transport
     * duplicate itself and prefers the requested provider. Collapsing it here could keep the
     * Synoptic copy and that preference would never fire.
     */
    @Test
    fun `the transport duplicate survives the site merge`() {
        val viaNws = Row("KPAO", 1000L, home.first, home.second, api = "NWS")
        val viaSynoptic = Row("KPAO", 1000L, home.first, home.second, api = "SYNOPTIC")

        assertEquals(2, merge(listOf(viaNws, viaSynoptic)).size)
    }

    @Test
    fun `distinct timestamps from one station are all kept`() {
        val rows = listOf(
            Row("KSJC", 1000L, court.first, court.second),
            Row("KSJC", 2000L, court.first, court.second),
            Row("KSJC", 3000L, home.first, home.second),
        )

        assertEquals(3, merge(rows).size)
    }

    /** Freshest breaks a tie between equidistant sites — the codebase's canonical rule. */
    @Test
    fun `equidistant copies resolve to the freshest`() {
        val stale = Row("KSJC", 1000L, 37.422, -122.089, fetchedAt = 10L)
        val fresh = Row("KSJC", 1000L, 37.412, -122.089, fetchedAt = 99L)

        assertEquals(fresh, merge(listOf(stale, fresh)).single())
    }

    /**
     * Write coordinates are quantized to 3 dp, so a fragment exactly at the tolerance is an ordinary
     * case. In doubles the two differences below straddle 0.01 in opposite directions, so a raw
     * comparison would admit one and reject the other for no reason but float noise.
     */
    @Test
    fun `the tolerance boundary is exact and symmetric`() {
        val centre = Row("KNUQ", 1000L, home.first, home.second)
        val above = Row("KSJC", 2000L, 37.427, -122.089)
        val below = Row("KPAO", 3000L, 37.407, -122.089)

        assertEquals(
            "both sit exactly 0.010 away and must be treated alike",
            listOf(centre, above, below),
            merge(listOf(centre, above, below)),
        )
    }

    @Test
    fun `one thousandth beyond the tolerance is excluded`() {
        val centre = Row("KNUQ", 1000L, home.first, home.second)
        val justOutside = Row("KSJC", 2000L, 37.428, -122.089)

        assertEquals(listOf(centre), merge(listOf(centre, justOutside)))
    }

    @Test
    fun `the result is ordered by timestamp regardless of input order`() {
        val rows = listOf(
            Row("KSJC", 3000L, home.first, home.second),
            Row("KNUQ", 1000L, court.first, court.second),
            Row("KPAO", 2000L, home.first, home.second),
        )

        assertEquals(listOf(1000L, 2000L, 3000L), merge(rows).map { it.ts })
        assertEquals(merge(rows), merge(rows.reversed()))
    }

    @Test
    fun `an empty read stays empty`() {
        assertEquals(emptyList<Row>(), merge(emptyList()))
    }
}
