package com.weatherwidget.shared.rainmap

import java.io.DataInputStream

/**
 * Coastlines, lake shores and country/state borders for the rain map, from Natural Earth 1:50m
 * (public domain, naturalearthdata.com), simplified and packed by `scripts/pack_natural_earth.py`
 * into `basemap/ne50m_lines.bin` (~140 KB): offline, free, no tile server.
 *
 * Format: "RLNE", version byte, line count (int); per line a kind byte, point count (ushort), then
 * (lat, lon) pairs as int16 centi-degrees.
 */
object BaseMap {
    enum class Kind { COAST, COUNTRY, STATE, LAKE }

    class Line(val kind: Kind, val lats: FloatArray, val lons: FloatArray) {
        val minLat = lats.min()
        val maxLat = lats.max()
        val minLon = lons.min()
        val maxLon = lons.max()
    }

    private const val RESOURCE = "/basemap/ne50m_lines.bin"

    val lines: List<Line> by lazy { load() }

    private fun load(): List<Line> {
        val stream = BaseMap::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        return DataInputStream(stream.buffered()).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "RLNE") { "bad base map" }
            inp.readUnsignedByte() // version
            val count = inp.readInt()
            List(count) {
                val kind = Kind.entries[inp.readUnsignedByte()]
                val n = inp.readUnsignedShort()
                val lats = FloatArray(n)
                val lons = FloatArray(n)
                for (k in 0 until n) {
                    lats[k] = inp.readShort() / 100f
                    lons[k] = inp.readShort() / 100f
                }
                Line(kind, lats, lons)
            }
        }
    }

    /** Lines touching the box (whole lines; the canvas clips). */
    fun linesIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Line> =
        lines.filter { it.maxLat >= minLat && it.minLat <= maxLat && it.maxLon >= minLon && it.minLon <= maxLon }

    /** Lines touching [grid]'s box, padded by one cell so edges are not cut short. */
    fun linesFor(grid: RainGrid): List<Line> {
        val padLat = (grid.lats.last() - grid.lats.first()) / (grid.rows - 1)
        val padLon = (grid.lons.last() - grid.lons.first()) / (grid.cols - 1)
        return linesIn(grid.lats.first() - padLat, grid.lats.last() + padLat, grid.lons.first() - padLon, grid.lons.last() + padLon)
    }

    /** x in 0..width for [lon] across [grid]'s box (west edge = 0). */
    fun x(grid: RainGrid, lon: Float, width: Float): Float =
        ((lon - grid.lons.first()) / (grid.lons.last() - grid.lons.first()) * width).toFloat()

    /** y in 0..height for [lat] (north edge = 0). */
    fun y(grid: RainGrid, lat: Float, height: Float): Float =
        ((grid.lats.last() - lat) / (grid.lats.last() - grid.lats.first()) * height).toFloat()
}
