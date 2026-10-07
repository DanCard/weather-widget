package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.weatherwidget.R
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.ProviderErrorDetails
import com.weatherwidget.widget.GraphFailureWatermarkRenderer
import com.weatherwidget.widget.WidgetStateManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the widget's failure pill opens: the source's failure in plain words, the provider's own
 * details (endpoint, status, quota name/limit/period), and the full response. Replaces the pill
 * jumping straight to Android's app data usage screen, which was right only for DATA_RESTRICTED;
 * that screen is now a button at the bottom, and API-key Settings one shown for key errors.
 */
class SourceErrorDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_error_details)

        val source = intent.getStringExtra(EXTRA_SOURCE_ID)
            ?.let { id -> WeatherSource.entries.firstOrNull { it.id == id } }
            ?: WidgetStateManager(this).getPrimarySource()
        val content = SourceErrorDetailsContent.build(this, source, WidgetStateManager(this))

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        // Always shown; Back from Android's screen returns here.
        findViewById<Button>(R.id.error_details_background_data).setOnClickListener {
            startActivity(Intent(this, BackgroundDataResolutionActivity::class.java))
        }
        findViewById<Button>(R.id.error_details_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.error_details_source).text = source.displayName.orEmpty()

        val headline = findViewById<TextView>(R.id.error_details_headline)
        val summary = findViewById<TextView>(R.id.error_details_summary)
        val explanation = findViewById<TextView>(R.id.error_details_explanation)
        if (content == null) {
            headline.visibility = View.GONE
            summary.text = getString(R.string.error_details_none)
            explanation.visibility = View.GONE
            findViewById<View>(R.id.error_details_section).visibility = View.GONE
            findViewById<View>(R.id.error_details_toggle_response).visibility = View.GONE
            return
        }
        headline.text = content.headline
        summary.text = content.summary
        explanation.text = content.explanation
        bindRows(content.rows)
        bindResponse(content.rawResponse)

        findViewById<Button>(R.id.error_details_api_key_settings).apply {
            visibility = if (content.offerApiKeySettings) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@SourceErrorDetailsActivity, SettingsActivity::class.java)) }
        }

    }

    private fun bindRows(rows: List<Pair<String, String>>) {
        val container = findViewById<LinearLayout>(R.id.error_details_rows)
        val primary = ContextCompat.getColor(this, R.color.widget_text_primary)
        val secondary = ContextCompat.getColor(this, R.color.widget_text_secondary)
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
        rows.forEach { (label, value) ->
            container.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, pad, 0, pad)
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(secondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                    addView(TextView(context).apply {
                        text = value
                        setTextColor(primary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTextIsSelectable(true)
                    })
                },
            )
        }
    }

    private fun bindResponse(raw: String?) {
        val toggle = findViewById<Button>(R.id.error_details_toggle_response)
        val scroll = findViewById<HorizontalScrollView>(R.id.error_details_response_scroll)
        if (raw == null) {
            toggle.visibility = View.GONE
            return
        }
        findViewById<TextView>(R.id.error_details_response).text = raw
        toggle.setOnClickListener {
            val show = scroll.visibility != View.VISIBLE
            scroll.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.error_details_hide_response else R.string.error_details_show_response)
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"

        fun intent(context: Context, sourceId: String?): Intent =
            Intent(context, SourceErrorDetailsActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Everything the details page shows, assembled without views so it can be tested directly. */
internal data class SourceErrorDetailsContent(
    val headline: String,
    val summary: String,
    val explanation: String,
    val rows: List<Pair<String, String>>,
    val rawResponse: String?,
    val offerApiKeySettings: Boolean,
) {
    companion object {
        private val AUTH_CODES = setOf("HTTP_401", "HTTP_403", "ACCESS_ERROR")

        /** Null when nothing is failing for [source] (the pill is gone, or was tapped as it cleared). */
        fun build(
            context: Context,
            source: WeatherSource,
            state: WidgetStateManager,
            nowMs: Long = System.currentTimeMillis(),
            locale: Locale = Locale.getDefault(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): SourceErrorDetailsContent? {
            val count = state.getSourceFailureCount(source)
            // No source-wide failure: the pill may be one product's quota block (hourly views or the
            // daily view), recorded apart from the failure streak.
            val productBlock = if (count > 0) {
                null
            } else {
                ForecastProduct.entries.firstNotNullOfOrNull { product ->
                    state.getProductQuota(source, product, nowMs)?.let { product to it }
                } ?: return null
            }
            val code = productBlock?.let { GoogleQuota.errorCodeFor(it.first) } ?: state.getSourceLastErrorCode(source)
            val failedAt = productBlock?.second?.sinceMs ?: state.getSourceLastFailureTime(source)
            val detail = productBlock?.second?.detail ?: state.getSourceLastFailureDetail(source)
            val details = ProviderErrorDetails.parse(detail)
            val quota = productBlock
                ?.let { (product, block) -> QuotaNotice.forProductBlock(product, block.untilMs, detail) }
                ?: QuotaNotice.forSourceFailure(code, failedAt, detail)
            val resetText = quota?.let { GraphFailureWatermarkRenderer.formatResetTime(it.resetAtMs, locale, zone) }
            val codeText = code?.let { GraphFailureWatermarkRenderer.localizedErrorCodeText(context, it) }

            val explanation = when {
                resetText != null -> context.getString(R.string.error_details_explain_quota, resetText)
                code == "HTTP_429" -> context.getString(R.string.error_details_explain_rate_limit)
                code in AUTH_CODES -> context.getString(R.string.error_details_explain_auth)
                code == "DATA_RESTRICTED" -> context.getString(R.string.error_details_explain_data)
                else -> context.getString(R.string.error_details_explain_generic)
            }

            val rows = buildList {
                codeText?.let { add(context.getString(R.string.error_details_label_error) to it) }
                details?.request?.let { add(context.getString(R.string.error_details_label_request) to it) }
                details?.httpStatus?.let { add(context.getString(R.string.error_details_label_status) to it.toString()) }
                details?.providerMessage?.let { add(context.getString(R.string.error_details_label_message) to it) }
                details?.quotaName?.let { add(context.getString(R.string.error_details_label_quota) to it) }
                details?.quotaLimit?.let { limit ->
                    val text = when (details.quotaPeriod) {
                        ProviderErrorDetails.QuotaPeriod.DAY -> context.getString(R.string.error_details_limit_per_day, limit)
                        ProviderErrorDetails.QuotaPeriod.MINUTE -> context.getString(R.string.error_details_limit_per_minute, limit)
                        null -> limit
                    }
                    add(context.getString(R.string.error_details_label_limit) to text)
                }
                details?.quotaMetric?.let { add(context.getString(R.string.error_details_label_metric) to it) }
                details?.quotaWindowStartMs?.let {
                    add(context.getString(R.string.error_details_label_window) to formatDateTime(it, locale, zone))
                }
                resetText?.let { add(context.getString(R.string.error_details_label_resets) to it) }
                if (count > 0) add(context.getString(R.string.error_details_label_failures) to count.toString())
                failedAt?.let {
                    add(
                        context.getString(R.string.error_details_label_last_attempt) to
                            GraphFailureWatermarkRenderer.formatFailureTime(it, nowMs, locale, zone),
                    )
                }
            }

            val headline = "${source.displayName.uppercase(locale)} " +
                context.getString(if (quota != null) R.string.updates_paused else R.string.updates_failing)
            return SourceErrorDetailsContent(
                headline = headline,
                summary = when {
                    quota != null && resetText != null ->
                        GraphFailureWatermarkRenderer.localizedQuotaText(context, quota.scope, resetText)
                    else -> codeText ?: context.getString(R.string.updates_failing)
                },
                explanation = explanation,
                rows = rows,
                rawResponse = details?.rawBody,
                offerApiKeySettings = code in AUTH_CODES,
            )
        }

        private fun formatDateTime(epochMs: Long, locale: Locale, zone: ZoneId): String =
            DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }
}
