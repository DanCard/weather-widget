package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.Text
import com.weatherwidget.shared.actuals.BlendBreakdown
import com.weatherwidget.shared.actuals.BlendTable
import com.weatherwidget.shared.actuals.BlendTableFormatter
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hover the graph's "now" dot to see the stations behind the blended actual temperature.
 *
 * The dot is the one pixel on the graph that claims to be a measurement, and it is the least
 * self-explanatory: it is an IDW blend of several stations, some of them carried forward by the
 * forecast, so it can legitimately sit outside the range of every reading in the list (see the
 * `observed_dot_is_forecast_extrapolated` finding). This overlay answers "which thermometers, and how
 * much did each count" without leaving the graph.
 *
 * **The numbers are never computed here.** Rows come from [BlendTableFormatter], the same pure
 * formatter behind the Stations window's Blend tab, so the two surfaces cannot disagree about what
 * the blend did — the point of both is to be trustworthy, which they cannot be if each derives its
 * own figures.
 */

/**
 * Where the now dot was last painted, in Canvas-local pixels.
 *
 * Deliberately **not** Compose state. The centre is only known inside the draw scope, and writing
 * state there that composition reads risks an invalidation loop. The pointer handler only ever runs
 * after a draw has populated this, so a plain holder is both sufficient and cheaper.
 */
class NowDotTarget {
    var centerX: Float? = null
    var centerY: Float? = null
    var radius: Float = 0f

    /** Canvas width in px, so the popup can flip to the dot's left near the right edge. */
    var canvasWidth: Float = 0f

    fun clear() {
        centerX = null
        centerY = null
        radius = 0f
    }

    fun set(x: Float, y: Float, r: Float, width: Float) {
        centerX = x
        centerY = y
        radius = r
        canvasWidth = width
    }
}

/** Extra slop around the painted dot so a ~5 px target is comfortable to hit with a mouse. */
const val NOW_DOT_HOVER_SLOP_PX: Float = 7f

/**
 * True when [pointer] is within the dot's painted radius plus [NOW_DOT_HOVER_SLOP_PX].
 *
 * Pure so the geometry is testable without a UI harness, the same reason [BlendTableFormatter] is
 * pure. A target with no centre (the dot is off-window, or nothing has been drawn yet) never hits.
 */
fun nowDotHitTest(target: NowDotTarget, pointer: Offset): Boolean {
    val cx = target.centerX ?: return false
    val cy = target.centerY ?: return false
    val dx = pointer.x - cx
    val dy = pointer.y - cy
    return sqrt(dx * dx + dy * dy) <= target.radius + NOW_DOT_HOVER_SLOP_PX
}

/**
 * Tracks whether the pointer is over the now dot.
 *
 * [hovered] is passed as the `MutableState` itself rather than its value: only the popup reads
 * `.value`, which confines recomposition to the popup. If the graph's own body read it, every mouse
 * move would invalidate the graph and re-run the blend it performs while composing.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.nowDotHoverInput(
    target: NowDotTarget,
    hovered: MutableState<Boolean>,
): Modifier = this
    .onPointerEvent(PointerEventType.Move) { event ->
        val position = event.changes.lastOrNull()?.position ?: return@onPointerEvent
        val isOver = nowDotHitTest(target, position)
        if (hovered.value != isOver) hovered.value = isOver
    }
    .onPointerEvent(PointerEventType.Exit) {
        if (hovered.value) hovered.value = false
    }

/**
 * The rows to show, or null when there is nothing worth popping up.
 *
 * Returns the newest breakdown formatted by the shared formatter, capped to [maxRows] contributors
 * (highest weight first — [BlendTableFormatter] already orders them). Null rather than an empty
 * frame when the blend produced no contributions, so the caller draws nothing at all.
 */
fun nowDotStationsTable(
    breakdowns: List<BlendBreakdown>,
    useCelsius: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
    maxRows: Int = MAX_POPUP_ROWS,
): BlendTable? {
    val newest = breakdowns.firstOrNull() ?: return null
    if (newest.contributions.isEmpty()) return null
    val table = BlendTableFormatter.format(newest, useCelsius, zoneId)
    if (table.rows.isEmpty()) return null
    return if (table.rows.size <= maxRows) table else table.copy(rows = table.rows.take(maxRows))
}

