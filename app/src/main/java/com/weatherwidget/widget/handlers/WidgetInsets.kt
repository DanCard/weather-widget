package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.graph.NavArrowGeometry

/**
 * How far the graph sits inside the widget, in dp: `widget_root`'s `android:padding` plus
 * `graph_view`'s side margins in `widget_weather.xml`. The bitmap is sized from these, and the
 * RemoteViews touch zones that track a painted pill are placed with them, so they must match the
 * layout; `WidgetEdgeInsetRoboTest` inflates it and checks.
 *
 * All zero since 2026-11-18: the graph fills the widget edge to edge like the Daily Calendar widget
 * beside it (plans/261118-remove-widget-edge-padding.md). Before, 8 dp of root padding and 4 dp
 * graph margins left the drawing 12 dp short of the widget's sides and 8 dp short of top and bottom.
 */
object WidgetInsets {
    /** `widget_root`'s `android:padding`, every side. */
    const val ROOT_PADDING_DP = 0f

    /** `graph_view`'s `layout_marginStart`/`End` (owned by [NavArrowGeometry] for the arrow overlap). */
    const val GRAPH_SIDE_MARGIN_DP = NavArrowGeometry.GRAPH_INSET_DP

    /** Widget width minus the graph's width. */
    const val GRAPH_WIDTH_INSET_DP = 2 * (ROOT_PADDING_DP + GRAPH_SIDE_MARGIN_DP)

    /** Widget height minus the graph's height. */
    const val GRAPH_HEIGHT_INSET_DP = 2 * ROOT_PADDING_DP
}
