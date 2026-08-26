package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarFetchPolicyTest {

    private fun tier(visible: List<WeatherSource>, displayed: Set<String>) =
        MetarFetchPolicy.tierFor(visible, displayed)

    @Test
    fun `no consumer visible means no fetch at all`() {
        assertEquals(
            MetarFetchPolicy.Tier.NONE,
            tier(listOf(WeatherSource.NWS, WeatherSource.TOMORROW_IO), setOf("NWS")),
        )
    }

    /** The case in the brief: NWS displayed, Silurian visible behind it. */
    @Test
    fun `consumer visible but not displayed is non-primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.NON_PRIMARY,
            tier(listOf(WeatherSource.NWS, WeatherSource.SILURIAN), setOf("NWS")),
        )
    }

    /** The case where a source configured to use METAR is displayed. */
    @Test
    fun `consumer displayed is primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.PRIMARY,
            tier(listOf(WeatherSource.SILURIAN, WeatherSource.NWS), setOf("SILURIAN")),
        )
    }

    /** Widgets can show different sources; one showing a consumer promotes the whole fetch. */
    @Test
    fun `one widget displaying a consumer promotes the fetch to primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.PRIMARY,
            tier(
                listOf(WeatherSource.NWS, WeatherSource.SILURIAN),
                setOf("NWS", "SILURIAN"),
            ),
        )
    }

    @Test
    fun `open meteo with METAR preference counts as a consumer`() {
        val preferMetar: (WeatherSource) -> WeatherSource? = { WeatherSource.METAR }
        assertEquals(
            MetarFetchPolicy.Tier.NON_PRIMARY,
            MetarFetchPolicy.tierFor(
                listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
                setOf("NWS"),
                preferMetar,
            ),
        )
    }

    @Test
    fun `no visible sources at all means no fetch`() {
        assertEquals(MetarFetchPolicy.Tier.NONE, tier(emptyList(), emptySet()))
    }

    @Test
    fun `consumers are the configurable sources that resolve to METAR`() {
        val consumers = MetarFetchPolicy.consumers(WeatherSourceOrdering.ALL_CONFIGURABLE)
        assertEquals(setOf(WeatherSource.SILURIAN), consumers.toSet())
    }

    /**
     * GENERIC_GAP is not a forecast API — it synthesizes climate normals for future dates beyond
     * real forecast coverage, read-time only, never persisted — so it can never need actuals. It is
     * excluded structurally by being absent from ALL_CONFIGURABLE.
     */
    @Test
    fun `generic gap is never a consumer because it is never visible`() {
        assertTrue(WeatherSource.GENERIC_GAP !in WeatherSourceOrdering.ALL_CONFIGURABLE)
        assertEquals(MetarFetchPolicy.Tier.NONE, tier(listOf(WeatherSource.NWS), setOf("NWS")))
    }

    /** METAR must never count itself as a consumer and bootstrap its own fetch. */
    @Test
    fun `metar is not its own consumer`() {
        assertTrue(MetarFetchPolicy.consumers(listOf(WeatherSource.METAR)).isEmpty())
        assertEquals(MetarFetchPolicy.Tier.NONE, tier(listOf(WeatherSource.METAR), setOf("METAR")))
    }
}