/** Beyond this the overlay stops being a glance and starts being the Blend tab. */
const val MAX_POPUP_ROWS: Int = 8

/**
 * The overlay itself. Reads [hovered] internally — see [nowDotHoverInput] for why that matters.
 *
 * Positioned from [target], which the draw scope filled in, and flipped to the left of the dot when
 * it would otherwise run off the right edge.
 */
@Composable
fun NowDotStationsPopup(
    hovered: MutableState<Boolean>,
    table: BlendTable?,
    target: NowDotTarget,
    scale: Float,
) {
    if (!hovered.value || table == null) return
    val dotX = target.centerX ?: return
    val dotY = target.centerY ?: return

    val density = LocalDensity.current
    val widthPx = with(density) { (POPUP_WIDTH_DP * scale).dp.toPx() }
    // Flip to the dot's left rather than run off the right edge of the graph.
    val flipLeft = dotX + widthPx + GAP_PX > target.canvasWidth
    val x = if (flipLeft) dotX - widthPx - GAP_PX else dotX + GAP_PX
    val y = (dotY - 10f).coerceAtLeast(0f)

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xF01C1C1E))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width((POPUP_WIDTH_DP * scale).dp),
    ) {
        Column {
            Text(
                text = "${table.blendedLabel} blended · ${table.stationCount} stations",
                color = Color(0xFFEDEDED),
                fontSize = (11f * scale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            // Headers come from the shared formatter's own list, so a column renamed there is renamed
            // here. `raw` and `fed to blend` are the two that must never be confused: the first is what
            // the thermometer measured, the second is what the blend actually used — they differ
            // whenever a stale station was carried forward by the forecast.
            Row(Modifier.padding(top = 3.dp)) {
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[0], STATION_WEIGHT, scale)
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[2], KM_WEIGHT, scale)
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[4], AGE_WEIGHT, scale)
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[5], RAW_WEIGHT, scale)
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[6], FED_WEIGHT, scale)
                HeaderCell(BlendTableFormatter.COLUMN_HEADERS[7], WEIGHT_WEIGHT, scale)
            }
            table.rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cell(row.station, STATION_WEIGHT, scale, Color(0xFFEDEDED))
                    Cell(row.km, KM_WEIGHT, scale, Color(0xFF9A9A9E))
                    Cell(row.age, AGE_WEIGHT, scale, Color(0xFF9A9A9E))
                    // What the thermometer actually read.
                    Cell(row.raw, RAW_WEIGHT, scale, Color(0xFFEDEDED))
                    // What the blend used. Amber when the two differ because the value is
                    // forecast-carried rather than measured — the distinction the Blend tab tints for.
                    Cell(
                        row.valueFedToBlend,
                        FED_WEIGHT,
                        scale,
                        if (row.isExtrapolated) Color(0xFFE0B44A) else Color(0xFFEDEDED),
                    )
                    Cell(row.weightShare, WEIGHT_WEIGHT, scale, Color(0xFFEDEDED))
                }
            }
        }
    }
}

// Proportional columns rather than fixed widths: a fixed station column has to be sized for the
// longest id that could appear (`TOMORROW_IO_REALTIME`), which leaves a visible gap after a 4-char
// ICAO code like `KNUQ` on every ordinary row. Weights keep the columns aligned down the table while
// spending the width where the content is.
private const val STATION_WEIGHT = 2.0f
private const val KM_WEIGHT = 1.0f
private const val AGE_WEIGHT = 0.9f
private const val RAW_WEIGHT = 1.0f
private const val FED_WEIGHT = 1.7f
private const val WEIGHT_WEIGHT = 1.2f

private const val POPUP_WIDTH_DP = 250f

/** Clearance between the dot and the overlay, in px. */
private const val GAP_PX = 12f

@Composable
private fun RowScope.Cell(text: String, weight: Float, scale: Float, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = (10.5f * scale).sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, scale: Float) {
    Text(
        text = text,
        color = Color(0xFF7A7A7E),
        fontSize = (9f * scale).sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}
