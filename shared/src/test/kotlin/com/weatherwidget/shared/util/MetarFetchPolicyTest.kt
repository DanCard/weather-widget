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

    /** The case in the brief: NWS displayed, Open-Meteo visible behind it. */
    @Test
    fun `consumer visible but not displayed is non-primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.NON_PRIMARY,
            tier(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO), setOf("NWS")),
        )
    }

    /** The Paris case: the displayed source is the one with no actuals of its own. */
    @Test
    fun `consumer displayed is primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.PRIMARY,
            tier(listOf(WeatherSource.OPEN_METEO, WeatherSource.NWS), setOf("OPEN_METEO")),
        )
    }

    /** Widgets can show different sources; one showing a consumer promotes the whole fetch. */
    @Test
    fun `one widget displaying a consumer promotes the fetch to primary`() {
        assertEquals(
            MetarFetchPolicy.Tier.PRIMARY,
            tier(
                listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
                setOf("NWS", "SILURIAN"),
            ),
        )
    }

    @Test
    fun `silurian counts as a consumer too`() {
        assertEquals(
            MetarFetchPolicy.Tier.NON_PRIMARY,
            tier(listOf(WeatherSource.NWS, WeatherSource.SILURIAN), setOf("NWS")),
        )
    }

    @Test
    fun `no visible sources at all means no fetch`() {
        assertEquals(MetarFetchPolicy.Tier.NONE, tier(emptyList(), emptySet()))
    }

    /**
     * The consumer set is derived from `supportsTemperatureActuals`, not a hardcoded provider list,
     * so a future keyless forecast-only source is picked up without editing the policy.
     */
    @Test
    fun `consumers are exactly the configurable sources with no actuals product`() {
        val consumers = MetarFetchPolicy.consumers(WeatherSourceOrdering.ALL_CONFIGURABLE)
        assertEquals(setOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN), consumers.toSet())
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
