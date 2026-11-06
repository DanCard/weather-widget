package com.weatherwidget.desktop

import androidx.compose.ui.unit.dp
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [fitBlendColumns]: content-sized Blend columns in the window's width (BlendTableViewTest renders them). */
@Category(ShortDuration::class)
class BlendColumnFitTest {
    @Test
    fun `with room to spare every column keeps its content width`() {
        assertEquals(listOf(60.dp, 20.dp, 40.dp), fitBlendColumns(listOf(60.dp, 20.dp, 40.dp), availableWidth = 160.dp))
    }

    @Test
    fun `too narrow, the station column gives way first and gaps stay at the minimum`() {
        val widths = fitBlendColumns(listOf(60.dp, 20.dp, 40.dp), availableWidth = 100.dp)
        println("narrow fit widths=$widths")
        // 100 - 20 - 40 - 2 × 8dp minimum gap.
        assertEquals(listOf(24.dp, 20.dp, 40.dp), widths)
    }

    @Test
    fun `never a negative station width`() {
        assertEquals(0.dp, fitBlendColumns(listOf(60.dp, 200.dp), availableWidth = 100.dp)[0])
    }

    @Test
    fun `the station column never shrinks below its floor`() {
        assertEquals(listOf(30.dp, 20.dp, 40.dp), fitBlendColumns(listOf(60.dp, 20.dp, 40.dp), availableWidth = 70.dp, stationFloor = 30.dp))
        // A floor above the content width never widens the column.
        assertEquals(10.dp, fitBlendColumns(listOf(10.dp, 20.dp), 20.dp, stationFloor = 30.dp)[0])
    }
}
