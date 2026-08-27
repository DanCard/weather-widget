package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.sqrt

/** One tiny layer glyph, already positioned in plot pixels. */
data class LayerGlyph(val x: Float, val y: Float, val glyph: Char)

/**
 * One hour's vertex on a layer curve, in plot pixels.
 *
 * [cover] is this layer's percentage at the vertex and [otherCover] is the *sibling* layer's, which
 * the placer needs only to detect the two curves landing on the same value.
 */
data class LayerVertex(
    val x: Float,
    val y: Float,
    val cover: Int?,
    val otherCover: Int? = null,
)

/**
 * Places the mid/high cloud layers as curves whose line is made of repeated tiny glyphs — `m` and
 * `h` — spaced like a dash pattern.
 *
 * The glyph *is* the dash, which is the point: a lane or ribbon encodes layer identity by position
 * and has to be learned, while a curve drawn out of `h`s says what it is everywhere along its
 * length. Both platforms call this so the Android widget and the desktop app cannot disagree about
 * where a glyph lands; only the actual text drawing is platform code.
 *
 * Coverage below [MIN_COVER] draws nothing at all. A layer at 0% is not a line along the bottom of
 * the plot — it is an absent layer, and 73% of stored hours have both mid and high below 20, so the
 * floor is what keeps the graph empty on ordinary days.
 */
object CloudLayerGlyphPlacer {

    /** Below this percentage a layer is absent and draws no glyph at all. */
    const val MIN_COVER = 5

    /**
     * Layers this close in percentage points overprint each other, because equal values map to the
     * same y. Measured in the prototype with mid and high both pinned at 100% for a full afternoon.
     */
    const val COINCIDENT_DELTA = 6

    const val MID_GLYPH = 'm'
    const val HIGH_GLYPH = 'h'

    /**
     * High is offset half a step along the curve so the two layers interleave rather than stack on
     * the same x. Combined with [COINCIDENT_DELTA] nudging, this is what keeps a 100%/100% pair
     * readable.
     */
    const val MID_PHASE = 0f
    const val HIGH_PHASE = 0.5f

    /**
     * Arc-length spacing and glyph size, in dp; each platform converts with its own density.
     *
     * The glyph is deliberately biased SMALL. These are texture on a curve, not labels to be read
     * one at a time — the shape of the trail carries the information, and an `h` large enough to
     * read comfortably in isolation is large enough to clot into a smear where the curve is steep.
     * When in doubt shrink this rather than grow it.
     */
    const val GLYPH_STEP_DP = 13f
    const val GLYPH_SIZE_DP = 6.5f

    /**
     * Walks [vertices] and emits one [glyph] every [stepPx] of arc length.
     *
     * @param phaseFraction fraction of [stepPx] to delay the first glyph by; [HIGH_PHASE] for the
     *   high layer so it never shares an x with mid.
     * @param nudgePx signed vertical offset applied only where this layer's coverage is within
     *   [COINCIDENT_DELTA] of [LayerVertex.otherCover]. Pass opposite signs for the two layers.
     * @param minCover coverage floor; below it no glyph is drawn.
     */
    fun place(
        vertices: List<LayerVertex>,
        glyph: Char,
        stepPx: Float,
        phaseFraction: Float = 0f,
        nudgePx: Float = 0f,
        minCover: Int = MIN_COVER,
    ): List<LayerGlyph> {
        if (vertices.size < 2 || stepPx <= 0f || !stepPx.isFinite()) return emptyList()

        val out = mutableListOf<LayerGlyph>()
        // Distance until the next glyph. Starting at a full step (plus the phase) means the very
        // first vertex never gets one, which keeps both layers off the plot's left edge.
        var untilNext = stepPx * (1f + phaseFraction.coerceIn(0f, 1f))

        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            // Spacing is measured along the CURVE, not horizontally — this is a dash pattern, and
            // a dash pattern follows the line it draws. Where a layer climbs 12% -> 100% inside an
            // hour the glyphs stack up that climb, which is the near-vertical run of dashes such a
            // transition should produce. Sampling by x instead thins those transitions to one or
            // two glyphs and leaves the trail too sparse to read as a line.
            val segment = sqrt(dx * dx + dy * dy)
            if (segment <= 0f || !segment.isFinite()) continue

            // A null endpoint means the layer has no value here. Skip the whole segment rather than
            // interpolating toward zero, which would draw a descent the data never claimed. The
            // step budget still advances so spacing stays even across the gap.
            if (a.cover == null || b.cover == null) {
                untilNext -= segment
                while (untilNext <= 0f) untilNext += stepPx
                continue
            }

            var travelled = 0f
            while (untilNext <= segment - travelled) {
                travelled += untilNext
                val t = travelled / segment
                val cover = a.cover + (b.cover - a.cover) * t
                if (cover >= minCover) {
                    val other = interpolateOther(a, b, t)
                    val coincident = other != null && abs(cover - other) < COINCIDENT_DELTA
                    out.add(
                        LayerGlyph(
                            x = a.x + dx * t,
                            y = a.y + dy * t + if (coincident) nudgePx else 0f,
                            glyph = glyph,
                        ),
                    )
                }
                untilNext = stepPx
            }
            untilNext -= (segment - travelled)
        }
        return out
    }

    private fun interpolateOther(a: LayerVertex, b: LayerVertex, t: Float): Float? {
        val oa = a.otherCover ?: return null
        val ob = b.otherCover ?: return null
        return oa + (ob - oa) * t
    }

    /**
     * True when a layer has anything worth drawing in the window. Renderers use this to skip the
     * glyph pass entirely — the common case, since most days have no mid or high cloud at all.
     */
    fun hasVisibleCover(covers: List<Int?>, minCover: Int = MIN_COVER): Boolean =
        covers.any { it != null && it >= minCover }
}
