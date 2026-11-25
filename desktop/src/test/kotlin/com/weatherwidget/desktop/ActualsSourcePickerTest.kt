package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.ObservationsTabs
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The Current Observations window's actuals picker greys feeds that cannot serve here instead of offering
 * a choice that leaves the actuals line blank. Android's half: `CurrentObservationsActivityRobolectricTest`.
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

    /** The same call `CurrentObservationsWindow` makes, with this build's baked keys. */
    private fun options(location: Pair<Double, Double>, userKeys: Map<String, String>) =
        ActualsProviderResolver.candidateOptions(
            defaultProvider = ActualsProviderResolver.defaultProviderFor(WeatherSource.GOOGLE_WEATHER, location),
            location = location,
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

    @Test
    fun `switching actuals source skips refresh when rows for the chosen provider are already stored`() {
        val dbPath = Files.createTempFile("actuals-picker-skip-refresh", ".db")
        val db = DesktopWeatherDatabase(dbPath).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        try {
            val now = System.currentTimeMillis()
            dao.upsertObservations(
                listOf(
                    DesktopObservationEntity(
                        stationId = "KNUQ", stationName = "Moffett Field", timestamp = now - 60_000L,
                        temperature = 65f, condition = "Clear", locationLat = mountainView.first,
                        locationLon = mountainView.second, distanceKm = 2f, api = WeatherSource.NWS.id, fetchedAt = now,
                    ),
                    DesktopObservationEntity(
                        stationId = "KPAO", stationName = "Palo Alto", timestamp = now - 60_000L,
                        temperature = 66f, condition = "Clear", locationLat = mountainView.first,
                        locationLon = mountainView.second, distanceKm = 3f, api = WeatherSource.METAR.id, fetchedAt = now,
                    ),
                ),
            )
            DesktopActualsPreference.install()
            var cfg by mutableStateOf(
                DesktopConfig(
                    lat = mountainView.first,
                    lon = mountainView.second,
                    label = "Mountain View",
                    obsSelectedTab = ObservationsTabs.OBSERVATIONS,
                    settings = DesktopSettings(
                        weatherSource = WeatherSource.OPEN_METEO.id,
                        actualsProviders = mapOf(WeatherSource.OPEN_METEO.id to WeatherSource.NWS.id),
                    ),
                ),
            )
            DesktopActualsPreference.publish(cfg)
            var refreshCalls = 0

            composeTestRule.setContent {
                CurrentObservationsContent(
                    weatherDao = dao,
                    config = cfg,
                    onRefreshData = { refreshCalls++ },
                    onClose = {},
                    saves = DataWindowSaves(window = { cfg = it }, sourceSwitch = { cfg = it }),
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 3_000) {
                runCatching { composeTestRule.onNodeWithText("Moffett Field").fetchSemanticsNode(); true }.getOrDefault(false)
            }

            composeTestRule.onNodeWithText("NWS", substring = true).performClick()
            composeTestRule.onNodeWithTag("actuals_candidate_METAR").performClick()

            composeTestRule.waitUntil(timeoutMillis = 3_000) {
                runCatching { composeTestRule.onNodeWithText("Palo Alto").fetchSemanticsNode(); true }.getOrDefault(false)
            }
            assertEquals("METAR rows are already in SQLite; switching must not trigger a network refresh", 0, refreshCalls)
        } finally {
            db.getConnection().close()
            Files.deleteIfExists(dbPath)
        }
    }
}
