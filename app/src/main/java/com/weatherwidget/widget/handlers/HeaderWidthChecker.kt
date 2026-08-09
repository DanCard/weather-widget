package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue

enum class HeaderDisclosureLevel {
    FULL,
    NO_ICON,
    NO_ICON_NO_DELTA,
    MINIMAL,
    NONE,
}

fun HeaderDisclosureLevel.showsIcon(): Boolean = this == HeaderDisclosureLevel.FULL

fun HeaderDisclosureLevel.showsDelta(): Boolean = this == HeaderDisclosureLevel.FULL || this == HeaderDisclosureLevel.NO_ICON

fun HeaderDisclosureLevel.showsPrecip(): Boolean = this == HeaderDisclosureLevel.FULL || this == HeaderDisclosureLevel.NO_ICON || this == HeaderDisclosureLevel.NO_ICON_NO_DELTA

/**
 * Where the daily view's header buttons (current observations / forecast history) are placed.
 *
 * [CENTER] is preferred: the pair sits in the empty middle of the header with the painted date
 * beside it. [INLINE] is the fallback when the centred slot collides with the left cluster — the
 * buttons are appended to the left cluster instead, exactly as the hourly view does on narrow
 * widgets. [HIDDEN] is a last resort; the buttons must not vanish merely because the header is
 * dense, which is what the INLINE rung exists to prevent.
 */
enum class DailyIconPlacement {
    CENTER,
    INLINE,
    HIDDEN,
}

object HeaderWidthChecker {
    internal val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Scale factor applied to header icons and fonts when the header row has plenty
     * of empty space (e.g. Samsung full-width widgets).
     *
     * Returns 1.35 when occupied header content fills less than 50% of the widget width
     * AND the widget is at least 450dp wide; 1.0 otherwise.
     */
    private const val WIDE_HEADER_SCALE = 1.35f
    private const val WIDE_HEADER_OCCUPANCY_THRESHOLD = 0.50f
    private const val WIDE_HEADER_MIN_WIDTH_DP = 450

    /** Below this width the hourly header appends the inline nav icon row (graph selector |
     * stations | home | history) to the left cluster. Mirrors positionCenterIcons. */
    const val INLINE_NAV_MAX_WIDTH_DP = 420
    private const val INLINE_NAV_ZONE_XML_WIDTH_DP = 36
    private const val INLINE_NAV_FIRST_ZONE_MARGIN_DP = 1

