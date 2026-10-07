package com.weatherwidget.shared.graph

import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.util.FailureBannerStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The failure pill drawn over a graph — the Android widget's watermark and the desktop popup's banner.
 * Every rule that decides what it says, how big it is and where it sits lives here; each platform only
 * measures text, draws, and supplies its localized strings. (Android and desktop drifted apart on
 * position and wording when each had its own copy — plans/261007-shared-failure-banner-android-desktop.md.)
 */
data class FailureBannerPill(
    val bounds: GraphRect,
    val cornerRadius: Float,
    val mainText: String,
    val mainTextSize: Float,
    val mainBaselineY: Float,
    val detailText: String?,
    val detailTextSize: Float?,
    val detailBaselineY: Float?,
    /** 1 except in [FailureBannerStage.FADED]. */
    val alpha: Float = 1f,
) {
    val centerX: Float get() = (bounds.left + bounds.right) / 2f
}

object FailureBannerLayout {
    private const val MAIN_TEXT_SIZE_DP = 15f
    private const val MAIN_MIN_TEXT_SIZE_DP = 9f
    private const val DETAIL_TEXT_SIZE_DP = 15f
    private const val DETAIL_MIN_TEXT_SIZE_DP = 11f
    private const val HORIZONTAL_PADDING_DP = 12f
    private const val VERTICAL_PADDING_DP = 6f
    private const val DETAIL_GAP_DP = 2f
    private const val CANVAS_EDGE_INSET_DP = 4f
    private const val ELLIPSIS = "…"

    // The [FailureBannerStage.TINY]/[FailureBannerStage.FADED] pill: one line, no header.
    private const val TINY_TEXT_SIZE_DP = 10f
    private const val TINY_MIN_TEXT_SIZE_DP = 8f
    private const val TINY_HORIZONTAL_PADDING_DP = 8f
    private const val TINY_VERTICAL_PADDING_DP = 3f

    /** ARGB colours; both platforms draw with these. */
    const val BACKGROUND_ARGB: Int = 0xE61A0E0E.toInt()
    const val BORDER_ARGB: Int = 0x66FF5A5A
    const val MAIN_TEXT_ARGB: Int = 0xFFFF5A5A.toInt()
    const val DETAIL_TEXT_ARGB: Int = 0xE6FF5A5A.toInt()

    /** Main line: bold sans-serif-medium with this letter spacing (em); detail line: regular sans-serif. */
    const val MAIN_LETTER_SPACING_EM = 0.08f

    /** Border stroke width in dp. */
    const val BORDER_WIDTH_DP = 1f

    /**
     * Measures are supplied by the platform: [measureMain]/[measureDetail] give a line's width at a
     * text size, [mainMetrics]/[detailMetrics] its (ascent, descent) — ascent negative, as Android's
     * FontMetrics. All sizes are in pixels, [density] being pixels per dp.
     */
    fun calculate(
        width: Float,
        height: Float,
        density: Float,
        sourceLabel: String?,
        errorCode: String?,
        failureTimeMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        failingText: String = "UPDATES FAILING",
        errorCodeText: (String) -> String = ::humanReadableErrorCode,
        stage: FailureBannerStage = FailureBannerStage.FULL,
        pausedText: String = "UPDATES PAUSED",
        quotaText: (QuotaScope, String) -> String = QuotaNoticeText::summary,
        measureMain: (String, Float) -> Float,
        measureDetail: (String, Float) -> Float,
        mainMetrics: (Float) -> Pair<Float, Float>,
        detailMetrics: (Float) -> Pair<Float, Float>,
    ): FailureBannerPill? {
        if (width <= 0f || height <= 0f || density <= 0f) return null
        val tiny = stage != FailureBannerStage.FULL
        val horizontalPadding = (if (tiny) TINY_HORIZONTAL_PADDING_DP else HORIZONTAL_PADDING_DP) * density
        val verticalPadding = (if (tiny) TINY_VERTICAL_PADDING_DP else VERTICAL_PADDING_DP) * density
        val maxPillWidth = width - CANVAS_EDGE_INSET_DP * density * 2f
        val availableTextWidth = maxPillWidth - horizontalPadding * 2f
        if (maxPillWidth <= 0f || availableTextWidth <= 0f) return null

        val quota = QuotaNotice.forSourceFailure(errorCode, failureTimeMs, detail = null)
        val headline = if (quota != null) pausedText else failingText
        val source =
            sourceLabel
                ?.takeIf { it.isNotBlank() }
                ?.uppercase(locale)
                ?.let { "$it $headline" }
                ?: headline
        val detailText =
            if (quota != null) {
                quotaText(quota.scope, QuotaNoticeText.formatResetTime(quota.resetAtMs, locale, zoneId))
            } else {
                buildDetailText(
                    errorCode = errorCode,
                    failureTimeMs = failureTimeMs,
                    nowMs = nowMs,
                    locale = locale,
                    zoneId = zoneId,
                    errorCodeText = errorCodeText,
                )
            }
        // Tiny: the detail alone ("⚠ 429 Rate Limited · 2:37 PM") — the source is already named
        // in the header, and the full pill said the rest for its first seconds.
        val rawMainText = if (tiny && detailText != null) "⚠ $detailText" else "⚠ $source"
        val mainFit = fitLine(
            text = rawMainText,
            preferredSize = (if (tiny) TINY_TEXT_SIZE_DP else MAIN_TEXT_SIZE_DP) * density,
            minimumSize = (if (tiny) TINY_MIN_TEXT_SIZE_DP else MAIN_MIN_TEXT_SIZE_DP) * density,
            availableWidth = availableTextWidth,
            measure = measureMain,
        )
        val detailFit =
            detailText?.takeUnless { tiny }?.let {
                fitLine(
                    text = it,
                    preferredSize = DETAIL_TEXT_SIZE_DP * density,
                    minimumSize = DETAIL_MIN_TEXT_SIZE_DP * density,
                    availableWidth = availableTextWidth,
                    measure = measureDetail,
                )
            }

        val (mainAscent, mainDescent) = mainMetrics(mainFit.textSize)
        val mainHeight = mainDescent - mainAscent
        val detailMetricsValue = detailFit?.let { detailMetrics(it.textSize) }
        val detailHeight =
            detailMetricsValue?.let { (ascent, descent) -> descent - ascent } ?: 0f
        val detailGap = if (detailFit != null) DETAIL_GAP_DP * density else 0f
        val pillHeight =
            mainHeight + detailHeight + detailGap + verticalPadding * 2f
        if (pillHeight > height) return null

        val contentWidth = maxOf(mainFit.width, detailFit?.width ?: 0f)
        val pillWidth = (contentWidth + horizontalPadding * 2f).coerceAtMost(maxPillWidth)
        val centerX = width / 2f
        // Vertically centred on the graph, well clear of the Android header's touch zones (which
        // reach about 37 dp down); the widget's error_pill_touch_zone is centred to match.
        val pillTop = ((height - pillHeight) / 2f).coerceAtLeast(0f)
        val bounds = GraphRect(centerX - pillWidth / 2f, pillTop, centerX + pillWidth / 2f, pillTop + pillHeight)
        val mainBaseline = bounds.top + verticalPadding - mainAscent
        val detailBaseline =
            if (detailFit != null && detailMetricsValue != null) {
                mainBaseline + mainDescent + detailGap - detailMetricsValue.first
            } else {
                null
            }
        return FailureBannerPill(
            bounds = bounds,
            cornerRadius = pillHeight / 2f,
            mainText = mainFit.text,
            mainTextSize = mainFit.textSize,
            mainBaselineY = mainBaseline,
            detailText = detailFit?.text,
            detailTextSize = detailFit?.textSize,
            detailBaselineY = detailBaseline,
            alpha = if (stage == FailureBannerStage.FADED) FailureBannerStage.FADED_ALPHA else 1f,
        )
    }

