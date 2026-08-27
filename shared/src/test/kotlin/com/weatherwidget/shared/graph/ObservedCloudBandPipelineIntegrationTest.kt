package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The whole observed-band path end to end, across the three classes that own a piece of it:
 * stored rows -> [MetarCloudBlender] (observed) + [PriorDayBandForecast] (frozen) ->
 * [CloudSeriesBuilder] -> the [CloudPoint] list the renderers draw.
 *
 * Each class is unit-tested on its own; what this pins is that observed bands reach the render
 * model independently of whether the forecast side has a frozen snapshot.
 */
@Category(ShortDuration::class)
class ObservedCloudBandPipelineIntegrationTest {

    private val hour = 3_600_000L
    private val now = 1_787_572_800_000L
    private val lat = 37.42
    private val lon = -122.08

    private fun liveHour(offsetHours: Long, mid: Int?, high: Int? = null) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = 30,
        cloudCoverLow = 30,
        cloudCoverMid = mid,
        cloudCoverHigh = high,
        source = WeatherSource.OPEN_METEO.id,
        fetchedAt = now,
    )

    private fun observedRow(atMs: Long, mid: Int?, high: Int? = null, low: Int? = 30) = ObservationReading(
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
        cloudVerticalKind = CloudVerticalKind.PROVIDER_BANDS,
    )

    private fun snapshot(hourMs: Long, leadHours: Long, mid: Int?, high: Int? = null) =
        PriorDayBandForecast.BandSnapshot(
            hourMs = hourMs,
            bucketMs = hourMs - leadHours * hour,
            bands = CloudBands(mid = mid, high = high),
        )

    private fun runPipeline(
        liveHours: List<HourlyForecast>,
        observed: List<ObservationReading>,
        snapshots: List<PriorDayBandForecast.BandSnapshot>,
    ): List<CloudPoint> = runBlocking {
        val actuals = MetarCloudBlender.fromSiteRows(
            startMs = now - 12 * hour,
            endMs = now + hour,
            sourceId = WeatherSource.OPEN_METEO.id,
            readSiteRows = { _, _ -> observed },
        )
        CloudSeriesBuilder.build(
            liveHours = liveHours,
            priorForecast = emptyMap(),
            retroActual = actuals.hours,
            nowMs = now,
            priorBands = PriorDayBandForecast.select(snapshots),
            retroBands = actuals.bands,
        )
    }

    @Test
    fun `a missed band forecast and its actual survive the whole path`() {
        val target = now - 4 * hour
        val points = runPipeline(
            liveHours = listOf(liveHour(-4, mid = 20)),
            observed = listOf(observedRow(target, mid = 20)),
            snapshots = listOf(snapshot(target, leadHours = 25, mid = 85)),
        )

        val point = points.single()
        assertEquals("the day-ago prediction reaches the forecast trail", 85, point.forecastBands.mid)
        assertEquals("the retro-corrected value reaches the actual trail", 20, point.actualBands.mid)
        assertTrue(point.isFrozenBands)

    }

    @Test
    fun `an accurate band actual remains available to the pink trail`() {
        val target = now - 4 * hour
        val points = runPipeline(
            liveHours = listOf(liveHour(-4, mid = 62)),
            observed = listOf(observedRow(target, mid = 62)),
            snapshots = listOf(snapshot(target, leadHours = 24, mid = 60)),
        )

        val point = points.single()
        assertEquals(60, point.forecastBands.mid)
        assertEquals(62, point.actualBands.mid)
        assertEquals(62, point.actualBands.mid)
    }

    /**
     * A missing snapshot changes only the provenance of the gray forecast trail. The observed band
     * remains independently useful and must still reach the pink actual trail.
     */
    @Test
    fun `an hour with observations but no stored snapshot retains its actual band`() {
        val target = now - 4 * hour
        val points = runPipeline(
            liveHours = listOf(liveHour(-4, mid = 90)),
            observed = listOf(observedRow(target, mid = 90)),
            snapshots = emptyList(),
        )

        val point = points.single()
        assertEquals("falls back to the live row", 90, point.forecastBands.mid)
        assertFalse(point.isFrozenBands)
        assertEquals(90, point.actualBands.mid)
    }

    /**
     * A future hour has no observation and no settled prediction. Both trails must come from the
     * live row and nothing may claim otherwise, or the graph draws an accuracy comparison for
     * weather that has not happened.
     */
    @Test
    fun `a future hour carries only the live forecast`() {
        val points = runPipeline(
            liveHours = listOf(liveHour(2, mid = 55, high = 100)),
            observed = emptyList(),
            snapshots = emptyList(),
        )

        val point = points.single()
        assertEquals(55, point.forecastBands.mid)
        assertEquals(100, point.forecastBands.high)
        assertNull(point.actualBands.mid)
        assertFalse(point.isFrozenBands)
    }

    /**
     * The kind gate has to survive the wiring: a cumulative-layer row stored against Open-Meteo
     * must not become a band percentage just because the source is one that forecasts bands.
     */
    @Test
    fun `a cumulative-layer observation never reaches the band trail`() {
        val target = now - 4 * hour
        val points = runPipeline(
            liveHours = listOf(liveHour(-4, mid = 20)),
            observed = listOf(
                observedRow(target, mid = 88)
                    .copy(cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS),
            ),
            snapshots = listOf(snapshot(target, leadHours = 25, mid = 10)),
        )

        assertNull(points.single().actualBands.mid)
    }

    @Test
    fun `high and mid are resolved independently across the path`() {
        val target = now - 6 * hour
        val points = runPipeline(
            liveHours = listOf(liveHour(-6, mid = 15, high = 95)),
            observed = listOf(observedRow(target, mid = 15, high = 95)),
            snapshots = listOf(snapshot(target, leadHours = 26, mid = 12, high = 30)),
        )

        val point = points.single()
        assertEquals(15, point.actualBands.mid)
        assertEquals(95, point.actualBands.high)
        assertEquals(30, point.forecastBands.high)
    }

    @Test
    fun `NWS cumulative low and mid reach the graph model with derived total`() = runBlocking {
        val target = now - 4 * hour
        val row = observedRow(target, low = 44, mid = 75)
            .copy(
                api = WeatherSource.NWS.id,
                stationId = "KSJC",
                stationName = "KSJC",
                distanceKm = 4f,
                isMetar = true,
                cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
            )
        val actuals = MetarCloudBlender.fromSiteRows(
            startMs = now - 12 * hour,
            endMs = now + hour,
            sourceId = WeatherSource.NWS.id,
            readSiteRows = { _, _ -> listOf(row) },
        )
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(liveHour(-4, mid = null).copy(source = WeatherSource.NWS.id)),
            priorForecast = emptyMap(),
            retroActual = actuals.hours,
            nowMs = now,
            retroBands = actuals.bands,
        )

        val point = points.single()
        assertEquals(75, point.actualCover)
        assertEquals(44, point.actualBands.low)
        assertEquals(75, point.actualBands.mid)
        assertNull(point.actualBands.high)
    }
}
