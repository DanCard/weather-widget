package com.weatherwidget.desktop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.weatherwidget.shared.contract.DailyRainLabelHostContract
import com.weatherwidget.shared.graph.DailyRainLabelLayout
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

/** The daily graph's Compose TextMeasurer host, measured outside composition. */
@Category(MediumDuration::class)
class DesktopDailyRainLabelHostContractTest : DailyRainLabelHostContract() {
    override fun host(): DailyRainLabelLayout.Host =
        desktopRainLabelHost(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), scale = 1f)
}
