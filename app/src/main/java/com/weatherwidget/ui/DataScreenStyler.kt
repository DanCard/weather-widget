package com.weatherwidget.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.weatherwidget.shared.graph.DataScreenStyle

/**
 * Applies the `:shared` [DataScreenStyle] palette — the one desktop's Observations and History
 * windows read — to Android views. Layout XML cannot read Kotlin constants, so the screens that use
 * the shared palette set it here at runtime instead of copying the hex values into drawables.
 */
object DataScreenStyler {
    fun color(hex: String): Int = Color.parseColor(hex)

    fun background(view: View) = view.setBackgroundColor(color(DataScreenStyle.BACKGROUND))

    /** Card fill, 1 dp border, 12 dp corners. */
    fun card(view: View) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(color(DataScreenStyle.CARD_FILL))
            cornerRadius = DataScreenStyle.CARD_RADIUS_DP * density
            setStroke((DataScreenStyle.CARD_BORDER_DP * density).toInt().coerceAtLeast(1), color(DataScreenStyle.CARD_BORDER))
        }
    }

    /** The navy pill with accent text (API source button, and the screen's other text buttons). */
    fun pillButton(view: TextView) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(color(DataScreenStyle.SOURCE_BUTTON_FILL))
            cornerRadius = DataScreenStyle.CARD_RADIUS_DP * density
        }
        view.setTextColor(color(DataScreenStyle.ACCENT))
    }
}
