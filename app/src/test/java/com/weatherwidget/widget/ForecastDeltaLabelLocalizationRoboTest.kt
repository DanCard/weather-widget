package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the "+1.2 from forecast" delta suffix to Android resources under a non-English locale.
 * The English " from forecast" default remains in :shared for the desktop app (no localization
 * layer); Android supplies the localized suffix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
@Category(Localization::class)
class ForecastDeltaLabelLocalizationRoboTest {

    @Test
    fun `forecast delta suffix resolves localized`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = " " + context.getString(R.string.forecast_delta_suffix)

        assertEquals("gegenüber der Prognose", context.getString(R.string.forecast_delta_suffix))
        assertEquals("+1.2 gegenüber der Prognose", ForecastDeltaLabel.format(1.2f, false, suffix))
    }
}
