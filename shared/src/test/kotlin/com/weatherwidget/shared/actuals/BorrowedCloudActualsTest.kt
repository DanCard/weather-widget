package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.CloudHourBucket
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Open-Meteo had no cloud actual curve at all: `MetarCloudBlender.fromSiteRows` gated on
 * `source.supportsCloudActuals`, which is false for every forecast-only source, and its fallback
 * branch read the `<SOURCE>_MAIN` backfill row whose cloud [HistoricalActualsBackfill] deliberately
 * nulls. Both were right while such a source had no measured feed; borrowing makes the question
 * "which api supplies cloud?" instead — the same reframing that gave it a temperature curve.
 */
@Category(ShortDuration::class)
class BorrowedCloudActualsTest {

    private val hour = CloudHourBucket.startMsOf(1_787_572_800_000L)
    private val lat = 37.42
    private val lon = -122.08

    @After
    fun tearDown() {
        ActualsProviderResolver.resetPreferenceSource()
    }

    private fun metarRow(station: String, lowPercent: Int, atMs: Long = hour) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = atMs,
        temperature = 60f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 2f,
        stationType = "OFFICIAL",
        api = WeatherSource.METAR.id,
        isMetar = true,
        cloudCoverLow = lowPercent,
    )

    private fun nwsRow(station: String, lowPercent: Int) = metarRow(station, lowPercent)
        .copy(api = WeatherSource.NWS.id)

    private suspend fun cloudFor(
        source: WeatherSource,
        rows: List<ObservationReading>,
    ): MetarCloudBlender.Result =
        MetarCloudBlender.fromSiteRows(
            startMs = hour,
            endMs = hour + 3_600_000L,
            sourceId = source.id,
            readSiteRows = { _, _ -> rows },
        )

    @Test
    fun `open-meteo draws cloud from its borrowed METAR feed`() = runBlocking {
        val result = cloudFor(WeatherSource.OPEN_METEO, listOf(metarRow("KNUQ", 75)))

        assertEquals(
            "the borrowed METAR reading must reach the cloud curve",
            75,
            result.hours[hour],
        )
        assertTrue("a real station blend, not a synthetic series", result.isMetarBlend)
    }

    @Test
    fun `the borrowed feed follows the per-source preference`() = runBlocking {
        ActualsProviderResolver.installPreferenceSource { WeatherSource.NWS }
        val rows = listOf(metarRow("KNUQ", 75), nwsRow("KNUQ", 20))

        assertEquals(
            "with NWS chosen, the NWS row supplies cloud and METAR must not",
            20,
            cloudFor(WeatherSource.OPEN_METEO, rows).hours[hour],
        )
    }

    /**
     * Provenance must not collapse. METAR rows carry their own `api`, and blending them under NWS's
     * is the same confusion the observations primary key was widened to prevent.
     */
    @Test
    fun `a source that ships its own cloud does not absorb the borrowed feed`() = runBlocking {
        val rows = listOf(nwsRow("KNUQ", 20), metarRow("KSJC", 90))

        assertEquals(
            "NWS must blend only NWS-provenance rows",
            20,
            cloudFor(WeatherSource.NWS, rows).hours[hour],
        )
    }

    /**
     * Silurian is excluded as a *provider* (its include_past payload is forecast output), so a
     * borrower can never resolve to it. It still gets a curve as a borrower, from METAR.
     */
    @Test
    fun `silurian borrows rather than serving its own forecast output`() = runBlocking {
        assertFalse(WeatherSource.SILURIAN.supportsCloudActuals)
        assertEquals(75, cloudFor(WeatherSource.SILURIAN, listOf(metarRow("KNUQ", 75))).hours[hour])
    }

    /**
     * Borrowing is a READ-side change and must not start manufacturing rows.
     *
     * Open-Meteo produces no [HistoricalActualsBackfill] row at all — `build` returns empty because
     * `supportsTemperatureActuals` is false — so there is no synthetic row for a borrowed cloud
     * value to be written into. Asserted explicitly because the first version of this test checked
     * `cloudCover == null` over that empty list and passed vacuously.
     */
    @Test
    fun `borrowing does not manufacture a synthetic row for a forecast-only source`() {
        val built = HistoricalActualsBackfill.build(
            hourly = listOf(
                com.weatherwidget.data.model.HourlyForecast(
                    dateTime = hour,
                    temperature = 60f,
                    condition = "Clear",
                    cloudCover = 88,
                ),
            ),
            latitude = lat,
            longitude = lon,
            sourceId = WeatherSource.OPEN_METEO.id,
            nowMs = hour + 1_000L,
        )
        assertTrue(
            "a forecast-only source must have no synthetic observation row of its own; got $built",
            built.isEmpty(),
        )
    }
}
