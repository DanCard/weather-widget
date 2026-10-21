package com.weatherwidget.shared.radar

import com.weatherwidget.shared.util.NwsCoverage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Where the radar view's frames come from (`plans/261021-radar-button-on-precip-graph.md`).
 *
 * Inside NWS coverage: the NWS GeoServer WMS mosaics radar.weather.gov animates (`bref_qcd`, base
 * reflectivity quality-controlled), ~2-min frames, already lat/lon (EPSG:4326) so they line up with
 * the base map as they come. Elsewhere: RainViewer's free past radar, 10-min frames, Web Mercator
 * tiles. Neither needs a key. Both are **observed** radar: no forecast frames (RainViewer dropped
 * nowcast tiles 2026-01-01).
 */
sealed class RadarFeed(val attribution: String) {
    data class Nws(val region: NwsCoverage.Region) : RadarFeed("NOAA / NWS") {
        /** GeoServer workspace = layer prefix: `conus/conus_bref_qcd` (all four measured 2026-10-21). */
        val workspace: String get() = when (region) {
            NwsCoverage.Region.CONUS -> "conus"
            NwsCoverage.Region.ALASKA -> "alaska"
            NwsCoverage.Region.HAWAII -> "hawaii"
            NwsCoverage.Region.PUERTO_RICO -> "carib"
        }
        val layer: String get() = "${workspace}_bref_qcd"
        val owsUrl: String get() = "$NWS_HOST/geoserver/$workspace/$layer/ows"
    }

    data object RainViewer : RadarFeed("RainViewer")

    /** `RADAR_FETCH feed=…` */
    val logName: String get() = when (this) {
        is Nws -> "nws_${workspace}"
        RainViewer -> "rainviewer"
    }

    companion object {
        const val NWS_HOST = "https://opengeo.ncep.noaa.gov"
        const val RAINVIEWER_LIST_URL = "https://api.rainviewer.com/public/weather-maps.json"

        fun forLocation(lat: Double, lon: Double): RadarFeed =
            NwsCoverage.region(lat, lon)?.let { Nws(it) } ?: RainViewer
    }
}

/**
 * The map's lat/lon box, plotted equirectangular like the rain map's `BaseMap`: x linear in lon,
 * y linear in lat. Longitude span is widened by 1/cos(lat) so a square view is roughly square on
 * the ground (± [HALF_SPAN_LAT_DEG] ≈ ±165 km, close to radar.weather.gov's zoom 7).
 */
data class RadarBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    /** x in 0..width for [lon] (west edge = 0). */
    fun x(lon: Float, width: Float): Float = ((lon - minLon) / (maxLon - minLon) * width).toFloat()

    /** y in 0..height for [lat] (north edge = 0). */
    fun y(lat: Float, height: Float): Float = ((maxLat - lat) / (maxLat - minLat) * height).toFloat()

    /** Centre of pixel column [col] of [width]. */
    fun lonAt(col: Int, width: Int): Double = minLon + (col + 0.5) / width * (maxLon - minLon)

    /** Centre of pixel row [row] of [height] (row 0 = north). */
    fun latAt(row: Int, height: Int): Double = maxLat - (row + 0.5) / height * (maxLat - minLat)

    companion object {
        const val HALF_SPAN_LAT_DEG = 1.5

        fun around(lat: Double, lon: Double, halfLatDeg: Double = HALF_SPAN_LAT_DEG): RadarBox {
            val halfLon = halfLatDeg / max(cos(Math.toRadians(lat)), 0.2)
            return RadarBox(
                minLat = max(lat - halfLatDeg, -85.0),
                maxLat = min(lat + halfLatDeg, 85.0),
                minLon = max(lon - halfLon, -180.0),
                maxLon = min(lon + halfLon, 180.0),
            )
        }
    }
}
