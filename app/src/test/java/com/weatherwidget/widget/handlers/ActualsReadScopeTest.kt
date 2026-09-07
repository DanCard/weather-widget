package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * [ActualsReadScope] restricts an SQL read to a set of `observations.api` values. That is only safe
 * while the set is a **superset of every api the consumer would have kept** — a scope narrower than
 * the consumer's own filter silently deletes rows from the blend, which is indistinguishable from a
 * station that stopped reporting.
 *
 * So the property under test is one-directional and deliberately so: everything
 * [ObservationSourceMatcher.matchesActualSource] admits must survive the scope. The converse is
 * harmless — the scope may hand the consumer a row it then drops in Kotlin, which is what the
 * unscoped read did for every api.
 */
@Category(ShortDuration::class)
class ActualsReadScopeTest {

    /** Every api that appears in `observations` on a real device, plus the synthetic ones. */
    private val knownApis = listOf(
        "NWS", "OPEN_METEO", "SYNOPTIC", "METAR", "TOMORROW_IO", "SILURIAN",
        "WEATHER_API", "VISUAL_CROSSING", "OPENWEATHERMAP",
        WeatherSource.GENERIC_GAP.id,
    )

    private val stationIds = listOf("KNUQ", "NWS_BLEND", "SYNOPTIC_ABC", "METAR_KSJC", "TOMORROW_IO_MAIN")

    @Test
    fun `the scope never drops a row the consumer would keep`() {
        for (source in WeatherSource.entries) {
            val scope = ActualsReadScope.apisFor(source)
            for (api in knownApis) {
                for (stationId in stationIds) {
                    val kept = ObservationSourceMatcher.matchesActualSource(
                        stationId = stationId,
                        api = api,
                        source = source,
                    )
                    if (kept) {
                        assertTrue(
                            "scoping ${source.id} to $scope would delete a row the blend keeps " +
                                "(api=$api stationId=$stationId)",
                            scope.contains(api),
                        )
                    }
                }
            }
        }
    }

    /**
     * The reason the set is resolved rather than written down: a source can be configured to take
     * another feed's actuals, and a literal `setOf(source.id)` would read the wrong provider's rows
     * and draw an empty curve. The reporting device runs `actuals_provider_SILURIAN = SYNOPTIC`.
     */
    @Test
    fun `a borrowing source scopes to the provider it borrows from`() {
        val scope = ActualsReadScope.apisFor(WeatherSource.SILURIAN)
        val provider = com.weatherwidget.shared.observations.ActualsProviderResolver
            .providerIdFor(WeatherSource.SILURIAN)
        assertTrue("must read the borrowed provider's rows, not SILURIAN's", scope.contains(provider))
    }

    /** GENERIC_GAP rides along because matchesActualSource admits it ahead of the provider check. */
    @Test
    fun `generic gap is always in scope`() {
        for (source in WeatherSource.entries) {
            assertTrue(source.id, ActualsReadScope.apisFor(source).contains(WeatherSource.GENERIC_GAP.id))
        }
    }
}
