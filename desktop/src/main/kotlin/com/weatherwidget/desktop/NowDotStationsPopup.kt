package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.BlendBreakdown
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hover the graph's "now" dot to see the stations behind the blended actual temperature.
 *
 * The dot is the one pixel on the graph that claims to be a measurement, and it is the least
 * self-explanatory: it is an IDW blend of several stations, some of them carried forward by the
 * forecast, so it can legitimately sit outside the range of every reading in the list (see the
 * `observed_dot_is_forecast_extrapolated` finding). This overlay answers "what are the nearby
 * thermometers reading right now" without leaving the graph.
 *
 * **Nothing is derived here.** The cards are the Stations window's Observations tab — its default
 * view — rendered by the same [ObservationCard] over the same [visibleStationRows] selection, so the
 * two surfaces list the same stations with the same values by construction. (The overlay used to
 * show the Blend tab's weight table; that answers "how was the blend weighted", which is the tab's
 * question, not the glance's.)
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

    fun clear() {
        centerX = null
        centerY = null
        radius = 0f
    }

    fun set(x: Float, y: Float, r: Float) {
        centerX = x
        centerY = y
        radius = r
    }
}

/** Extra slop around the painted dot so a ~5 px target is comfortable to hit with a mouse. */
const val NOW_DOT_HOVER_SLOP_PX: Float = 7f

/**
 * True when [pointer] is within the dot's painted radius plus [NOW_DOT_HOVER_SLOP_PX].
 *
 * Pure so the geometry is testable without a UI harness, the same reason [nowDotStationCards] is. A target with no centre (the dot is off-window, or nothing has been drawn yet) never hits.
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
 * What the overlay shows: station cards, nothing else (a header naming the blended figure was tried
 * and cut — the dot's own label already says it).
 *
 * [shown] is the [visibleStationRows] selection over [observations] — the Observations tab's exact
 * row set — reduced to OFFICIAL stations and capped at [maxRows] (the nearest first); [remaining] is
 * how many the cap cut.
 */
data class NowDotStationCards(
    val officialOnly: Boolean,
    val shown: List<ObservationReading>,
    val remaining: Int,
)

/**
 * The cards to show, or null when there is nothing worth popping up.
 *
 * Personal stations are dropped when at least one OFFICIAL station is present: near a city the PWS
 * rows outnumber the official ones two to one, and the overlay is a glance, not the list. When the
 * selection has no official station at all (a Synoptic-borrowing setup in PWS-only country) every
 * row is kept rather than showing nothing — [NowDotStationCards.officialOnly] reports which happened.
 *
 * Null rather than an empty frame when the blend produced no point (there is no dot to explain) or
 * the selection is empty, so the caller draws nothing at all.
 */
fun nowDotStationCards(
    breakdowns: List<BlendBreakdown>,
    observations: List<ObservationReading>,
    source: WeatherSource,
    maxRows: Int = MAX_POPUP_ROWS,
): NowDotStationCards? {
    val newest = breakdowns.firstOrNull() ?: return null
    if (newest.contributions.isEmpty()) return null
    val all = visibleStationRows(observations, source)
    if (all.isEmpty()) return null
    val official = all.filter { it.stationType == OFFICIAL_STATION_TYPE }
    val officialOnly = official.isNotEmpty()
    val rows = if (officialOnly) official else all
    return NowDotStationCards(
        officialOnly = officialOnly,
        shown = rows.take(maxRows),
        remaining = (rows.size - maxRows).coerceAtLeast(0),
    )
}

/** The `stationType` value the Observations tab tints green; everything else is a personal station. */
const val OFFICIAL_STATION_TYPE = "OFFICIAL"

/** The nearest official station only: one card is the glance. */
const val MAX_POPUP_ROWS: Int = 1

/**
 * Places the overlay beside the dot, inside the graph's own bounds.
 *
 * Pure so the cases are testable without a UI harness. [anchor] is the dot's bounding box (centre ±
 * radius) and [container] the graph area, both in graph pixels. The overlay is a sibling of the
 * Canvas laid out by [NowDotStationsPopup]'s `Layout`, which is what lets it know its own measured
 * size and the graph's — the two things the old `offset()` Box lacked when it ran off both edges.
 *
 * Why not a `Popup`: tried first. A popup is a separate pointer layer, and opening one sends the
 * Canvas a pointer Exit even when the popup is nowhere near the pointer — hover false, popup gone,
 * hover true again: a flicker loop the moment the dot is touched.
 *
 * Rules, in order: [fitWidth] first so the overlay can always sit beside the dot; sit to the right
 * with [GAP_PX] clearance; flip left when the right would overflow; otherwise the wider side,
 * clamped to the edge; then clamp vertically. The overlay must never cover the dot — the pointer
 * landing on it would end the very hover that opened it.
 */
