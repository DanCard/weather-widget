package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.weatherwidget.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class FailureWatermarkLayout(
    val pillBounds: RectF,
    val cornerRadius: Float,
    val mainText: String,
    val mainTextSize: Float,
    val mainBaselineY: Float,
    val detailText: String?,
    val detailTextSize: Float?,
    val detailBaselineY: Float?,
)

/** Failure-watermark formatting, width fitting, and Android drawing. */
internal object GraphFailureWatermarkRenderer {
    private const val MAIN_TEXT_SIZE_DP = 15f
    private const val MAIN_MIN_TEXT_SIZE_DP = 9f
    private const val DETAIL_TEXT_SIZE_DP = 15f
    private const val DETAIL_MIN_TEXT_SIZE_DP = 11f
    private const val HORIZONTAL_PADDING_DP = 12f
    private const val VERTICAL_PADDING_DP = 6f
    private const val DETAIL_GAP_DP = 2f
    private const val CANVAS_EDGE_INSET_DP = 4f
    private const val PILL_TOP_DP = 8f
    private const val ELLIPSIS = "…"

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        density: Float,
        sourceLabel: String? = null,
        errorCode: String? = null,
        failureTimeMs: Long? = null,
        failingText: String,
        errorCodeText: (String) -> String,
    ) {
        val mainPaint = createMainPaint()
        val detailPaint = createDetailPaint()
        val layout = calculateLayout(
            width = width,
            height = height,
            density = density,
            sourceLabel = sourceLabel,
            errorCode = errorCode,
            failureTimeMs = failureTimeMs,
            failingText = failingText,
            errorCodeText = errorCodeText,
            measureMain = { text, textSize ->
                mainPaint.textSize = textSize
                mainPaint.measureText(text)
            },
            measureDetail = { text, textSize ->
                detailPaint.textSize = textSize
                detailPaint.measureText(text)
            },
            mainMetrics = { textSize ->
                mainPaint.textSize = textSize
                mainPaint.metricsPair()
            },
            detailMetrics = { textSize ->
                detailPaint.textSize = textSize
                detailPaint.metricsPair()
            },
        ) ?: return

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E61A0E0E")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.parseColor("#66FF5A5A")
        }
        canvas.drawRoundRect(
            layout.pillBounds,
            layout.cornerRadius,
            layout.cornerRadius,
            backgroundPaint,
        )
        canvas.drawRoundRect(
            layout.pillBounds,
            layout.cornerRadius,
            layout.cornerRadius,
            borderPaint,
        )

        val centerX = layout.pillBounds.centerX()
        mainPaint.textSize = layout.mainTextSize
        canvas.drawText(layout.mainText, centerX, layout.mainBaselineY, mainPaint)
        if (
            layout.detailText != null &&
            layout.detailTextSize != null &&
            layout.detailBaselineY != null
        ) {
            detailPaint.textSize = layout.detailTextSize
            canvas.drawText(
                layout.detailText,
                centerX,
                layout.detailBaselineY,
                detailPaint,
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun calculateLayout(
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
        measureMain: (String, Float) -> Float,
        measureDetail: (String, Float) -> Float,
        mainMetrics: (Float) -> Pair<Float, Float>,
        detailMetrics: (Float) -> Pair<Float, Float>,
    ): FailureWatermarkLayout? {
        if (width <= 0f || height <= 0f || density <= 0f) return null
        val horizontalPadding = HORIZONTAL_PADDING_DP * density
        val verticalPadding = VERTICAL_PADDING_DP * density
        val maxPillWidth = width - CANVAS_EDGE_INSET_DP * density * 2f
        val availableTextWidth = maxPillWidth - horizontalPadding * 2f
        if (maxPillWidth <= 0f || availableTextWidth <= 0f) return null

        val source =
            sourceLabel
                ?.takeIf { it.isNotBlank() }
                ?.uppercase(locale)
                ?.let { "$it $failingText" }
                ?: failingText
        val rawMainText = "⚠ $source"
        val detailText =
            buildDetailText(
                errorCode = errorCode,
                failureTimeMs = failureTimeMs,
                nowMs = nowMs,
                locale = locale,
                zoneId = zoneId,
                errorCodeText = errorCodeText,
            )
        val mainFit = fitLine(
            text = rawMainText,
            preferredSize = MAIN_TEXT_SIZE_DP * density,
            minimumSize = MAIN_MIN_TEXT_SIZE_DP * density,
            availableWidth = availableTextWidth,
            measure = measureMain,
        )
        val detailFit =
            detailText?.let {
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
        val pillTop = (PILL_TOP_DP * density).coerceAtMost(height - pillHeight).coerceAtLeast(0f)
        val pillBounds =
            RectF(
                centerX - pillWidth / 2f,
                pillTop,
                centerX + pillWidth / 2f,
                pillTop + pillHeight,
            )
        val mainBaseline = pillBounds.top + verticalPadding - mainAscent
        val detailBaseline =
            if (detailFit != null && detailMetricsValue != null) {
                mainBaseline + mainDescent + detailGap - detailMetricsValue.first
            } else {
                null
            }
        return FailureWatermarkLayout(
            pillBounds = pillBounds,
            cornerRadius = pillHeight / 2f,
            mainText = mainFit.text,
            mainTextSize = mainFit.textSize,
            mainBaselineY = mainBaseline,
            detailText = detailFit?.text,
            detailTextSize = detailFit?.textSize,
            detailBaselineY = detailBaseline,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun humanReadableErrorCode(code: String): String =
        when (code) {
            "HTTP_400" -> "400 Bad Request"
            "HTTP_401" -> "401 Unauthorized"
            "HTTP_403" -> "403 Forbidden"
            "HTTP_404" -> "404 Not Found"
            "HTTP_422" -> "422 Unprocessable"
            "HTTP_429" -> "429 Rate Limited"
            "ACCESS_ERROR" -> "Access Error"
            "DNS_ERROR" -> "DNS Error"
            "CONN_REFUSED" -> "Connection Refused"
            "TIMEOUT" -> "Timed Out"
            "SSL_ERROR" -> "SSL Error"
            "SOCKET_ERROR" -> "Socket Error"
            else ->
                when {
                    code.startsWith("HTTP_5") ->
                        "${code.removePrefix("HTTP_")} Server Error"

                    code.startsWith("HTTP_") ->
                        "HTTP ${code.removePrefix("HTTP_")}"

                    else -> code
                }
        }

    /** Localized error-code phrases for the watermark detail line (Android side). */
    @androidx.annotation.VisibleForTesting
    internal fun localizedErrorCodeText(context: Context, code: String): String =
        when (code) {
            "HTTP_400" -> context.getString(R.string.watermark_http_400)
            "HTTP_401" -> context.getString(R.string.watermark_http_401)
            "HTTP_403" -> context.getString(R.string.watermark_http_403)
            "HTTP_404" -> context.getString(R.string.watermark_http_404)
            "HTTP_422" -> context.getString(R.string.watermark_http_422)
            "HTTP_429" -> context.getString(R.string.watermark_http_429)
            "ACCESS_ERROR" -> context.getString(R.string.watermark_access_error)
            "DNS_ERROR" -> context.getString(R.string.watermark_dns_error)
            "CONN_REFUSED" -> context.getString(R.string.watermark_conn_refused)
            "TIMEOUT" -> context.getString(R.string.watermark_timeout)
            "SSL_ERROR" -> context.getString(R.string.watermark_ssl_error)
            "SOCKET_ERROR" -> context.getString(R.string.watermark_socket_error)
            else ->
                when {
                    code.startsWith("HTTP_5") ->
                        context.getString(R.string.watermark_server_error, code.removePrefix("HTTP_"))

                    code.startsWith("HTTP_") ->
                        "HTTP ${code.removePrefix("HTTP_")}"

                    else -> code
                }
        }

    @androidx.annotation.VisibleForTesting
    internal fun formatFailureTime(
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val failure = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val now = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val pattern = if (failure.toLocalDate() == now.toLocalDate()) "h:mm a" else "MMM d, h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(failure)
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

    private fun createMainPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFF5A5A")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 26) letterSpacing = 0.08f
        }

    private fun createDetailPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FF5A5A")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

    private fun Paint.metricsPair(): Pair<Float, Float> {
        val metrics = fontMetrics
        return if (metrics != null && (metrics.ascent != 0f || metrics.descent != 0f)) {
            metrics.ascent to metrics.descent
        } else {
            -textSize to textSize * 0.2f
        }
    }
}
