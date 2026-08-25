package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the forecast-evolution widget labels to Android resources under a non-English locale, so a
 * revert to hardcoded English (the 95c87a92 class) cannot silently ship. Robolectric has no font
 * engine, so this asserts resource resolution rather than rendered text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
@Category(Localization::class)
class ForecastEvolutionLocalizationRoboTest {

    @Test
    fun `forecast evolution labels resolve localized instead of hardcoded English`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("API tatsächlich: 72°", context.getString(R.string.evolution_api_actual_format, "72°"))
        assertEquals("Standort tatsächlich: 72°", context.getString(R.string.evolution_location_actual_format, "72°"))
        assertEquals("Einzelne Höchstprognose", context.getString(R.string.evolution_single_high_title))
        assertEquals("Einzelne Tiefstprognose", context.getString(R.string.evolution_single_low_title))
        assertEquals("Diff. +1,2°", context.getString(R.string.evolution_diff_format, "+1,2°"))
        assertEquals("(3 T)", context.getString(R.string.evolution_days_ahead_format, 3))
    }
}