    /** No anchor = a caller that does not stage the banner: the full pill, as before stages existed. */
    fun stageFor(bannerSinceMs: Long?, nowMs: Long): FailureBannerStage =
        bannerSinceMs?.let { FailureBannerStage.at(nowMs - it) } ?: FailureBannerStage.FULL

    /** English error-code phrases (Android substitutes its localized resources). */
    fun humanReadableErrorCode(code: String): String =
        QuotaScope.ofErrorCode(code)?.let(QuotaNoticeText::short) ?: when (code) {
            "HTTP_400" -> "400 Bad Request"
            "HTTP_401" -> "401 Unauthorized"
            "HTTP_403" -> "403 Forbidden"
            "HTTP_404" -> "404 Not Found"
            "HTTP_422" -> "422 Unprocessable"
            "HTTP_429" -> "429 Rate Limited"
            "ACCESS_ERROR" -> "Access Error"
            "DNS_ERROR" -> "DNS Error"
            "CONN_REFUSED" -> "Connection Refused"
            "DATA_RESTRICTED" -> "Background Data Blocked"
            "TIMEOUT" -> "Timed Out"
            "SSL_ERROR" -> "SSL Error"
            "SOCKET_ERROR" -> "Socket Error"
            "NO_COVERAGE" -> "Not Available In This Region"
            else ->
                when {
                    code.startsWith("HTTP_5") -> "${code.removePrefix("HTTP_")} Server Error"
                    code.startsWith("HTTP_") -> "HTTP ${code.removePrefix("HTTP_")}"
                    else -> code
                }
        }

    fun formatFailureTime(
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val failure = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val now = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val sameDay = failure.toLocalDate() == now.toLocalDate()
        return DateTimeFormatter.ofPattern(if (sameDay) "h:mm a" else "MMM d, h:mm a", locale).format(failure)
    }

    private data class FittedLine(
        val text: String,
        val textSize: Float,
        val width: Float,
    )

    private fun fitLine(
        text: String,
        preferredSize: Float,
        minimumSize: Float,
        availableWidth: Float,
        measure: (String, Float) -> Float,
    ): FittedLine {
        val preferredWidth = measure(text, preferredSize)
        if (preferredWidth <= availableWidth) {
            return FittedLine(text, preferredSize, preferredWidth)
        }
        val scaledSize =
            (preferredSize * availableWidth / preferredWidth)
                .coerceIn(minimumSize, preferredSize)
        val scaledWidth = measure(text, scaledSize)
        if (scaledWidth <= availableWidth) {
            return FittedLine(text, scaledSize, scaledWidth)
        }

        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = text.take(middle).trimEnd() + ELLIPSIS
            if (measure(candidate, minimumSize) <= availableWidth) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        val fittedText =
            if (low == 0) ELLIPSIS else text.take(low).trimEnd() + ELLIPSIS
        return FittedLine(
            text = fittedText,
            textSize = minimumSize,
            width = measure(fittedText, minimumSize).coerceAtMost(availableWidth),
        )
    }

    private fun buildDetailText(
        errorCode: String?,
        failureTimeMs: Long?,
        nowMs: Long,
        locale: Locale,
        zoneId: ZoneId,
        errorCodeText: (String) -> String,
    ): String? {
        val codeText = errorCode?.let(errorCodeText)
        val timeText =
            failureTimeMs?.let {
                formatFailureTime(it, nowMs = nowMs, locale = locale, zoneId = zoneId)
            }
        return when {
            codeText != null && timeText != null -> "$codeText · $timeText"
            codeText != null -> codeText
            timeText != null -> timeText
            else -> null
        }
    }
}
