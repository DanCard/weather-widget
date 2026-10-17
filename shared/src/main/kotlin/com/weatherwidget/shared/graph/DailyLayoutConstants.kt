package com.weatherwidget.shared.graph

/**
 * Shared sizing constants for daily view layouts across Android widget and desktop.
 */
object DailyLayoutConstants {
    /**
     * Per-column width assumption (dp). Deliberately tighter than a standard launcher grid cell
     * so the daily view packs more days at every size — e.g. Samsung Fold 4 cover display (~337dp)
     * shows 7 columns (6 days with Today overlay), Pixel 7 Pro (~373dp) shows 7 columns, and the
     * Fold full-width (~574dp) shows 11. Lowering this densifies daily views uniformly.
     */
    const val CELL_WIDTH_DP = 55

    /** Per-row height assumption (dp). */
    const val CELL_HEIGHT_DP = 90

    /** Width threshold below which a layout is treated as single-icon width (no graph). */
    const val ICON_WIDTH_THRESHOLD_DP = 130
}
