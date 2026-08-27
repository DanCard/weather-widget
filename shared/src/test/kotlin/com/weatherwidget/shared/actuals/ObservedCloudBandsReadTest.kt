package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.CloudBands
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.CloudHourBucket
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The read side of the observed mid/high bands: which stored rows are allowed to become a band
 * percentage on the cloud graph's 0-100 axis.
 *
 * The gate is [CloudVerticalKind], not a source id. `CUMULATIVE_LAYERS` reports a cumulative sky
 * condition and `TOTAL_ENVELOPE` a height range; neither is a band percentage, and neither has a
 * band forecast to be drawn against.
 */
@Category(ShortDuration::class)
class ObservedCloudBandsReadTest {

    private val hour = CloudHourBucket.startMsOf(1_787_572_800_000L)
    private val lat = 37.42
    private val lon = -122.08

    @After
    fun tearDown() {
        ActualsProviderResolver.resetPreferenceSource()
    }

    private fun openMeteoRow(
        mid: Int? = null,
        high: Int? = null,
        low: Int? = 30,
        kind: CloudVerticalKind = CloudVerticalKind.PROVIDER_BANDS,
        atMs: Long = hour,
    ) = ObservationReading(
        stationId = HistoricalActualsBackfill.syntheticStationId(WeatherSource.OPEN_METEO.id),
        stationName = "OPEN_METEO: History Backfill",
        timestamp = atMs,
        temperature = 60f,
        condition = "Cloudy",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 0f,
        stationType = "OFFICIAL",
        api = WeatherSource.OPEN_METEO.id,
        cloudCoverLow = low,
        cloudCoverMid = mid,
        cloudCoverHigh = high,
        cloudVerticalKind = kind,
    )

    private suspend fun read(rows: List<ObservationReading>): MetarCloudBlender.Result =
        MetarCloudBlender.fromSiteRows(
            startMs = hour,
            endMs = hour + 3_600_000L,
            sourceId = WeatherSource.OPEN_METEO.id,
            readSiteRows = { _, _ -> rows },
        )

    @Test
    fun `a PROVIDER_BANDS row supplies both bands`() = runBlocking {
        val result = read(listOf(openMeteoRow(mid = 44, high = 100)))

        assertEquals(CloudBands(mid = 44, high = 100), result.bands[hour])
        // The main curve reads the row's total. This row carries none — Open-Meteo backfill rows
        // that predate the total column, and every station row — so it falls back to the bands'
        // maximum, which for cumulative layers IS the total. See VisibleCloudCover.
        assertEquals(100, result.hours[hour])
    }

    @Test
    fun `a CUMULATIVE_LAYERS row contributes no bands`() = runBlocking {
        val result = read(
            listOf(openMeteoRow(mid = 44, high = 100, kind = CloudVerticalKind.CUMULATIVE_LAYERS)),
        )

        assertTrue(result.bands.isEmpty())
    }

    @Test
    fun `a TOTAL_ENVELOPE row contributes no bands`() = runBlocking {
        val result = read(
            listOf(openMeteoRow(mid = 44, kind = CloudVerticalKind.TOTAL_ENVELOPE)),
        )

        assertTrue(result.bands.isEmpty())
    }

    /** A row reporting no band at all is not an observation of a cloudless middle atmosphere. */
    @Test
    fun `a row with neither band contributes no entry`() = runBlocking {
        val result = read(listOf(openMeteoRow(mid = null, high = null)))

        assertTrue(result.bands.isEmpty())
        assertEquals("its low value is all the row has, so it is the curve", 30, result.hours[hour])
    }

    @Test
    fun `one band present is enough`() = runBlocking {
        val result = read(listOf(openMeteoRow(mid = null, high = 65)))

        assertEquals(CloudBands(mid = null, high = 65), result.bands[hour])
    }

    @Test
    fun `bands are keyed by their native report timestamp`() = runBlocking {
        val quarterPast = hour + 15 * 60_000L
        val result = read(listOf(openMeteoRow(mid = 51, atMs = quarterPast)))

        assertEquals(CloudBands(mid = 51), result.bands[quarterPast])
    }

    /**
     * The METAR blend branch never reaches the band code at all — its rows are cumulative sky
     * conditions blended across stations, which is a different question from a band percentage.
     */
    @Test
    fun `a station-observation source yields no bands`() = runBlocking {
        val result = MetarCloudBlender.fromSiteRows(
            startMs = hour,
            endMs = hour + 3_600_000L,
            sourceId = WeatherSource.NWS.id,
            readSiteRows = { _, _ ->
                listOf(
                    openMeteoRow(mid = 44, high = 70, kind = CloudVerticalKind.CUMULATIVE_LAYERS)
                        .copy(
                            api = WeatherSource.NWS.id,
                            stationId = "KNUQ",
                            stationName = "KNUQ",
                            distanceKm = 2f,
                            isMetar = true,
                        ),
                )
            },
        )

        assertTrue(result.bands.isEmpty())
    }
}
