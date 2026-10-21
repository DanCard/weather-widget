package com.weatherwidget.shared.radar

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Decoded pixels, row-major ARGB, row 0 = top. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixels ${pixels.size} != $width×$height" }
    }
}

/**
 * RainViewer serves Web Mercator ("slippy map") tiles; the map is equirectangular ([RadarBox]).
 * [tilesFor] names the tiles under a box and [resample] copies them into the box's pixels,
 * nearest-neighbour, one latitude per output row — so a storm sits at the same lat/lon as the
 * coastline under it. Near zoom 6 with 512-px tiles a tile pixel is ~1 km, about one output pixel.
 */
object MercatorTiles {
    data class Tile(val x: Int, val y: Int)

    /** Global pixel x of [lon] at [zoom] for [tileSize]-px tiles. */
    fun worldX(lon: Double, zoom: Int, tileSize: Int): Double = (lon + 180.0) / 360.0 * tileSize * (1 shl zoom)

    /** Global pixel y of [lat] (north = 0). */
    fun worldY(lat: Double, zoom: Int, tileSize: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * tileSize * (1 shl zoom)
    }

    fun tilesFor(box: RadarBox, zoom: Int, tileSize: Int): List<Tile> {
        val n = 1 shl zoom
        val x0 = floor(worldX(box.minLon, zoom, tileSize) / tileSize).toInt().coerceIn(0, n - 1)
        val x1 = floor(worldX(box.maxLon, zoom, tileSize) / tileSize).toInt().coerceIn(0, n - 1)
        val y0 = floor(worldY(box.maxLat, zoom, tileSize) / tileSize).toInt().coerceIn(0, n - 1)
        val y1 = floor(worldY(box.minLat, zoom, tileSize) / tileSize).toInt().coerceIn(0, n - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> Tile(x, y) } }
    }

    /** [tiles] (missing ones read as transparent) → [width]×[height] pixels over [box]. */
    fun resample(
        box: RadarBox,
        zoom: Int,
        tileSize: Int,
        tiles: Map<Tile, ArgbImage>,
        width: Int,
        height: Int,
    ): ArgbImage {
        val out = IntArray(width * height)
        val colWx = DoubleArray(width) { worldX(box.lonAt(it, width), zoom, tileSize) }
        for (row in 0 until height) {
            val wy = worldY(box.latAt(row, height), zoom, tileSize)
            val ty = floor(wy / tileSize).toInt()
            val py = (wy - ty * tileSize.toDouble()).toInt().coerceIn(0, tileSize - 1)
            for (col in 0 until width) {
                val wx = colWx[col]
                val tx = floor(wx / tileSize).toInt()
                val img = tiles[Tile(tx, ty)] ?: continue
                val px = (wx - tx * tileSize.toDouble()).toInt().coerceIn(0, tileSize - 1)
                // A tile decoded at another size (a 256 fallback) still maps by fraction.
                val sx = if (img.width == tileSize) px else px * img.width / tileSize
                val sy = if (img.height == tileSize) py else py * img.height / tileSize
                out[row * width + col] = img.pixels[sy * img.width + sx]
            }
        }
        return ArgbImage(width, height, out)
    }
}
