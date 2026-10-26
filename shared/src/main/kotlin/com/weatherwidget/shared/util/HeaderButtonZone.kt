package com.weatherwidget.shared.util

/**
 * How much room each centred header button takes — its icon plus the air around it — one rule for
 * daily and hourly headers on both platforms (`plans/261025-shared-header-button-air.md`).
 *
 * The air grows with the header's width, in dp before any header scaling: a 20dp icon in a 24dp
 * zone leaves 4dp between neighbours and the row reads as one control (Pixel 7 Pro's 373dp hourly
 * header, 2026-10-25); [HeaderFit] squeezes to 6dp at the least (user, 2026-10-25), never 4.
 * Narrow headers prefer 8dp (user, 2026-10-26): 6dp as the preferred air left nothing to squeeze,
 * and desktop's square window (802dp at ×2.45 ≈ a 327dp header) drew its buttons clumped with half
 * the row empty. Wide headers keep 32dp, against misclicks.
 *
 * Width only, never the row's content: air that grew into leftover room moved every button on a
 * source tap (user, 2026-10-26), where the row should only gain or lose home on its left.
 */
object HeaderButtonZone {
    /** The least air [HeaderFit] squeezes a centred button's zone to. */
    const val MIN_AIR_DP = 6f

    /** Preferred air around one centred button for a header [widthDp] wide (unscaled dp). */
    fun airDp(widthDp: Float): Float = when {
        widthDp >= 450f -> 32f
        widthDp >= 420f -> 20f
        widthDp >= 360f -> 12f
        else -> 8f
    }

    /** Preferred zone: an [iconDp] icon with [airDp] around it. */
    fun zoneDp(iconDp: Float, widthDp: Float): Float = iconDp + airDp(widthDp)

    /** Narrowest zone: the icon with [MIN_AIR_DP] around it. */
    fun minZoneDp(iconDp: Float): Float = iconDp + MIN_AIR_DP
}
