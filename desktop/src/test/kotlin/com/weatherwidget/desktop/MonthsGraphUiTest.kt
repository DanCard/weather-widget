package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.weatherwidget.shared.months.MonthsDayValue
import com.weatherwidget.shared.months.MonthsSpan
import com.weatherwidget.shared.months.MonthsViewport
import com.weatherwidget.shared.months.MonthsZoomAction
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The desktop months canvas: wheel, drag and click reach the shared zoom rules. */
@Category(LongDuration::class)
@OptIn(ExperimentalTestApi::class)
class MonthsGraphUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.parse("2026-10-15")
    private val values = (0L until 200L).map { n ->
        MonthsDayValue(today.minusDays(n), 60f + n % 9, 40f + n % 5, MonthsDayValue.Kind.ACTUAL)
    }
    private val state = DesktopMonthsState(
        span = MonthsSpan.TWO,
        window = MonthsViewport.window(MonthsSpan.TWO, today),
        today = today,
        values = values.sortedBy { it.date },
        bounds = today.minusDays(199)..today,
        normals = emptyMap(),
    )

    private val actions = mutableListOf<MonthsZoomAction>()
    private val pans = mutableListOf<Int>()

    private val switched = mutableListOf<String>()

    private fun show(shown: DesktopMonthsState = state) {
        composeTestRule.setContent {
            Box(Modifier.size(600.dp, 300.dp)) {
                MonthsGraph(
                    state = shown,
                    useCelsius = false,
                    scale = 1f,
                    onAction = { actions += it },
                    onPanDays = { pans += it },
                    modifier = Modifier.size(600.dp, 300.dp),
                    onSwitchSource = { switched += it },
                )
            }
        }
    }

    @Test
    fun `click opens the daily view on the day under the pointer`() {
        show()
        composeTestRule.onNodeWithTag("months_graph").assertIsDisplayed().performTouchInput { click(center) }
        composeTestRule.waitForIdle()
        val open = actions.single() as MonthsZoomAction.OpenDaily
        val middle = state.window.start.plusDays((state.window.dayCount / 2).toLong())
        assertTrue("clicked ${open.date}, middle $middle", kotlin.math.abs(ChronoUnit.DAYS.between(middle, open.date)) <= 2)
    }

    @Test
    fun `wheel steps the span`() {
        show()
        composeTestRule.onNodeWithTag("months_graph").performMouseInput { scroll(1f) }
        composeTestRule.waitForIdle()
        assertEquals(MonthsZoomAction.SetSpan(MonthsSpan.SIX), actions.first())
    }

    @Test
    fun `dragging right pans to earlier days`() {
        show()
        composeTestRule.onNodeWithTag("months_graph").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertTrue("pans=$pans", pans.isNotEmpty() && pans.all { it < 0 })
        assertTrue("a drag is not a click", actions.none { it is MonthsZoomAction.OpenDaily })
    }

    @Test
    fun `the more-history line switches to that source`() {
        show(state.copy(moreHistory = com.weatherwidget.shared.months.SourceDepth("NWS", today.minusDays(135), 135)))
        composeTestRule.onNodeWithTag("months_more_history").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("NWS"), switched)
        assertTrue("the line is not a day tap", actions.none { it is MonthsZoomAction.OpenDaily })
    }

    @Test
    fun `no line when no other source is deeper`() {
        show()
        composeTestRule.onNodeWithTag("months_more_history").assertDoesNotExist()
    }
}
