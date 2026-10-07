package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.weatherwidget.R
import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.graph.FailureBannerLayout
import com.weatherwidget.shared.util.FailureBannerStage
import java.time.ZoneId
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
    /** 1 except in [FailureBannerStage.FADED]. */
    val alpha: Float = 1f,
)

/**
 * Android drawing of the shared failure pill ([FailureBannerLayout] decides wording, size and
 * position for the widget and the desktop popup alike); this side measures with [Paint], draws on a
 * [Canvas], and supplies the localized strings.
 */
internal object GraphFailureWatermarkRenderer {
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
        bannerSinceMs: Long? = null,
        pausedText: String = "UPDATES PAUSED",
        quotaText: (QuotaScope, String) -> String = QuotaNoticeText::summary,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val stage = stageFor(bannerSinceMs, nowMs)
        val mainPaint = createMainPaint()
        val detailPaint = createDetailPaint()
        val layout = calculateLayout(
            width = width,
            height = height,
            density = density,
            sourceLabel = sourceLabel,
            errorCode = errorCode,
            failureTimeMs = failureTimeMs,
            nowMs = nowMs,
            failingText = failingText,
            errorCodeText = errorCodeText,
            stage = stage,
            pausedText = pausedText,
            quotaText = quotaText,
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
            color = FailureBannerLayout.BACKGROUND_ARGB
        }.fade(layout.alpha)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = FailureBannerLayout.BORDER_WIDTH_DP * density
            color = FailureBannerLayout.BORDER_ARGB
        }.fade(layout.alpha)
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
        mainPaint.fade(layout.alpha)
        detailPaint.fade(layout.alpha)
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

    /** [draw] with the Android strings — what every graph renderer calls. */
    fun drawLocalized(
        context: Context,
        canvas: Canvas,
        width: Float,
        height: Float,
        density: Float,
        sourceLabel: String?,
        errorCode: String?,
        failureTimeMs: Long?,
        bannerSinceMs: Long?,
    ) = draw(
        canvas, width, height, density, sourceLabel, errorCode, failureTimeMs,
        failingText = context.getString(R.string.updates_failing),
        errorCodeText = { code -> localizedErrorCodeText(context, code) },
        bannerSinceMs = bannerSinceMs,
        pausedText = context.getString(R.string.updates_paused),
        quotaText = { code, resetTime -> localizedQuotaText(context, code, resetTime) },
    )

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
        stage: FailureBannerStage = FailureBannerStage.FULL,
        pausedText: String = "UPDATES PAUSED",
        quotaText: (QuotaScope, String) -> String = QuotaNoticeText::summary,
        measureMain: (String, Float) -> Float,
        measureDetail: (String, Float) -> Float,
        mainMetrics: (Float) -> Pair<Float, Float>,
        detailMetrics: (Float) -> Pair<Float, Float>,
    ): FailureWatermarkLayout? =
        FailureBannerLayout.calculate(
            width, height, density, sourceLabel, errorCode, failureTimeMs, nowMs, locale, zoneId,
            failingText, errorCodeText, stage, pausedText, quotaText,
            measureMain, measureDetail, mainMetrics, detailMetrics,
        )?.let { pill ->
            FailureWatermarkLayout(
                pillBounds = RectF(pill.bounds.left, pill.bounds.top, pill.bounds.right, pill.bounds.bottom),
                cornerRadius = pill.cornerRadius,
                mainText = pill.mainText,
                mainTextSize = pill.mainTextSize,
                mainBaselineY = pill.mainBaselineY,
                detailText = pill.detailText,
                detailTextSize = pill.detailTextSize,
                detailBaselineY = pill.detailBaselineY,
                alpha = pill.alpha,
            )
        }

    internal fun stageFor(bannerSinceMs: Long?, nowMs: Long): FailureBannerStage =
        FailureBannerLayout.stageFor(bannerSinceMs, nowMs)

    /** The localized [QuotaNoticeText.summary]: "<which> quota used · resets <time>". */
    internal fun localizedQuotaText(context: Context, scope: QuotaScope, resetTime: String): String = when (scope) {
        QuotaScope.SOURCE -> context.getString(R.string.watermark_quota_daily, resetTime)
        QuotaScope.HOURLY_FORECAST -> context.getString(R.string.watermark_quota_hourly_forecast, resetTime)
        QuotaScope.DAILY_FORECAST -> context.getString(R.string.watermark_quota_daily_forecast, resetTime)
    }

    internal fun formatResetTime(epochMs: Long, locale: Locale, zoneId: ZoneId): String =
        QuotaNoticeText.formatResetTime(epochMs, locale, zoneId)

    private fun Paint.fade(alpha: Float): Paint = apply {
        if (alpha < 1f) this.alpha = (this.alpha * alpha).toInt()
    }

    @androidx.annotation.VisibleForTesting
    internal fun humanReadableErrorCode(code: String): String = FailureBannerLayout.humanReadableErrorCode(code)

    /** The localized [QuotaNoticeText.short]. */
    internal fun localizedQuotaShort(context: Context, scope: QuotaScope): String = when (scope) {
        QuotaScope.SOURCE -> context.getString(R.string.watermark_quota_daily_short)
        QuotaScope.HOURLY_FORECAST -> context.getString(R.string.watermark_quota_hourly_forecast_short)
        QuotaScope.DAILY_FORECAST -> context.getString(R.string.watermark_quota_daily_forecast_short)
    }

    /** Localized error-code phrases for the watermark detail line (Android side). */
    @androidx.annotation.VisibleForTesting
    internal fun localizedErrorCodeText(context: Context, code: String): String =
        QuotaScope.ofErrorCode(code)?.let { localizedQuotaShort(context, it) } ?: when (code) {
            "HTTP_400" -> context.getString(R.string.watermark_http_400)
            "HTTP_401" -> context.getString(R.string.watermark_http_401)
            "HTTP_403" -> context.getString(R.string.watermark_http_403)
            "HTTP_404" -> context.getString(R.string.watermark_http_404)
            "HTTP_422" -> context.getString(R.string.watermark_http_422)
            "HTTP_429" -> context.getString(R.string.watermark_http_429)
            "ACCESS_ERROR" -> context.getString(R.string.watermark_access_error)
            "DNS_ERROR" -> context.getString(R.string.watermark_dns_error)
            "CONN_REFUSED" -> context.getString(R.string.watermark_conn_refused)
            "DATA_RESTRICTED" -> context.getString(R.string.watermark_data_restricted)
            "TIMEOUT" -> context.getString(R.string.watermark_timeout)
            "SSL_ERROR" -> context.getString(R.string.watermark_ssl_error)
            "SOCKET_ERROR" -> context.getString(R.string.watermark_socket_error)
            "NO_COVERAGE" -> context.getString(R.string.watermark_no_coverage)
            else ->
                when {
                    code.startsWith("HTTP_5") ->
                        context.getString(R.string.watermark_server_error, code.removePrefix("HTTP_"))

                    code.startsWith("HTTP_") ->
                        "HTTP ${code.removePrefix("HTTP_")}"

                    else -> code
                }
        }

    internal fun formatFailureTime(
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = FailureBannerLayout.formatFailureTime(epochMs, nowMs, locale, zoneId)

    private fun createMainPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FailureBannerLayout.MAIN_TEXT_ARGB
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 26) letterSpacing = FailureBannerLayout.MAIN_LETTER_SPACING_EM
        }

    private fun createDetailPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FailureBannerLayout.DETAIL_TEXT_ARGB
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
