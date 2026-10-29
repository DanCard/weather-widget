package com.weatherwidget.desktop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The real font's blank line-box space that DailyBottomStack reclaims (glyph-outline bounds). */
@Category(MediumDuration::class)
class DesktopTextInkTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    @Test
    fun `low digits leave the descent blank, day capitals leave room above`() {
        val low = measurer.measure("00°", TextStyle(fontSize = 38.sp))
        val day = measurer.measure("Wed", TextStyle(fontSize = 30.sp))
        val below = low.blankBelowInk()
        val above = day.blankAboveInk()
        assertTrue("below=$below of ${low.size.height}", below > 0f && below < low.size.height / 2f)
        assertTrue("above=$above of ${day.size.height}", above > 0f && above < day.size.height / 2f)
    }
}
