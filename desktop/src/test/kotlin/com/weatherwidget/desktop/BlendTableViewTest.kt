package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import com.weatherwidget.desktop.theme.WeatherTypography
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.actuals.BlendTable
import com.weatherwidget.shared.actuals.BlendTableRow
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Blend tab columns are sized to their content (2026-11-06): fixed weights left a hole after the
 * one-letter type, clipped "45m" in the age column and wrapped the "type" header at the 500dp default.
 */
@Category(LongDuration::class)
class BlendTableViewTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(station: String, type: String, km: String, age: String, value: String, share: String) =
        BlendTableRow(
            station = station, type = type, km = km, lastRead = "07:50", age = age, raw = "47.0",
            valueFedToBlend = value, weightShare = share, isExtrapolated = value.endsWith("E"),
            stationType = if (type == "O") StationType.OFFICIAL else StationType.PERSONAL,
        )

    // The 2026-11-06 screenshot's rows.
    private val table = BlendTable(
        timeLabel = "08:35", blendedLabel = "56.23°", stationCount = 5,
        rows = listOf(
            row("AW020", "P", "2.21", "45m", "50.16 E", "7.6%"),
            row("KNUQ", "O", "3.80", "0m", "57.20 R", "68.4%"),
            row("KPAO", "O", "6.08", "48m", "55.22 E", "19.6%"),
            row("LOAC1", "F", "8.31", "25m", "57.67 E", "0.6%"),
            row("KSJC", "O", "15.89", "5m", "55.73 E", "3.8%"),
        ),
    )

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun `at the user's 690dp window no cell is clipped and the type header stays on one line`() {
        // 2026-11-06 screenshot: the Stations window was 690 px at density 1.
        compose.setContent {
            MaterialTheme(typography = WeatherTypography) {
                Box(Modifier.width(690.dp)) { BlendTableView(listOf(table)) }
            }
        }
        for (h in com.weatherwidget.shared.actuals.BlendTableFormatter.COLUMN_HEADERS) {
            val b = compose.onNodeWithText(h).fetchSemanticsNode().boundsInRoot
            println("header '$h' left=${b.left} width=${b.width} density=${compose.density.density}")
        }
        val texts = table.rows.flatMap { r ->
            listOf(r.station, r.km, r.lastRead, r.age, r.raw, r.valueFedToBlend, r.weightShare)
        }.distinct()
        for (text in texts) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { i, _ ->
                val layout = compose.onAllNodesWithText(text, useUnmergedTree = true)[i].layout()
                println("cell '$text' width=${layout.size.width} lineRight=${layout.getLineRight(0)} multi=${layout.multiParagraph.width} overflow=${layout.hasVisualOverflow}")
                assertFalse("'$text' is clipped", layout.hasVisualOverflow)
            }
        }
        val typeHeader = compose.onNodeWithText("type").layout()
        println("type header lines=${typeHeader.lineCount}")
        assertEquals("the type header wraps", 1, typeHeader.lineCount)
        val semantics = compose.onNodeWithText("type").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        println("type header semantics=$semantics")
    }

    @Test
    fun `at the 500dp default the station names stay readable at the header's width`() {
        compose.setContent {
            MaterialTheme(typography = WeatherTypography) {
                Box(Modifier.width(500.dp)) { BlendTableView(listOf(table)) }
            }
        }
        val station = compose.onNodeWithText("station").fetchSemanticsNode().boundsInRoot
        val cell = compose.onNodeWithText("KSJC", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        println("500dp: station header width=${station.width} KSJC cell width=${cell.width}")
        assertEquals(station.width, cell.width, 0.5f)
        org.junit.Assert.assertTrue("station column collapsed", cell.width > 40f)
    }
}
