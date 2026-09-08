package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.TimedCloudCover
import kotlinx.coroutines.Job
import java.time.LocalDateTime

/**
 * Encapsulates parameters for [CloudCoverGraphRenderer.renderGraph].
 */
data class CloudRenderRequest(
    val context: Context,
    val hours: List<CloudCoverGraphRenderer.CloudHourData>,
    val widthPx: Int,
    val heightPx: Int,
    val currentTime: LocalDateTime,
    val bitmapScale: Float = 1f,
    val smoothIterations: Int = 1,
    val actualSeries: List<TimedCloudCover> = emptyList(),
    val hourLabelSpacingDp: Float = HourlyGraphDefaults.DEFAULT_HOUR_LABEL_SPACING_DP,
    val missingHours: Int = 0,
    val totalHours: Int = 0,
    val numColumns: Int = 0,
    val missingDescription: String? = null,
    val missingReason: String? = null,
    val job: Job? = null,
    val onLabelPlaced: ((CloudCoverGraphRenderer.LabelPlacementDebug) -> Unit)? = null,
    val onDayLabelPlaced: ((CloudCoverGraphRenderer.DayLabelPlacementDebug) -> Unit)? = null,
    val onWatermarkPlaced: ((CloudCoverGraphRenderer.WatermarkPlacementDebug) -> Unit)? = null,
    val showErrorWatermark: Boolean = false,
    val errorSourceLabel: String? = null,
    val errorCode: String? = null,
    val errorFailureTimeMs: Long? = null,
    val dominantStationLabel: DominantStationLabel.LabelText? = null,
    val onDominantStationPlaced: ((DominantStationLabel.Placement?) -> Unit)? = null,
    val onLayerGlyphsPlaced: ((List<GraphRect>) -> Unit)? = null,
)
