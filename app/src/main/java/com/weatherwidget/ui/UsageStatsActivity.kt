package com.weatherwidget.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.ApiUsageSummary
import com.weatherwidget.data.remote.SourceUsage
import com.weatherwidget.data.remote.UsageCounts
import com.weatherwidget.shared.util.NetworkUsageReport
import com.weatherwidget.util.NetworkUsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings → Data Usage → "Usage stats…": this device's network data and its API requests per
 * source and endpoint (Today · This month · Last month · 90 days). The desktop's
 * `UsageStatsWindowHost` shows the same summary ([ApiUsageSummary]).
 */
class UsageStatsActivity : AppCompatActivity() {

    private val countFormat: NumberFormat by lazy { NumberFormat.getIntegerInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_stats)
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.usage_stats_footer).text =
            getString(R.string.usage_stats_footer_format, RetentionPolicy.USAGE_DAYS.toInt())
        findViewById<LinearLayout>(R.id.usage_stats_api).addView(mutedText(getString(R.string.usage_stats_loading)))
        findViewById<LinearLayout>(R.id.usage_stats_network).addView(mutedText(getString(R.string.data_usage_loading)))

        val dao = WeatherDatabase.getDatabase(this).apiUsageDao()
        lifecycleScope.launch {
            val (summary, network) = withContext(Dispatchers.IO) {
                val since = LocalDate.now().minusDays(RetentionPolicy.USAGE_DAYS).toEpochDay() * 86_400_000L
                val rows = dao.getSince(since).map { it.toRow() }
                ApiUsageSummary.summarize(rows, Instant.now(), ZoneId.systemDefault()) to
                    NetworkUsageTracker.queryNetworkUsage(this@UsageStatsActivity)
            }
            bindNetwork(network)
            bindApiUsage(summary)
        }
    }

    private fun bindNetwork(report: NetworkUsageReport?) {
        val container = findViewById<LinearLayout>(R.id.usage_stats_network)
        container.removeAllViews()
        if (report == null) {
            container.addView(mutedText(getString(R.string.data_usage_unavailable)))
            return
        }
        listOf(
            R.string.data_usage_past_24h to report.past24Hours,
            R.string.data_usage_past_7d to report.past7Days,
            R.string.data_usage_past_30d to report.past30Days,
            R.string.data_usage_past_90d to report.past90Days,
        ).forEachIndexed { i, (title, window) ->
            container.addView(text(getString(title), primary = true, bold = true, sizeSp = 15f).apply {
                if (i > 0) setPadding(0, dp(10), 0, 0)
            })
            container.addView(mutedText(networkLine(getString(R.string.cellular_label), window.cellular)))
            container.addView(mutedText(networkLine(getString(R.string.wifi_label), window.wifi)))
        }
    }

    private fun networkLine(label: String, bytes: com.weatherwidget.shared.util.TrafficBucket): String =
        getString(
            R.string.data_usage_entry_format,
            label,
            NetworkUsageTracker.formatBytes(bytes.totalBytes),
            NetworkUsageTracker.formatBytes(bytes.foregroundBytes),
            NetworkUsageTracker.formatBytes(bytes.backgroundBytes),
        )

    private fun bindApiUsage(summary: List<SourceUsage>) {
        val container = findViewById<LinearLayout>(R.id.usage_stats_api)
        container.removeAllViews()
        if (summary.isEmpty()) {
            container.addView(mutedText(getString(R.string.usage_stats_empty)))
            return
        }
        container.addView(
            row(
                "",
                listOf(
                    getString(R.string.usage_stats_col_today),
                    getString(R.string.usage_stats_col_this_month),
                    getString(R.string.usage_stats_col_last_month),
                    getString(R.string.usage_stats_col_90_days),
                ),
                style = RowStyle.HEADER,
            ),
        )
        summary.forEach { source ->
            container.addView(
                row(source.displayName, source.counts.columns(), style = RowStyle.SOURCE).apply {
                    tag = "usage_source_${source.sourceId}"
                    setPadding(0, dp(10), 0, 0)
                },
            )
            if (source.errors > 0 || source.quotaRefused > 0) {
                container.addView(
                    mutedText(
                        getString(
                            R.string.usage_stats_errors_format,
                            countFormat.format(source.errors),
                            countFormat.format(source.quotaRefused),
                        ),
                    ).apply { setPadding(dp(12), 0, 0, 0) },
                )
            }
            source.endpoints.forEach { endpoint ->
                container.addView(
                    row(
                        endpoint.endpoint.ifEmpty { getString(R.string.usage_stats_not_broken_down) },
                        endpoint.counts.columns(),
                        style = RowStyle.ENDPOINT,
                    ),
                )
            }
        }
        findViewById<View>(R.id.usage_stats_pacific_note).visibility =
            if (summary.any { it.pacificDays }) View.VISIBLE else View.GONE
    }

    private fun UsageCounts.columns() = listOf(today, thisMonth, lastMonth, last90Days).map { countFormat.format(it) }

    private enum class RowStyle { HEADER, SOURCE, ENDPOINT }

    private fun row(label: String, values: List<String>, style: RowStyle): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val primary = style == RowStyle.SOURCE
            val bold = style == RowStyle.SOURCE
            val sizeSp = if (style == RowStyle.ENDPOINT) 12f else 13f
            addView(
                text(label, primary = primary, bold = bold, sizeSp = sizeSp).apply {
                    if (style == RowStyle.ENDPOINT) setPadding(dp(12), dp(2), 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            values.forEach { value ->
                addView(
                    text(value, primary = primary, bold = bold, sizeSp = sizeSp).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(VALUE_COLUMN_DP), LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
        }

    private fun mutedText(value: String) = text(value, primary = false, bold = false)

    private fun text(value: String, primary: Boolean, bold: Boolean, sizeSp: Float = 13f) =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(
                ContextCompat.getColor(
                    this@UsageStatsActivity,
                    if (primary) R.color.widget_text_primary else R.color.widget_text_secondary,
                ),
            )
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        /** Four count columns beside a wrapping label; "Last month" in the widest locales fits two lines. */
        const val VALUE_COLUMN_DP = 60
    }
}
