package com.weatherwidget.ui

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.util.RefreshOutcome
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The History Refresh message names a refused part, and Android's English strings say exactly what
 * desktop's [RefreshOutcome.text] says (plans/261016-history-refresh-outcome-shared-desktop-parity.md).
 */
@Category(LongDuration::class)
class ForecastHistoryRefreshTextTest : RobolectricTest() {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val outcomes = listOf(
        RefreshOutcome.Failed,
        RefreshOutcome.Refreshed,
        RefreshOutcome.Refused(QuotaScope.HOURLY_FORECAST, 1_792_220_400_000L),
        RefreshOutcome.Refused(QuotaScope.DAILY_FORECAST, 1_792_220_400_000L),
        RefreshOutcome.Refused(QuotaScope.SOURCE, 1_792_220_400_000L),
        RefreshOutcome.HourlyIncomplete,
    )

    @Test
    fun `android text matches the shared English text for every outcome`() {
        val androidTime = { ms: Long -> android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms)) }
        for (outcome in outcomes) {
            assertEquals(
                outcome.toString(),
                RefreshOutcome.text(outcome, "Google", androidTime),
                ForecastHistoryActivity.refreshOutcomeText(context, outcome, "Google"),
            )
        }
    }

    @Test
    fun `hourly quota names the hourly forecast and its reset`() {
        val text = ForecastHistoryActivity.refreshOutcomeText(context, outcomes[2], "Google")
        assertEquals(true, text.startsWith("Refreshed Google · Hourly forecast quota used · resets "))
    }
}
