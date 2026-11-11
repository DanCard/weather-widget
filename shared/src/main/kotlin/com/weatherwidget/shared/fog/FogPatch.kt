package com.weatherwidget.shared.fog

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The fog ellipse over a daily bar's cold end, planned once for Android (`DailyBarRenderer`) and
 * desktop (`DailyForecastGraph`); a platform only fills each [Oval] in order
 * (plans/261111-daily-bar-morning-fog.md, user 2026-11-11: "A2", an ellipse, see-through, wider than the
 * bar, drawn over it).
 *
 * The ellipse runs from just below the low up past the burn-off temperature, at least a circle so a short
 * patch shows, at most the whole bar. It is [LAYERS] nested translucent ellipses — densest low in the
 * middle, soft at the rim and fading toward the top ([TOP_SHRINK]) — instead of a gradient shader, which
 * Samsung launchers flattened (6deaeece). Their combined alpha where all overlap is [FogOpacity.alpha].
 */
object FogPatch {

    data class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float, val argb: Int)

    /** Width as a share of the column; over 1, so it spills into the neighbours (user, 2026-11-11). */
    const val WIDTH_FRACTION = 1.3f

    /** Nested ellipses; each is inset [INSET_FRACTION] of the outer size from the last. */
    // Many thin steps so the fade reads as continuous: three, then six, showed rings (user, 2026-11-11:
    // "more of a continuous spectrum, rather than defined steps"). The innermost is 30 % of the outer.
    const val LAYERS = 16
    const val INSET_FRACTION = 0.35f / (LAYERS - 1)

    /**
     * Vertically the layers shrink mostly from the top: by the innermost layer the top has come down
     * [TOP_SHRINK] of the height and the bottom up only [BOTTOM_SHRINK], so the fog is densest just above
     * the low and thins toward the burn-off (user, 2026-11-11, "B + C": a few hours of fog read as half
     * the bar when the ellipse was equally dense to its outline).
     * plans/261111-fog-height-dense-top-bottom-weighted.md
     */
    const val TOP_SHRINK = 0.75f
    const val BOTTOM_SHRINK = 0.10f

    /** Layer alphas weight the inner layers by this power of their rank, so the outline is faint. */
    const val ALPHA_WEIGHT_POWER = 2.0

    /** The smallest patch: a circle this many times the ellipse's normal width. */
    const val MIN_PATCH_SCALE = 1.25f

    /** Extra height past the fog's span, so the narrow tips do not cut it short. */
    const val TIP_EXTRA_FRACTION = 0.15f

    /** Fog white (#E6EBEF); the alpha is per layer. */
    const val FOG_RGB = 0xE6EBEF

    /**
     * @param lowTempF the column's lowest drawn temperature; the ellipse sits on it.
     * @param highTempF the column's highest drawn temperature; the ellipse never reaches past it.
     * @param yAt the graph's temperature → y mapping (y grows downward).
     * @param inkRadius half the bar's stroke width (its round cap reaches this far past the low).
     */
    fun plan(
        fog: MorningFog?,
        lowTempF: Float?,
        highTempF: Float?,
        yAt: (Float) -> Float,
        centerX: Float,
        columnWidth: Float,
        inkRadius: Float,
    ): List<Oval> {
        if (fog == null || !fog.isFoggy || lowTempF == null) return emptyList()
        val totalAlpha = FogOpacity.alpha(fog)
        if (totalAlpha <= 0f) return emptyList()
        val high = maxOf(highTempF ?: lowTempF, lowTempF)
        val top = (fog.topTempF ?: lowTempF).coerceIn(lowTempF, high)

        val baseWidth = columnWidth * WIDTH_FRACTION
        // A fog that has only just begun (top at the low) is a circle MIN_PATCH_SCALE × the ellipse's
        // width, narrowing smoothly to that width as the fog grows (user, 2026-11-11: "B: Bigger
        // minimum circle" — today's 26 % circle was hard to see over the bright today column).
        val minSize = baseWidth * MIN_PATCH_SCALE
        val bottomY = yAt(lowTempF) + inkRadius
        val barTopY = yAt(high) - inkRadius
        val span = bottomY - yAt(top)
        val height = minOf(maxOf(span * (1f + TIP_EXTRA_FRACTION), minSize), maxOf(bottomY - barTopY, minSize))
        val width = maxOf(baseWidth, minSize * minSize / height)

        // Each layer's 8-bit alpha tracks the ideal running total, so rounding (≈1.5 % layers round
        // to 4/255) does not drift the centre off the target.
        var reached = 0.0
        val alphas = (1..LAYERS).map { k ->
            val ideal = 1.0 - (1.0 - totalAlpha).pow((k.toDouble() / LAYERS).pow(ALPHA_WEIGHT_POWER))
            val needed = 1.0 - (1.0 - ideal) / (1.0 - reached)
            val byte = (needed * 255.0).roundToInt().coerceIn(0, 255)
            reached = 1.0 - (1.0 - reached) * (1.0 - byte / 255.0)
            byte
        }
        return (0 until LAYERS).filter { alphas[it] > 0 }.map { i ->
            val argb = (alphas[i] shl 24) or FOG_RGB
            val insetX = width * INSET_FRACTION * i
            val t = i.toFloat() / (LAYERS - 1)
            Oval(
                left = centerX - width / 2f + insetX,
                top = bottomY - height + height * TOP_SHRINK * t,
                right = centerX + width / 2f - insetX,
                bottom = bottomY - height * BOTTOM_SHRINK * t,
                argb = argb,
            )
        }
    }
}

/**
 * Which fog a daily column shows, one rule for every column on both platforms: the provider's observed
 * fog ([observed], `daily_history`) combined ([MorningFog.combine]) with its whole-morning forecast
 * answer, the newest fetch's row, else the stored hours. Future days have no observation, so they
 * read the forecast alone.
 *
 * The forecast side always covers the whole 00–12 window, whatever the time: a fetch made after the
 * morning is the provider's hindsight (newest fetch wins). Reading only the hours still ahead made
 * Open-Meteo's dense fog (0.0 mi, 04–08) fade to its word-only "Light Fog" observation as each
 * foggy hour passed (Fold 4, 2026-11-11: "Meteo earlier today had fog, but it disappeared").
 * plans/261111-today-fog-whole-morning.md
 */
object DailyMorningFog {
    data class Resolved(val fog: MorningFog?, val from: String)

    fun forColumn(
        observed: MorningFog?,
        row: MorningFog?,
        fromHourly: () -> MorningFog?,
    ): Resolved {
        val forecast = row ?: fromHourly()
        val from = listOfNotNull(
            "observed".takeIf { observed != null },
            (if (row != null) "row" else "hourly").takeIf { forecast != null },
        ).joinToString("+").ifEmpty { "none" }
        return Resolved(MorningFog.combine(observed, forecast), from)
    }
}
