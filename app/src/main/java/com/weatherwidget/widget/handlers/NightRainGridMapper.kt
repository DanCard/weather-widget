package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ViewMode
import java.time.LocalDateTime
import java.time.LocalDate
import kotlin.math.floor

internal object NightRainGridMapper {
    private const val TAG = "NightRainGridMapper"
    internal const val GRID_ROWS = 6
    internal const val GRID_COLS = 20

    private val gridZoneIds: List<IntArray> = listOf(
        intArrayOf(R.id.graph_night_rain_zone_r0_c0, R.id.graph_night_rain_zone_r0_c1, R.id.graph_night_rain_zone_r0_c2, R.id.graph_night_rain_zone_r0_c3, R.id.graph_night_rain_zone_r0_c4, R.id.graph_night_rain_zone_r0_c5, R.id.graph_night_rain_zone_r0_c6, R.id.graph_night_rain_zone_r0_c7, R.id.graph_night_rain_zone_r0_c8, R.id.graph_night_rain_zone_r0_c9, R.id.graph_night_rain_zone_r0_c10, R.id.graph_night_rain_zone_r0_c11, R.id.graph_night_rain_zone_r0_c12, R.id.graph_night_rain_zone_r0_c13, R.id.graph_night_rain_zone_r0_c14, R.id.graph_night_rain_zone_r0_c15, R.id.graph_night_rain_zone_r0_c16, R.id.graph_night_rain_zone_r0_c17, R.id.graph_night_rain_zone_r0_c18, R.id.graph_night_rain_zone_r0_c19),
        intArrayOf(R.id.graph_night_rain_zone_r1_c0, R.id.graph_night_rain_zone_r1_c1, R.id.graph_night_rain_zone_r1_c2, R.id.graph_night_rain_zone_r1_c3, R.id.graph_night_rain_zone_r1_c4, R.id.graph_night_rain_zone_r1_c5, R.id.graph_night_rain_zone_r1_c6, R.id.graph_night_rain_zone_r1_c7, R.id.graph_night_rain_zone_r1_c8, R.id.graph_night_rain_zone_r1_c9, R.id.graph_night_rain_zone_r1_c10, R.id.graph_night_rain_zone_r1_c11, R.id.graph_night_rain_zone_r1_c12, R.id.graph_night_rain_zone_r1_c13, R.id.graph_night_rain_zone_r1_c14, R.id.graph_night_rain_zone_r1_c15, R.id.graph_night_rain_zone_r1_c16, R.id.graph_night_rain_zone_r1_c17, R.id.graph_night_rain_zone_r1_c18, R.id.graph_night_rain_zone_r1_c19),
        intArrayOf(R.id.graph_night_rain_zone_r2_c0, R.id.graph_night_rain_zone_r2_c1, R.id.graph_night_rain_zone_r2_c2, R.id.graph_night_rain_zone_r2_c3, R.id.graph_night_rain_zone_r2_c4, R.id.graph_night_rain_zone_r2_c5, R.id.graph_night_rain_zone_r2_c6, R.id.graph_night_rain_zone_r2_c7, R.id.graph_night_rain_zone_r2_c8, R.id.graph_night_rain_zone_r2_c9, R.id.graph_night_rain_zone_r2_c10, R.id.graph_night_rain_zone_r2_c11, R.id.graph_night_rain_zone_r2_c12, R.id.graph_night_rain_zone_r2_c13, R.id.graph_night_rain_zone_r2_c14, R.id.graph_night_rain_zone_r2_c15, R.id.graph_night_rain_zone_r2_c16, R.id.graph_night_rain_zone_r2_c17, R.id.graph_night_rain_zone_r2_c18, R.id.graph_night_rain_zone_r2_c19),
        intArrayOf(R.id.graph_night_rain_zone_r3_c0, R.id.graph_night_rain_zone_r3_c1, R.id.graph_night_rain_zone_r3_c2, R.id.graph_night_rain_zone_r3_c3, R.id.graph_night_rain_zone_r3_c4, R.id.graph_night_rain_zone_r3_c5, R.id.graph_night_rain_zone_r3_c6, R.id.graph_night_rain_zone_r3_c7, R.id.graph_night_rain_zone_r3_c8, R.id.graph_night_rain_zone_r3_c9, R.id.graph_night_rain_zone_r3_c10, R.id.graph_night_rain_zone_r3_c11, R.id.graph_night_rain_zone_r3_c12, R.id.graph_night_rain_zone_r3_c13, R.id.graph_night_rain_zone_r3_c14, R.id.graph_night_rain_zone_r3_c15, R.id.graph_night_rain_zone_r3_c16, R.id.graph_night_rain_zone_r3_c17, R.id.graph_night_rain_zone_r3_c18, R.id.graph_night_rain_zone_r3_c19),
        intArrayOf(R.id.graph_night_rain_zone_r4_c0, R.id.graph_night_rain_zone_r4_c1, R.id.graph_night_rain_zone_r4_c2, R.id.graph_night_rain_zone_r4_c3, R.id.graph_night_rain_zone_r4_c4, R.id.graph_night_rain_zone_r4_c5, R.id.graph_night_rain_zone_r4_c6, R.id.graph_night_rain_zone_r4_c7, R.id.graph_night_rain_zone_r4_c8, R.id.graph_night_rain_zone_r4_c9, R.id.graph_night_rain_zone_r4_c10, R.id.graph_night_rain_zone_r4_c11, R.id.graph_night_rain_zone_r4_c12, R.id.graph_night_rain_zone_r4_c13, R.id.graph_night_rain_zone_r4_c14, R.id.graph_night_rain_zone_r4_c15, R.id.graph_night_rain_zone_r4_c16, R.id.graph_night_rain_zone_r4_c17, R.id.graph_night_rain_zone_r4_c18, R.id.graph_night_rain_zone_r4_c19),
        intArrayOf(R.id.graph_night_rain_zone_r5_c0, R.id.graph_night_rain_zone_r5_c1, R.id.graph_night_rain_zone_r5_c2, R.id.graph_night_rain_zone_r5_c3, R.id.graph_night_rain_zone_r5_c4, R.id.graph_night_rain_zone_r5_c5, R.id.graph_night_rain_zone_r5_c6, R.id.graph_night_rain_zone_r5_c7, R.id.graph_night_rain_zone_r5_c8, R.id.graph_night_rain_zone_r5_c9, R.id.graph_night_rain_zone_r5_c10, R.id.graph_night_rain_zone_r5_c11, R.id.graph_night_rain_zone_r5_c12, R.id.graph_night_rain_zone_r5_c13, R.id.graph_night_rain_zone_r5_c14, R.id.graph_night_rain_zone_r5_c15, R.id.graph_night_rain_zone_r5_c16, R.id.graph_night_rain_zone_r5_c17, R.id.graph_night_rain_zone_r5_c18, R.id.graph_night_rain_zone_r5_c19),
    )

