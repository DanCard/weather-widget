package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
@Category(Localization::class)
class CloudCoverSourceLabelLocalizationRoboTest {

    @Test
    fun `cloud actuals source label is localized instead of built in shared English`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val label = requireNotNull(
            CloudCoverViewHandler.localizedActualsSourceLabel(context, WeatherSource.SYNOPTIC.displayName),
        )

        assertEquals("Tatsächliche Bewölkungsdaten von Synoptic", label.fullText)
    }
}