object NowDotPopupPositioner {
    /**
     * The widest the overlay may be and still fit beside the dot on its roomier side, capped at
     * [preferred]. Near the centre of a default-zoom graph the dot leaves under half the width on
     * either side; a card list wider than that has nowhere to go, so the cards narrow (station names
     * ellipsize, as they do in a narrow Stations window) rather than the overlay covering the dot.
     */
    fun fitWidth(anchor: IntRect, containerWidth: Int, preferred: Int): Int {
        val gap = GAP_PX.roundToInt()
        val roomRight = containerWidth - anchor.right - gap
        val roomLeft = anchor.left - gap
        return minOf(preferred, maxOf(roomRight, roomLeft)).coerceAtLeast(0)
    }

    fun calculate(anchor: IntRect, container: IntSize, popup: IntSize): IntOffset {
        val gap = GAP_PX.roundToInt()
        val right = anchor.right + gap
        val left = anchor.left - gap - popup.width
        val x = when {
            right + popup.width <= container.width -> right
            left >= 0 -> left
            // Neither side fits (popup wider than fitWidth allows only if the caller ignored it):
            // take the roomier side and clamp to that edge.
            container.width - anchor.right >= anchor.left -> (container.width - popup.width).coerceAtLeast(0)
            else -> 0
        }
        // Top edge a little above the dot, as before, then kept inside the graph.
        val preferredY = anchor.top - 10
        val maxY = (container.height - popup.height).coerceAtLeast(0)
        val y = preferredY.coerceIn(0, maxY)
        return IntOffset(x, y)
    }
}

/**
 * The overlay itself. Reads [hovered] internally — see [nowDotHoverInput] for why that matters.
 *
 * A `Layout` filling the graph measures the card column with [NowDotPopupPositioner.fitWidth] as
 * its maximum (it wraps to its content below that) and places it where
 * [NowDotPopupPositioner.calculate] says. The layout node has no pointer-input
 * modifier of its own, and the cards are built non-clickable, so the Canvas underneath keeps the
 * hover; the positioner keeps the cards off the dot.
 */
@Composable
fun NowDotStationsPopup(
    hovered: MutableState<Boolean>,
    cards: NowDotStationCards?,
    target: NowDotTarget,
    scale: Float,
    nowMs: Long,
    useCelsius: Boolean,
) {
    if (!hovered.value || cards == null) return
    val dotX = target.centerX ?: return
    val dotY = target.centerY ?: return

    val r = target.radius.roundToInt()
    val anchor = IntRect(dotX.roundToInt() - r, dotY.roundToInt() - r, dotX.roundToInt() + r, dotY.roundToInt() + r)
    val preferredWidthPx = with(LocalDensity.current) { (POPUP_WIDTH_DP * scale).dp.roundToPx() }
    // Card fonts are the Observations tab's sizes times this — a glance, smaller than the tab.
    val fontScale = scale * CARD_FONT_SCALE_PER_UI_SCALE

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // IntrinsicSize.Max: the column is as wide as its widest line and no wider, and every
            // card fills that width, so the temperatures line up without a slab of empty space.
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xF01C1C1E))
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                cards.shown.forEach { obs ->
                    ObservationCard(
                        obs = obs,
                        useCelsius = useCelsius,
                        nowMs = nowMs,
                        fontScale = fontScale,
                        showRawMetar = false,
                        clickable = false,
                        compact = true,
                    )
                }
                // Rows beyond the cap are not footnoted; the Stations window lists them.
            }
        },
    ) { measurables, constraints ->
        val container = IntSize(constraints.maxWidth, constraints.maxHeight)
        val width = NowDotPopupPositioner.fitWidth(anchor, container.width, preferredWidthPx)
        val placeable = measurables.single().measure(
            Constraints(maxWidth = width, maxHeight = container.height),
        )
        val pos = NowDotPopupPositioner.calculate(anchor, container, IntSize(placeable.width, placeable.height))
        layout(container.width, container.height) { placeable.place(pos) }
    }
}

/** Upper bound; the column wraps to its content below this. */
private const val POPUP_WIDTH_DP = 220f

/** Card text = Observations-tab size × uiScale × this (0.5 read as a list, not a glance). */
private const val CARD_FONT_SCALE_PER_UI_SCALE = 0.36f

/** Clearance between the dot and the overlay, in px. */
private const val GAP_PX = 12f
