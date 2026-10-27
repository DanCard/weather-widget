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
import com.weatherwidget.data.remote.ProviderErrorDetails
import com.weatherwidget.data.remote.QuotaState
import com.weatherwidget.data.remote.SourceErrorDetails
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.GraphFailureWatermarkRenderer
import com.weatherwidget.widget.WidgetStateManager
import java.time.ZoneId
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
        val viewed = intent.getStringExtra(EXTRA_PRODUCT)?.let { name -> ForecastProduct.entries.firstOrNull { it.name == name } }
        val lastSuccessMs = ActiveLocationResolver.current(this)?.let { (lat, lon) ->
            FetchMetadata.getLastForecastSourceSuccessTime(this, source.id, lat, lon).takeIf { it > 0L }
        }
        val content = SourceErrorDetailsContent.build(this, source, WidgetStateManager(this), viewed = viewed, lastSuccessMs = lastSuccessMs)

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
        const val EXTRA_PRODUCT = "product"

        /** [product]: the tapped view's forecast product, so the page names that view's quota block. */
        fun intent(context: Context, sourceId: String?, product: ForecastProduct? = null): Intent =
            Intent(context, SourceErrorDetailsActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                .putExtra(EXTRA_PRODUCT, product?.name)
                // CLEAR_TASK: the page is built once in onCreate; NEW_TASK alone would bring an
                // earlier source's still-alive page to the front unchanged.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/**
 * Everything the details page shows, assembled without views so it can be tested directly. The rule —
 * which failure, which rows — is `:shared` [SourceErrorDetails] (desktop's details card uses it too);
 * this turns its values into Android's localized text.
 */
internal data class SourceErrorDetailsContent(
    val headline: String,
    val summary: String,
    val explanation: String,
    val rows: List<Pair<String, String>>,
    val rawResponse: String?,
    val offerApiKeySettings: Boolean,
) {
    companion object {
        /** Null when nothing is failing for [source] (the pill is gone, or was tapped as it cleared). */
        fun build(
            context: Context,
            source: WeatherSource,
            state: WidgetStateManager,
            nowMs: Long = System.currentTimeMillis(),
            locale: Locale = Locale.getDefault(),
            zone: ZoneId = ZoneId.systemDefault(),
            /** The view whose pill was tapped: its own product's quota block is the one described. */
            viewed: ForecastProduct? = null,
            lastSuccessMs: Long? = null,
        ): SourceErrorDetailsContent? {
            val facts = SourceErrorDetails.Facts(
                failureCount = state.getSourceFailureCount(source),
                lastErrorCode = state.getSourceLastErrorCode(source),
                lastFailureMs = state.getSourceLastFailureTime(source),
                lastFailureDetail = state.getSourceLastFailureDetail(source),
                productQuota = { product ->
                    state.getProductQuota(source, product, nowMs)?.let { QuotaState.ProductQuota(it.untilMs, it.sinceMs, it.detail) }
                },
                rateLimit = state.getRateLimit(source, nowMs)?.let { SourceErrorDetails.RateLimit(it.atMs, it.partial, it.detail) },
                lastSuccessMs = lastSuccessMs,
            )
            val content = SourceErrorDetails.build(facts, viewed, nowMs) ?: return null
            fun reset(ms: Long) = GraphFailureWatermarkRenderer.formatResetTime(ms, locale, zone)
            return SourceErrorDetailsContent(
                headline = "${source.displayName.uppercase(locale)} " +
                    context.getString(
                        when (content.headline) {
                            SourceErrorDetails.Headline.PAUSED -> R.string.updates_paused
                            SourceErrorDetails.Headline.RATE_LIMITED -> R.string.updates_rate_limited
                            SourceErrorDetails.Headline.FAILING -> R.string.updates_failing
                        },
                    ),
                summary = when (val summary = content.summary) {
                    is SourceErrorDetails.Summary.Quota ->
                        GraphFailureWatermarkRenderer.localizedQuotaText(context, summary.scope, reset(summary.resetAtMs))
                    is SourceErrorDetails.Summary.Code -> GraphFailureWatermarkRenderer.localizedErrorCodeText(context, summary.code)
                    SourceErrorDetails.Summary.Failing -> context.getString(R.string.updates_failing)
                },
                explanation = when (content.explanation) {
                    SourceErrorDetails.Explanation.QUOTA ->
                        context.getString(R.string.error_details_explain_quota, reset(content.resetAtMs ?: 0L))
                    SourceErrorDetails.Explanation.RATE_LIMIT -> context.getString(R.string.error_details_explain_rate_limit)
                    SourceErrorDetails.Explanation.AUTH -> context.getString(R.string.error_details_explain_auth)
                    SourceErrorDetails.Explanation.DATA_RESTRICTED -> context.getString(R.string.error_details_explain_data)
                    SourceErrorDetails.Explanation.GENERIC -> context.getString(R.string.error_details_explain_generic)
                },
                rows = content.rows.map { row -> context.getString(labelRes(row.label)) to valueText(context, row.value, nowMs, locale, zone) },
                rawResponse = content.rawResponse,
                offerApiKeySettings = content.offerApiKeySettings,
            )
        }

        internal fun labelRes(label: SourceErrorDetails.Label): Int = when (label) {
            SourceErrorDetails.Label.ERROR -> R.string.error_details_label_error
            SourceErrorDetails.Label.REQUEST -> R.string.error_details_label_request
            SourceErrorDetails.Label.STATUS -> R.string.error_details_label_status
            SourceErrorDetails.Label.MESSAGE -> R.string.error_details_label_message
            SourceErrorDetails.Label.QUOTA -> R.string.error_details_label_quota
            SourceErrorDetails.Label.LIMIT -> R.string.error_details_label_limit
            SourceErrorDetails.Label.METRIC -> R.string.error_details_label_metric
            SourceErrorDetails.Label.WINDOW -> R.string.error_details_label_window
            SourceErrorDetails.Label.RESETS -> R.string.error_details_label_resets
            SourceErrorDetails.Label.FAILURES -> R.string.error_details_label_failures
            SourceErrorDetails.Label.LAST_ATTEMPT -> R.string.error_details_label_last_attempt
            SourceErrorDetails.Label.LAST_SUCCESS -> R.string.error_details_label_last_success
        }

        private fun valueText(context: Context, value: SourceErrorDetails.Value, nowMs: Long, locale: Locale, zone: ZoneId): String =
            when (value) {
                is SourceErrorDetails.Value.Plain -> value.text
                is SourceErrorDetails.Value.ErrorCode -> GraphFailureWatermarkRenderer.localizedErrorCodeText(context, value.code)
                is SourceErrorDetails.Value.Limit -> when (value.period) {
                    ProviderErrorDetails.QuotaPeriod.DAY -> context.getString(R.string.error_details_limit_per_day, value.limit)
                    ProviderErrorDetails.QuotaPeriod.MINUTE -> context.getString(R.string.error_details_limit_per_minute, value.limit)
                    null -> value.limit
                }
                is SourceErrorDetails.Value.DateTime -> SourceErrorDetails.formatDateTime(value.epochMs, locale, zone)
                is SourceErrorDetails.Value.ResetTime -> GraphFailureWatermarkRenderer.formatResetTime(value.epochMs, locale, zone)
                is SourceErrorDetails.Value.Moment -> GraphFailureWatermarkRenderer.formatFailureTime(value.epochMs, nowMs, locale, zone)
            }
    }
}
