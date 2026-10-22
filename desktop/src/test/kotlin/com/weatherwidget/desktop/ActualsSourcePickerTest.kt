package com.weatherwidget.desktop

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The Observations window's actuals picker greys feeds that cannot serve here instead of offering
 * a choice that leaves the actuals line blank. Android's half: `WeatherObservationsActivityRobolectricTest`.
 * See `plans/261021-actuals-source-picker-marks-unusable-feeds.md`.
 */
@Category(LongDuration::class)
class ActualsSourcePickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val kyiv = 50.45 to 30.524
    private val mountainView = 37.417 to -122.089
    private var chosen: WeatherSource? = null

    @After
    fun tearDown() {
        ActualsProviderResolver.resetLocationSource()
        ActualsProviderResolver.resetPreferenceSource()
    }

    /** The same call `ObservationsWindow` makes, with this build's baked keys. */
    private fun options(location: Pair<Double, Double>, userKeys: Map<String, String>) =
        ActualsProviderResolver.candidateOptions(
            defaultProvider = ActualsProviderResolver.defaultProviderFor(WeatherSource.GOOGLE_WEATHER, location),
            location = location,
            synopticConfigured = true,
        ) { !needsKeyFromUser(it, userKeys) }

    private fun render(location: Pair<Double, Double>, userKeys: Map<String, String>) {
        ActualsProviderResolver.installLocationSource { location }
        composeTestRule.setContent {
            ActualsSourceRow(
                source = WeatherSource.GOOGLE_WEATHER,
                active = ActualsProviderResolver.providerIdFor(WeatherSource.GOOGLE_WEATHER),
                options = options(location, userKeys),
                onChoose = { chosen = it },
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `abroad, NWS is listed but disabled and a click chooses nothing`() {
        render(kyiv, userKeys = mapOf(WeatherSource.TOMORROW_IO.id to "k"))
        composeTestRule.onNodeWithText("METAR", substring = true).performClick()
        composeTestRule.onNodeWithTag("actuals_candidate_NWS").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("actuals_candidate_METAR").assertIsEnabled()
        composeTestRule.onNodeWithTag("actuals_candidate_TOMORROW_IO").assertIsEnabled()
        composeTestRule.onNodeWithTag("actuals_candidate_NWS").performClick()
        assertNull(chosen)
    }

    @Test
    fun `at home NWS is enabled and choosing it reports it`() {
        render(mountainView, userKeys = emptyMap())
        composeTestRule.onNodeWithText("NWS", substring = true).performClick()
        composeTestRule.onNodeWithTag("actuals_candidate_NWS").assertIsEnabled().performClick()
        assertEquals(WeatherSource.NWS, chosen)
    }

    /** Key presence comes from the user's keys or this build's baked ones, like Settings. */
    @Test
    fun `a keyed feed is disabled only when neither the user nor the build has a key`() {
        val none = options(mountainView, emptyMap())
            .single { it.source == WeatherSource.WEATHER_API }
        val expected = if (DesktopApiKeys.DEFAULTS[WeatherSource.WEATHER_API.id].isNullOrBlank()) {
            ActualsProviderResolver.Unavailable.NO_KEY
        } else {
            null
        }
        assertEquals(expected, none.unavailable)
        val userKey = options(mountainView, mapOf(WeatherSource.WEATHER_API.id to "k"))
            .single { it.source == WeatherSource.WEATHER_API }
        assertNull(userKey.unavailable)
    }
}
