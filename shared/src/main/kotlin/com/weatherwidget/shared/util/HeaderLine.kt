package com.weatherwidget.shared.util

/**
 * Where the centred header buttons end, one rule for both platforms
 * (`plans/261026-shared-header-line-reserve.md`).
 *
 * The line floats ([HeaderFit.floatingSeamOffset]) but is placed against room reserved for what
 * any visible source could show, not the live text: a source tap changes the temperature, delta,
 * rain % and source label, and a line that followed them slid every button and the date (desktop,
 * NWS → Meteo, ~20 px; user, 2026-10-26). The fit also gets the widest source's label, so the date
 * shows on every source or none rather than coming and going on a tap.
 *
 * Each platform supplies only a [Measure] (text at its drawn size) and the visible sources' rows
 * ([HeaderContent.lineInputs]).
 */
object HeaderLine {
    /**
     * The temperature and delta reserved: typical widths, not the widest. "100.0°", "+00.0" and a
     * full-size "100%" dropped the daily date on desktop's 802×785 window (×2.45), so wider values
     * still push. The rain % is reserved at the widest any visible source shows now: none when dry.
     */
    const val RESERVE_TEMP_TEXT = "00.0°"
    const val RESERVE_DELTA_TEXT = "+0.0"

    /** A header piece, for [Measure]. */
    enum class Piece {
        TEMP,
        DELTA,
        RAIN,
        DATE,

        /** The source label and everything pinned beside it — the whole right cluster. */
        SOURCE,
    }

    /** A piece's width as drawn, leading margin included, in the [HeaderFit.Measured] units. */
    fun interface Measure {
        fun width(piece: Piece, text: String, fontScale: Float): Float
    }

    /** What the visible sources would show now ([HeaderContent.lineInputs]). */
    data class Inputs(
        val sourceLabels: List<String>,
        val rainLabels: List<HeaderContent.PrecipLabel>,
    )

    /**
     * [m] with the line placed against the reserve, and the widest source's cluster for the fit.
     * Unchanged where the platform cannot move the row off the middle (`seamOffset == null`).
     * [iconSlot]: the weather icon with its margin, reserved even when this hour has none.
     * [nominalDate]: the daily date, also passed by hourly views so the line matches across views.
     */
    fun place(
        m: HeaderFit.Measured,
        measure: Measure,
        inputs: Inputs,
        iconSlot: Float,
        nominalDate: String?,
    ): HeaderFit.Measured {
        if (m.seamOffset == null) return m
        val widestRain = inputs.rainLabels.maxOfOrNull { measure.width(Piece.RAIN, it.text, it.fontScale) } ?: 0f
        val left = iconSlot + measure.width(Piece.TEMP, RESERVE_TEMP_TEXT, 1f) +
            measure.width(Piece.DELTA, RESERVE_DELTA_TEXT, 1f) + widestRain
        val right = inputs.sourceLabels.maxOfOrNull { measure.width(Piece.SOURCE, it, 1f) } ?: 0f
        val fitted = m.copy(rightCluster = maxOf(m.rightCluster, right))
        val nominalDateWidth = nominalDate?.let { measure.width(Piece.DATE, it, 1f) } ?: m.date
        return fitted.copy(
            seamOffset = HeaderFit.floatingSeamOffset(
                fitted,
                nominalDateWidth = nominalDateWidth,
                reservedLeft = left,
                reservedRight = right,
            ),
        )
    }
}
