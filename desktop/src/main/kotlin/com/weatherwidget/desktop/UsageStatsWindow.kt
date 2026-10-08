package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ApiUsageSummary
import com.weatherwidget.data.remote.SourceUsage
import com.weatherwidget.data.remote.UsageCounts
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.shared.util.NetworkUsageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings → Data Usage → "Usage stats…": this machine's network data and its API requests per
 * source and endpoint (Today · This month · Last month · 90 days). Android's `UsageStatsActivity`
 * shows the same summary ([ApiUsageSummary]).
 */
@Composable
internal fun UsageStatsWindowHost(
    icon: Painter,
    weatherDao: DesktopWeatherDao,
    showRequestId: Int = 0,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 560.dp,
        height = 720.dp,
    )
    var apiUsage by remember { mutableStateOf<List<SourceUsage>?>(null) }
    var network by remember { mutableStateOf<NetworkUsageReport?>(null) }
    LaunchedEffect(showRequestId) {
        withContext(Dispatchers.IO) {
            val since = LocalDate.now().minusDays(RetentionPolicy.USAGE_DAYS).toEpochDay() * 86_400_000L
            apiUsage = ApiUsageSummary.summarize(weatherDao.apiUsageSince(since), Instant.now(), ZoneId.systemDefault())
            network = weatherDao.queryNetworkUsageReport()
        }
    }
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Usage Stats",
        icon = icon,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
    ) {
        BringToFrontOnShow(window = window, windowState = windowState, showRequestId = showRequestId)
        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                UsageStatsContent(apiUsage = apiUsage, network = network)
            }
        }
    }
}

/** The window's body; [apiUsage] null = still loading. */
@Composable
internal fun UsageStatsContent(apiUsage: List<SourceUsage>?, network: NetworkUsageReport?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Network data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        DataUsageSectionContent(report = network, isLoading = apiUsage == null)

        Spacer(Modifier.height(24.dp))
        Text("API calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            apiUsage == null -> MutedText("Loading…")
            apiUsage.isEmpty() -> MutedText("No API calls recorded yet.")
            else -> {
                UsageRow("", UsageColumnTitles, header = true)
                apiUsage.forEachIndexed { i, source ->
                    if (i > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        )
                    }
                    SourceUsageBlock(source)
                }
                Spacer(Modifier.height(12.dp))
                if (apiUsage.any { it.pacificDays }) {
                    MutedText("Google's days and months run on Pacific time, as in its Cloud Console.")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MutedText(
            "Counts every request this computer sent, including retries and failures. " +
                "Kept ${RetentionPolicy.USAGE_DAYS} days.",
        )
    }
}

private val UsageColumnTitles = listOf("Today", "This month", "Last month", "90 days")

@Composable
private fun SourceUsageBlock(source: SourceUsage) {
    Column(modifier = Modifier.fillMaxWidth().testTag("usage_source_${source.sourceId}")) {
        UsageRow(source.displayName, source.counts.columns(), bold = true)
        if (source.errors > 0 || source.quotaRefused > 0) {
            MutedText(
                "Errors: ${formatCount(source.errors)} · Quota refusals (429): ${formatCount(source.quotaRefused)}",
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        source.endpoints.forEach { endpoint ->
            UsageRow(
                label = endpoint.endpoint.ifEmpty { "Earlier (not broken down)" },
                values = endpoint.counts.columns(),
                indent = true,
            )
        }
    }
}

private fun UsageCounts.columns() = listOf(today, thisMonth, lastMonth, last90Days).map(::formatCount)

private fun formatCount(n: Int): String = "%,d".format(n)

@Composable
private fun UsageRow(
    label: String,
    values: List<String>,
    header: Boolean = false,
    bold: Boolean = false,
    indent: Boolean = false,
) {
    val style = if (bold || header) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
    val color = if (indent || header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = style, color = color, fontWeight = weight, modifier = Modifier.weight(1f))
        values.forEach { value ->
            Text(
                value,
                style = style,
                color = color,
                fontWeight = weight,
                textAlign = TextAlign.End,
                modifier = Modifier.width(78.dp),
            )
        }
    }
}

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun DataUsageSectionContent(
    report: com.weatherwidget.shared.util.NetworkUsageReport?,
    isLoading: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Network data used for forecast and observation updates.",
            style = WeatherTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Text(
                text = "Loading data usage…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val r = report ?: com.weatherwidget.shared.util.NetworkUsageReport(
                past24Hours = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past7Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past30Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past90Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
            )

            DataUsageWindowBlock(
                title = "Past 24 Hours",
                window = r.past24Hours,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 7 Days (Week)",
                window = r.past7Days,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 30 Days (Month)",
                window = r.past30Days,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 90 Days",
                window = r.past90Days,
            )
        }
    }
}

@Composable
private fun DataUsageWindowBlock(
    title: String,
    window: com.weatherwidget.shared.util.NetworkUsageWindow,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        val cellTotal = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.totalBytes)
        val cellFg = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.foregroundBytes)
        val cellBg = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.backgroundBytes)
        Text(
            text = "Cellular: $cellTotal (FG: $cellFg • BG: $cellBg)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(1.dp))
        val wifiTotal = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.totalBytes)
        val wifiFg = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.foregroundBytes)
        val wifiBg = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.backgroundBytes)
        Text(
            text = "Wi-Fi: $wifiTotal (FG: $wifiFg • BG: $wifiBg)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
