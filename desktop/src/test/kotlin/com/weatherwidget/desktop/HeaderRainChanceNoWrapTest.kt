package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.LongDuration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The header's left cluster carries current temp + delta + "from yest" + rain chance. It used to
 * claim exactly a third of the window, and in a tall narrow window — where the height-derived
 * `uiScale` grows the fonts while that third does not grow with them — the cluster overflowed: the
 * rain chance, its last child, was squeezed to a one-glyph column and stacked as "1 / 5 / %".
 *
 * The left cluster is content-sized now, so both guards below must hold:
 *  - the rain chance renders on one line (shape-based: a single line is wider than it is tall, a
 *    per-character stack is far taller than it is wide — true across fonts and densities);
 *  - the "from yest" caption is not truncated, checked by rendering the same header a second time
 *    at double the width and requiring the caption to measure identically. The wide copy has room
 *    to spare, so any narrowing in the crowded copy is truncation.
 */
@Category(LongDuration::class)
class HeaderRainChanceNoWrapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The geometry the wrap was reported at: wide enough to look roomy, tall enough to scale ~2.8x. */
    private val crowdedWidth = 1100.dp
    private val roomyWidth = 2200.dp
    private val windowHeight = 900.dp

    private fun snapshotWithRainChance(percent: Int): ForecastSnapshot {
        val zone = ZoneId.systemDefault()
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // Source-less rows match any display source, so the calculator interpolates a flat
        // `percent` across the whole 8-hour lookahead regardless of the configured provider.
        val hourly = (0..9).map { h ->
            HourlyForecast(
                dateTime = hourStart.plusHours(h.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 64f,
                condition = "Cloudy",
                precipProbability = percent,
            )
        }
        val today = LocalDate.now()
        return ForecastSnapshot(
            raw = RawFetch(
                daily = listOf(
                    DailyForecast(today.toString(), 75f, 55f, "Cloudy", precipProbability = percent),
                    DailyForecast(today.plusDays(1).toString(), 78f, 58f, "Cloudy", precipProbability = percent),
                ),
                hourly = hourly,
            ),
            // 64.0° plus a +2.2 "from yest" delta — the fully-populated left cluster, which is what
            // used to exhaust the third.
            resolved = ResolvedView(currentTemp = 64f, currentCondition = "Cloudy", deltaFromYesterday = 2.2f),
        )
    }

    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        // Keep the temperature text width locale-independent (an "en" JVM default would pick °C).
        settings = DesktopSettings(useCelsius = false),
        viewMode = ViewMode.HOURLY,
    )

    @Test
    fun crowdedHeaderKeepsRainChanceOnOneLineAndCaptionIntact() {
        composeTestRule.setContent {
            Column {
                // Same header content and the same font scale (height-derived), differing only in
                // how much horizontal room the clusters have to divide.
                listOf(crowdedWidth, roomyWidth).forEach { width ->
                    Box(Modifier.requiredSize(width, windowHeight)) {
                        WidgetPopup(
                            config = config,
                            forecast = snapshotWithRainChance(15),
                            dataStatus = DataStatus.Live(System.currentTimeMillis()),
                            resolvedCurrentTemp = 64f,
                            resolvedDeltaFromYesterday = 2.2f,
                            onUpdateLocation = {},
                            onUpdateConfig = {},
                            onOpenSettings = {},
                            onOpenObservations = {},
                        )
                    }
                }
            }
        }

        // Both headers render the same texts, so every lookup is a two-element list: index 0 is
        // the crowded header, index 1 the roomy reference.
        val rainChances = boundsOf("15%")
        val captions = boundsOf("from yest")
        assertEquals("Expected the rain chance in both headers", 2, rainChances.size)
        assertEquals("Expected the caption in both headers", 2, captions.size)

        val crowdedRain = rainChances[0]
        assertTrue(
            "Rain chance wrapped: ${crowdedRain.width} x ${crowdedRain.height} — a single line must " +
                "be wider than it is tall, a per-character stack is not",
            crowdedRain.width > crowdedRain.height,
        )
        assertEquals(
            "\"from yest\" is truncated in the crowded header (${captions[0].width} vs " +
                "${captions[1].width} with room to spare)",
            captions[1].width.value,
            captions[0].width.value,
            0.5f,
        )
    }

    /** Bounds of every node carrying [text], in composition order (crowded header first). */
    private fun boundsOf(text: String): List<DpRect> {
        val nodes = composeTestRule.onAllNodesWithText(text, useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().indices.map { nodes[it].getUnclippedBoundsInRoot() }
    }
}
