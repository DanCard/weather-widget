package com.weatherwidget.shared.graph

/**
 * When the hourly graph's mid-window temperature label (the value on the drawn line at the plot's
 * horizontal centre) may appear.
 *
 * The label fills the empty middle of a window whose only value labels sit at the far left and far
 * right edges. Any other placed temperature label (a low, high, local or interior label) already
 * describes the middle, and the mid label then duplicates or crowds it: on 2026-11-07 it printed
 * 47.7° next to the ACTUAL_LOW 47.7° label at the same hour.
 */
object MidWindowTempLabelGate {

    /**
     * @param placedIndices hour indices of the value labels the label engine placed.
     * @param lastIndex index of the last hour in the window (the far right edge).
     * @return true only when every placed label sits on the first or last hour.
     */
    fun shouldShow(placedIndices: List<Int>, lastIndex: Int): Boolean =
        placedIndices.all { it == 0 || it == lastIndex }

    /** Short reason for diagnostics: null when shown, else the first interior label's index. */
    fun hiddenReason(placedIndices: List<Int>, lastIndex: Int): String? =
        placedIndices.firstOrNull { it != 0 && it != lastIndex }?.let { "interior_label idx=$it" }
}