    /**
     * Width in dp of the inline nav icon row appended to the header's left cluster on narrow
     * widgets. Mirrors the visibility and touch-zone widths applied by positionCenterIcons
     * (TemperatureTouchTargets.kt): 0 at [INLINE_NAV_MAX_WIDTH_DP] and above; per-zone width is
     * the 36dp XML default below API 31, resized to 32/40/48dp on API 31+. The stations zone is
     * hidden when [showStations] is false (non-today graphs); the graph-selector zone adds a
     * 1dp marginStart.
     */
    fun inlineNavRowWidthDp(widthDp: Int, showStations: Boolean = true): Float {
        if (widthDp >= INLINE_NAV_MAX_WIDTH_DP) return 0f
        val zoneWidthDp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            when {
                widthDp < 350 -> 32
                widthDp < 400 -> 40
                else -> 48
            }
        } else {
            INLINE_NAV_ZONE_XML_WIDTH_DP
        }
        val zoneCount = if (showStations) 4 else 3
        return (zoneWidthDp * zoneCount + INLINE_NAV_FIRST_ZONE_MARGIN_DP).toFloat()
    }

    /**
     * Width of the daily header button pair when centred, in dp, for [iconCount] buttons.
     * Unscaled, like every other measurement on this side of the header (resolveHeaderDisclosure,
     * resolveLeftClusterRightPx); the bitmap renderer applies its own labelScale.
     */
    fun dailyCenterIconsWidthDp(iconCount: Int, widthDp: Int): Float =
        if (iconCount <= 0) 0f else dailyCenterIconZoneWidthDp(widthDp) * iconCount

    /**
     * Zone width [positionDailyIcons] will actually apply, so measurement and layout cannot drift.
     *
     * The airy zone is set via `setViewLayoutWidth` (API 31+); below that the 24dp XML width
     * stands, which happens to equal the narrow zone.
     */
    fun dailyCenterIconZoneWidthDp(widthDp: Int): Float =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            widthDp >= HeaderConstants.DAILY_WIDE_HEADER_MIN_WIDTH_DP
        ) {
            HeaderConstants.DAILY_CENTER_ICON_ZONE_WIDE_DP
        } else {
            HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP
        }

    /** Width of the daily header button pair when inline in the left cluster, in dp. */
    fun dailyInlineIconsWidthDp(iconCount: Int): Float =
        if (iconCount <= 0) 0f
        else HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP * iconCount +
            HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP

    /**
     * Three-rung ladder for the daily header buttons — see [DailyIconPlacement].
     *
     * [iconCount] is the LIVE button count, never a constant: the observations button is dropped
     * when today is off screen (current observations are inherently now-ish, matching the hourly
     * view's `positionCenterIcons(isToday)`), so a navigated header reserves 24dp rather than 48dp
     * and consequently has MORE room for the date, not less.
     */
    fun resolveDailyIconPlacement(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean,
        currentTempSizeDp: Float,
        iconCount: Int,
        isIconWidth: Boolean,
        disclosure: HeaderDisclosureLevel,
    ): DailyIconPlacement {
        if (iconCount <= 0 || isIconWidth || disclosure == HeaderDisclosureLevel.NONE) {
            return DailyIconPlacement.HIDDEN
        }
        val widthPx = dpToPx(context, widthDp.toFloat())
        if (widthPx <= 0f) return DailyIconPlacement.HIDDEN

        val leftClusterRight = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = includeIcon,
            currentTempSizeDp = currentTempSizeDp,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)

        return resolveDailyIconPlacementFromBounds(
            widthPx = widthPx,
            leftClusterRight = leftClusterRight,
            apiLeft = apiLeft,
            centerIconsWidth = dpToPx(context, dailyCenterIconsWidthDp(iconCount, widthDp)),
            inlineIconsWidth = dpToPx(context, dailyInlineIconsWidthDp(iconCount)),
            gapPx = gapPx,
        )
    }

    /**
     * Pure ladder, extracted so it can be tested without a font engine — Robolectric has none, so
     * anything driven by `measureText` there is not a trustworthy assertion (see
     * `robolectric_no_font_engine`). Callers supply already-measured bounds.
     */
    fun resolveDailyIconPlacementFromBounds(
        widthPx: Float,
        leftClusterRight: Float,
        apiLeft: Float,
        centerIconsWidth: Float,
        inlineIconsWidth: Float,
        gapPx: Float,
    ): DailyIconPlacement {
        if (centerIconsWidth <= 0f) return DailyIconPlacement.HIDDEN
        val slotLeft = widthPx / 2f - centerIconsWidth / 2f
        val slotRight = widthPx / 2f + centerIconsWidth / 2f
        if (slotLeft >= leftClusterRight + gapPx && slotRight <= apiLeft - gapPx) {
            return DailyIconPlacement.CENTER
        }
        if (leftClusterRight + inlineIconsWidth + gapPx <= apiLeft) {
            return DailyIconPlacement.INLINE
        }
        return DailyIconPlacement.HIDDEN
    }

    fun computeHeaderScale(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
    ): Float {
        val widthPx = dpToPx(context, widthDp.toFloat())
        if (widthPx <= 0f) return 1f

        if (widthDp < WIDE_HEADER_MIN_WIDTH_DP) return 1f

        val leftClusterRight = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = true,
            currentTempSizeDp = currentTempSizeDp,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)

        // Occupied = left cluster + (widthPx - apiLeft), i.e. the right cluster width
        val rightClusterWidth = (widthPx - apiLeft).coerceAtLeast(0f)
        val occupiedWidth = leftClusterRight + rightClusterWidth
        val occupancy = occupiedWidth / widthPx

        return if (occupancy < WIDE_HEADER_OCCUPANCY_THRESHOLD) WIDE_HEADER_SCALE else 1f
    }

    fun resolveHeaderDisclosure(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
): HeaderDisclosureLevel {
    val widthPx = dpToPx(context, widthDp.toFloat())

        val leftClusterRightFull = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = true,
            currentTempSizeDp = currentTempSizeDp,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)

        if (leftClusterRightFull + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.FULL
        }

        val leftClusterRightNoIcon = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightNoIcon + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.NO_ICON
        }

        val leftClusterRightNoIconNoDelta = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = null,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightNoIconNoDelta + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.NO_ICON_NO_DELTA
        }

        val leftClusterRightMinimal = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = null,
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightMinimal + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.MINIMAL
        }

        return HeaderDisclosureLevel.NONE
    }

    internal fun resolveLeftClusterRightPx(
        context: Context,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        deltaLabelText: String? = null,
    ): Float {
        var width = 0f
        if (includeIcon) {
            width += dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        }
        if (!currentTempText.isNullOrBlank()) {
            width += currentTempTextWidthPx(context, currentTempText, currentTempSizeDp)
        }
        if (!deltaText.isNullOrBlank()) {
            width += dpToPx(context, HeaderConstants.DELTA_MARGIN_START_DP)
            width += textWidthPx(context, deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP)
            if (!deltaLabelText.isNullOrBlank()) {
                width += dpToPx(context, HeaderConstants.DELTA_LABEL_MARGIN_START_DP)
                width += textWidthPx(context, deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP)
            }
        }
        if (!precipText.isNullOrBlank() && precipTextSizeDp != null) {
            width += dpToPx(context, HeaderConstants.PRECIP_MARGIN_START_DP)
            width += textWidthPx(context, precipText, precipTextSizeDp)
        }
        return width
    }

    /**
     * Whether the small "from yest" caption fits after the delta in the RemoteViews header.
     * The label is purely opportunistic: it never affects disclosure/scale decisions and is
     * shown only when the full left cluster (icon/temp/delta/label/precip, plus the inline
     * nav icon row on narrow widgets) still clears the API source label on the right with
     * the standard gap.
     *
     * [inlineNavWidthDp] must be [inlineNavRowWidthDp] for hourly views: the inline nav
     * icons live in the same left LinearLayout after precip, and ignoring them lets the
     * caption stay visible while the history icon is pushed over the API label (observed
     * on Pixel 7 Pro at widthDp=373). Daily view passes the default 0 (its inline zones
     * are always GONE).
     *
     * A visible rain chance always suppresses the caption (product rule): the precip %
     * has display priority over "from yest". Callers pass [precipText] only when the
     * precip % would actually be shown, so a non-blank value here means "rain in header".
     */
    fun deltaLabelFitsInHeader(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        deltaLabelText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        inlineNavWidthDp: Float = 0f,
    ): Boolean {
        if (deltaText.isNullOrBlank() || deltaLabelText.isNullOrBlank()) return false
        if (!precipText.isNullOrBlank()) return false
        val widthPx = dpToPx(context, widthDp.toFloat())
        if (widthPx <= 0f) return false
        val leftClusterRight = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = includeIcon,
            currentTempSizeDp = currentTempSizeDp,
            deltaLabelText = deltaLabelText,
        ) + dpToPx(context, inlineNavWidthDp)
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)
        return leftClusterRight + gapPx <= apiLeft
    }

    internal fun resolveApiLeftPx(
        context: Context,
        widthPx: Float,
        apiSourceText: String,
        apiTextSizeDp: Float,
    ): Float {
        val apiContainerWidth = dpToPx(context, HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP) +
            textWidthPx(context, apiSourceText, apiTextSizeDp)
        val isDualApiText = apiSourceText.contains(" - ")
        val marginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP +
            (if (isDualApiText) 0f else HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP)
        return widthPx - dpToPx(context, marginEndDp) - apiContainerWidth
    }

    internal fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    internal fun textWidthPx(context: Context, text: String, textSizeDp: Float): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            textSizeDp,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }

    internal fun currentTempTextWidthPx(
        context: Context,
        text: String,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
    ): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            currentTempSizeDp,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }
}
