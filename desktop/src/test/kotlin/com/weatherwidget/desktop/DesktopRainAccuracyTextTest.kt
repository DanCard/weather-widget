package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.RainAccuracyScenario
import com.weatherwidget.shared.stats.RainAccuracyReport
import com.weatherwidget.shared.stats.RainAccuracySummary
import com.weatherwidget.shared.stats.RainSourceScore
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class DesktopRainAccuracyTextTest {
    @Test
    fun `scenario section stars the closest source and lists sources with too few rain days`() = runBlocking {
        val report = RainAccuracyScenario.run(RainAccuracyScenario.FakeHost())
        val text = DesktopRainAccuracyText.section(report, RainAccuracyScenario.d13, RainAccuracyScenario.today)
        println(text)
        assertTrue(text, text.contains("★ ${RainAccuracyScenario.A.displayName}"))
        assertTrue(text, text.contains("too few rain days (2)"))
    }

    @Test
    fun `ranked lines number from 1 and name the lean`() {
        val day = LocalDate.of(2026, 11, 1)
        val report = RainAccuracyReport(
            scores = listOf(
                RainSourceScore(WeatherSource.GOOGLE_WEATHER, 20, 5, 1.0f, 0.5f, 0.9f),
                RainSourceScore(WeatherSource.NWS, 20, 5, 2.3f, -2.0f, 0.8f),
                RainSourceScore(WeatherSource.SILURIAN, 0, 0, null, null, null),
            ),
            days = emptyList(),
            comparisons = emptyMap(),
            firstDate = day,
            lastDate = day,
        )
        val lines = RainAccuracySummary.scoreLines(report).map(DesktopRainAccuracyText::score)
        lines.forEach(::println)
        assertTrue(lines[0], lines[0].startsWith("1. ${WeatherSource.GOOGLE_WEATHER.displayName}") && "wet +" in lines[0] && "90%" in lines[0])
        assertTrue(lines[1], lines[1].startsWith("2. ${WeatherSource.NWS.displayName}") && "dry −" in lines[1])
        assertTrue(lines[2], lines[2].contains("no scored days yet"))
    }

    @Test
    fun `today reads scored after midnight`() = runBlocking {
        val report = RainAccuracyScenario.run(RainAccuracyScenario.FakeHost())
        val text = DesktopRainAccuracyText.section(report, RainAccuracyScenario.today, RainAccuracyScenario.today)
        assertTrue(text, text.endsWith("Today: scored after midnight"))
    }
}
