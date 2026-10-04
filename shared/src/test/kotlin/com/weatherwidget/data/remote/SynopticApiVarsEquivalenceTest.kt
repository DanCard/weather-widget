package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * A `vars=`-filtered Synoptic response parses to exactly the observations of an unfiltered one.
 *
 * Both fixtures are trimmed from real responses recorded seconds apart on 2026-10-03
 * (37.39,-122.08, 25 mi, `recent=120`): KNUQ, an airport that reports METAR + cloud layers, and
 * AW020, a personal station that reports temperature only. The unfiltered response carries 73
 * observation keys; the `vars=` one carries the 7 the parser reads, under the same names.
 * See performance/261003-synoptic-fetch-review-fixes.md.
 */
@Category(ShortDuration::class)
class SynopticApiVarsEquivalenceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/synoptic/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun parse(name: String): List<SynopticApi.Companion.RadiusStation> {
        val outcome = SynopticApi.parseRadiusTimeseries(json, fixture(name))
        assertTrue("$name: $outcome", outcome is FetchOutcome.Success)
        return (outcome as FetchOutcome.Success).value.sortedBy { it.info.id }
    }

    @Test
    fun `a recorded vars response parses identically to the recorded full response`() {
        val full = parse("radius-full-recent120.json")
        val vars = parse("radius-vars-recent120.json")
        assertEquals(listOf("AW020", "KNUQ"), full.map { it.info.id })
        assertEquals(full.map { it.info }, vars.map { it.info })
        assertEquals(full.map { it.distanceKm }, vars.map { it.distanceKm })

        val fullObs = full.flatMap { it.observations }
        val varsObs = vars.flatMap { it.observations }
        assertEquals(fullObs.size, varsObs.size)
        assertEquals(fullObs, varsObs)
    }

    @Test
    fun `the recorded vars response still carries sky for the airport`() {
        // Guards the fixture itself: an equivalence over two sky-less responses would prove nothing.
        val knuq = parse("radius-vars-recent120.json").single { it.info.id == "KNUQ" }
        assertTrue(knuq.observations.any { it.isMetar })
        assertTrue(knuq.observations.any { it.cloudLayers.isNotEmpty() })
    }

    @Test
    fun `parsed vars list covers every field the parser reads`() {
        val expected = setOf(
            "air_temp",
            "metar",
            "cloud_layer_1",
            "cloud_layer_2",
            "cloud_layer_3",
            "weather_summary",
            "weather_condition",
        )
        assertEquals(expected, SynopticApi.PARSED_VARS.toSet())
    }
}