    init {
        check(gridZoneIds.size == GRID_ROWS) {
            "nightRainGridZoneIds rows=${gridZoneIds.size} != GRID_ROWS=$GRID_ROWS"
        }
        gridZoneIds.forEach { row ->
            check(row.size == GRID_COLS) {
                "nightRainGridZoneIds cols=${row.size} != GRID_COLS=$GRID_COLS"
            }
        }
    }

    @VisibleForTesting
    internal fun setupNightRainClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
        nightLabelDraws: List<DailyForecastGraphRenderer.DailyRainLabelPlacement>,
        buildClickIntent: (
            appWidgetId: Int, dayIndex: Int, date: LocalDate,
            iconRes: Int?, lat: Double, lon: Double,
            displaySource: WeatherSource, now: LocalDateTime,
            targetModeOverride: ViewMode?, offsetOverride: Int?,
            clickSource: String?,
        ) -> Intent,
    ) {
        gridZoneIds.forEach { rowIds ->
            rowIds.forEach { zoneId ->
                views.setOnClickPendingIntent(zoneId, null)
            }
        }

        Log.d(
            TAG,
            "nightRainZones layout: widget=$appWidgetId grid=${GRID_ROWS}x${GRID_COLS} " +
                "bitmap=${bitmapWidthPx}x${bitmapHeightPx} labels=${nightLabelDraws.size}",
        )

        val daysByDate = days.associateBy { it.date }
        var wired = 0
        nightLabelDraws.forEach { labelDraw ->
            if (labelDraw.kind != DailyForecastGraphRenderer.RainLabelKind.NIGHT) {
                Log.w(
                    TAG,
                    "nightRainZone skip: date=${labelDraw.date} reason=non_night_placement" +
                        " kind=${labelDraw.kind}",
                )
                return@forEach
            }
            val day = daysByDate[labelDraw.date]
            if (day == null) {
                Log.w(TAG, "nightRainZone skip: date=${labelDraw.date} reason=no_day_match")
                return@forEach
            }
            val labelText = day.rainData.nightRainLabelText
            if (labelText == null) {
                Log.d(TAG, "nightRainZone skip: date=${day.date} reason=no_label")
                return@forEach
            }
            if (day.date.isBefore(now.toLocalDate())) {
                Log.d(TAG, "nightRainZone skip: date=${day.date} reason=past_date")
                return@forEach
            }

            val colIndex = day.columnIndex ?: days.indexOf(day)
            val nightOffset = DayClickHelper.calculateNightCenterOffset(now, day.date, lat, lon)
            val intent = buildClickIntent(
                appWidgetId,
                colIndex + 1,
                day.date,
                day.iconRes,
                lat,
                lon,
                displaySource,
                now,
                ViewMode.PRECIPITATION,
                nightOffset,
                "night_rain:col=$colIndex:date=${day.date}",
            )
            val requestCode = WidgetRequestCodes.nightRainClick(appWidgetId, colIndex)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val coveredCells = computeNightRainGridCells(labelDraw, bitmapWidthPx, bitmapHeightPx)
            if (coveredCells.isEmpty()) {
                Log.w(TAG, "nightRainZone skip: date=${day.date} reason=no_cells left=${labelDraw.leftX} right=${labelDraw.rightX} top=${labelDraw.topY} bottom=${labelDraw.bottomY}")
                return@forEach
            }
            coveredCells.forEach { (rowIndex, colCellIndex) ->
                views.setOnClickPendingIntent(gridZoneIds[rowIndex][colCellIndex], pendingIntent)
            }
            wired++
            Log.d(
                TAG,
                "nightRainZone wired: widget=$appWidgetId col=$colIndex date=${day.date} " +
                    "label=\"$labelText\" iconRes=${day.iconRes} offset=${nightOffset}h " +
                    "bounds=(${labelDraw.leftX},${labelDraw.topY})..(${labelDraw.rightX},${labelDraw.bottomY}) " +
                    "zoneIds=${coveredCells.joinToString(",") { (rowIndex, colCellIndex) ->
                        context.resources.getResourceEntryName(gridZoneIds[rowIndex][colCellIndex])
                    }} " +
                    "targetMode=PRECIPITATION requestCode=$requestCode " +
                    "intentAction=${intent.action} hasExtras=${intent.extras != null}",
            )
        }
        Log.d(TAG, "nightRainZones summary: widget=$appWidgetId wired=$wired numDays=${days.size}")
    }

    @VisibleForTesting
    internal fun computeNightRainGridCells(
        labelDraw: DailyForecastGraphRenderer.DailyRainLabelPlacement,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
    ): List<Pair<Int, Int>> {
        if (
            bitmapWidthPx <= 0 ||
            labelDraw.leftX.isNaN() ||
            labelDraw.rightX.isNaN()
        ) {
            return emptyList()
        }

        val safeLeft = labelDraw.leftX.coerceIn(0f, bitmapWidthPx.toFloat() - 1f)
        val safeRight = labelDraw.rightX.coerceIn(safeLeft + 0.01f, bitmapWidthPx.toFloat())

        val startCol = floor((safeLeft / bitmapWidthPx) * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)
        val endCol = floor((((safeRight - 0.01f).coerceAtLeast(safeLeft)) / bitmapWidthPx) * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)

        return buildList {
            for (rowIndex in 0 until GRID_ROWS) {
                for (colIndex in startCol..endCol) {
                    add(rowIndex to colIndex)
                }
            }
        }
    }
}
