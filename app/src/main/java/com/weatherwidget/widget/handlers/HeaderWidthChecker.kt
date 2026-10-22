package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import com.weatherwidget.shared.util.HeaderFit

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
        val zoneCount = if (showStations) 4 else 3
        return nominalInlineZoneWidthDp(widthDp) * zoneCount + INLINE_NAV_FIRST_ZONE_MARGIN_DP
    }

    /** The inline nav touch zone's width before any compression ([HourlyHeaderFit]). */
    fun nominalInlineZoneWidthDp(widthDp: Int): Float =
        if (canResizeInlineZones()) {
            when {
                widthDp < 350 -> 32f
                widthDp < 400 -> 40f
                else -> 48f
            }
        } else {
            INLINE_NAV_ZONE_XML_WIDTH_DP.toFloat()
        }

    /** RemoteViews.setViewLayoutWidth exists from API 31; below it the XML width stands. */
    private fun canResizeInlineZones(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /**
     * What the hourly header shows and at what size. [inlineZoneWidthDp] is null when there is no
     * inline nav row (wide widgets) — [positionCenterIcons] then keeps its own widths.
     */
    data class HourlyHeaderLayout(
        val disclosure: HeaderDisclosureLevel,
        val inlineZoneWidthDp: Float?,
        val textScale: Float,
        val showDeltaLabel: Boolean,
    )

    /**
     * Fits the hourly header's left cluster — weather icon, current temperature, delta and its
     * "from yest" caption, rain %, and on narrow widgets the inline nav row — before the API label.
     * Narrow widgets compress before dropping ([HourlyHeaderFit]); at each [HeaderDisclosureLevel]
     * the zones narrow, then the icon and temperature shrink, and only then the next level is
     * tried. The caption is the first thing dropped: shown only when it fits without narrowing the
     * zones or shrinking the temperature, and never beside a rain chance.
     */
    fun fitHourlyHeader(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        deltaLabelText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        showStations: Boolean,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
    ): HourlyHeaderLayout {
        if (widthDp >= INLINE_NAV_MAX_WIDTH_DP) {
            val disclosure = resolveHeaderDisclosure(
                context, widthDp, apiSourceText, apiTextSizeDp, currentTempText, deltaText,
                precipText, precipTextSizeDp, currentTempSizeDp,
            )
            val label = deltaText != null && deltaLabelText != null && disclosure.showsDelta() &&
                deltaLabelFitsInHeader(
                    context = context,
                    widthDp = widthDp,
                    apiSourceText = apiSourceText,
                    apiTextSizeDp = apiTextSizeDp,
                    currentTempText = currentTempText,
                    deltaText = deltaText,
                    deltaLabelText = deltaLabelText,
                    precipText = if (disclosure.showsPrecip()) precipText else null,
                    precipTextSizeDp = precipTextSizeDp,
                    includeIcon = disclosure.showsIcon(),
                    currentTempSizeDp = currentTempSizeDp,
                )
            return HourlyHeaderLayout(disclosure, null, 1f, label)
        }

        val widthPx = dpToPx(context, widthDp.toFloat())
        val availablePx = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp) -
            dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)
        val nominalZonePx = dpToPx(context, nominalInlineZoneWidthDp(widthDp))
        val minZonePx = if (canResizeInlineZones()) {
            dpToPx(context, HourlyHeaderFit.MIN_ZONE_DP).coerceAtMost(nominalZonePx)
        } else {
            nominalZonePx
        }
        val zoneCount = if (showStations) 4 else 3
        val iconPx = dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        val tempPx = if (currentTempText.isNullOrBlank()) 0f else currentTempTextWidthPx(context, currentTempText, currentTempSizeDp)
        val deltaPx = if (deltaText.isNullOrBlank()) 0f else
            dpToPx(context, HeaderConstants.DELTA_MARGIN_START_DP) + textWidthPx(context, deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP)
        val labelPx = if (deltaLabelText.isNullOrBlank()) 0f else
            dpToPx(context, HeaderConstants.DELTA_LABEL_MARGIN_START_DP) + textWidthPx(context, deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP)
        val precipPx = if (precipText.isNullOrBlank() || precipTextSizeDp == null) 0f else
            dpToPx(context, HeaderConstants.PRECIP_MARGIN_START_DP) + textWidthPx(context, precipText, precipTextSizeDp)
        val firstZoneMarginPx = dpToPx(context, INLINE_NAV_FIRST_ZONE_MARGIN_DP.toFloat())

        fun plan(icon: Boolean, delta: Boolean, label: Boolean, precip: Boolean) = HourlyHeaderFit.plan(
            HourlyHeaderFit.Input(
                availablePx = availablePx,
                scalablePx = (if (icon) iconPx else 0f) + tempPx,
                fixedPx = (if (delta) deltaPx else 0f) + (if (label) labelPx else 0f) +
                    (if (precip) precipPx else 0f) + firstZoneMarginPx,
                zoneCount = zoneCount,
                nominalZonePx = nominalZonePx,
                minZonePx = minZonePx,
            ),
        )

        val levels = listOf(
            HeaderDisclosureLevel.FULL,
            HeaderDisclosureLevel.NO_ICON,
            HeaderDisclosureLevel.NO_ICON_NO_DELTA,
            HeaderDisclosureLevel.MINIMAL,
        )
        for (level in levels) {
            val delta = deltaPx > 0f && level.showsDelta()
            val precip = precipPx > 0f && level.showsPrecip()
            val base = plan(level.showsIcon(), delta, label = false, precip = precip)
            if (!base.fits) continue
            // The caption is the first thing dropped (user, 2026-10-09): only where there is room
            // for it as laid out — it never narrows the nav zones or shrinks the temperature, and
            // never sits beside a rain chance.
            val withLabel = if (delta && labelPx > 0f && !precip) plan(level.showsIcon(), true, label = true, precip = false) else null
            val chosen = withLabel?.takeIf {
                it.fits && it.textScale >= 1f && it.zoneWidthPx >= base.zoneWidthPx - 0.01f
            } ?: base
            return HourlyHeaderLayout(
                disclosure = level,
                inlineZoneWidthDp = chosen.zoneWidthPx / context.resources.displayMetrics.density,
                textScale = chosen.textScale,
                showDeltaLabel = chosen === withLabel,
            )
        }
        return HourlyHeaderLayout(HeaderDisclosureLevel.NONE, null, 1f, showDeltaLabel = false)
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
     * Three rungs — airy / middle / tight — because a 20dp icon in a 24dp zone leaves 4dp of air
     * and the buttons read as one control. The wider zones are set via `setViewLayoutWidth`
     * (API 31+); below that the 24dp XML width stands, which happens to equal the tight zone.
     */
    fun dailyCenterIconZoneWidthDp(widthDp: Int): Float {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP
        }
        return when {
            widthDp >= HeaderConstants.DAILY_EXTRA_WIDE_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_EXTRA_WIDE_DP
            widthDp >= HeaderConstants.DAILY_WIDE_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_WIDE_DP
            widthDp >= HeaderConstants.DAILY_MEDIUM_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_MEDIUM_DP
            else -> HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP
        }
    }

    /** Width of the daily header button pair when inline in the left cluster, in dp. */
    fun dailyInlineIconsWidthDp(iconCount: Int): Float =
        if (iconCount <= 0) 0f
        else HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP * iconCount +
            HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP

    /**
     * The daily header's pieces measured for the shared drop order ([HeaderFit],
     * `plans/261022-shared-header-fit-priority.md`); the caller resolves and logs both.
     *
     * Text, the weather icon and the API label are measured at [headerScale], the size they are
     * painted; the button zones are not scaled ([positionDailyIcons] sizes them in plain dp). A null
     * text or a zero [baseButtons] means that piece is absent, not dropped.
     */
    fun measureDailyHeader(
        context: Context,
        widthDp: Int,
        headerScale: Float,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        deltaLabelText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        dateText: String?,
        includeIcon: Boolean,
        baseButtons: Int,
        wantHome: Boolean,
        wantMonths: Boolean,
    ): HeaderFit.Measured {
        val widthPx = dpToPx(context, widthDp.toFloat())
        fun scaledDp(dp: Float) = dpToPx(context, dp) * headerScale
        fun scaledText(text: String?, sizeDp: Float, marginDp: Float = 0f) =
            if (text.isNullOrBlank()) 0f else scaledDp(marginDp) + textWidthPx(context, text, sizeDp) * headerScale
        return HeaderFit.Measured(
            width = widthPx,
            rightCluster = (widthPx - resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)) * headerScale,
            gap = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP),
            // The line the buttons end on; positionDailyIcons can only move them off the middle on
            // API 31+ (setViewLayoutMargin).
            seamOffset = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                scaledDp(HeaderFit.SEAM_OFFSET_DP)
            } else null,
            temp = if (currentTempText.isNullOrBlank()) 0f
                else currentTempTextWidthPx(context, currentTempText, HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP) * headerScale,
            rain = if (precipTextSizeDp == null) 0f
                else scaledText(precipText, precipTextSizeDp, HeaderConstants.PRECIP_MARGIN_START_DP),
            delta = scaledText(deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP, HeaderConstants.DELTA_MARGIN_START_DP),
            icon = if (includeIcon) scaledDp(HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) else 0f,
            date = scaledText(dateText, HeaderConstants.DATE_TEXT_SIZE_DP),
            caption = scaledText(deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP, HeaderConstants.DELTA_LABEL_MARGIN_START_DP),
            baseButtons = baseButtons,
            wantHome = wantHome,
            wantMonths = wantMonths,
            centreButton = dpToPx(context, dailyCenterIconZoneWidthDp(widthDp)),
            inlineButton = dpToPx(context, HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP),
            inlineLead = dpToPx(context, HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP),
        )
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
