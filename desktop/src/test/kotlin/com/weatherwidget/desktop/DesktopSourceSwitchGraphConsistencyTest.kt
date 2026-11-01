package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The 2026-11-01 "aw020 66°" flash: config moves on the click, the snapshot when its load returns.
 * The graph asked the old snapshot for the new source's forecast and found none. Without a forecast
 * the blend cannot carry official KNUQ's 2:35 reading forward to 2:40, so personal AW020 was left to
 * dominate until the new snapshot arrived, about 1.2 s later. Everything drawn from a snapshot now
 * uses that snapshot's own source.
 */
@Category(LongDuration::class)
class DesktopSourceSwitchGraphConsistencyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val nws = WeatherSource.NWS

    // Set to borrow NWS's actuals below, as the user's is, so the old snapshot's observations match it.
    private val meteo = WeatherSource.OPEN_METEO
    private val zone = ZoneId.systemDefault()
    private val nowMs = System.currentTimeMillis()

    private fun config(source: WeatherSource, viewMode: ViewMode = ViewMode.TEMPERATURE) = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        viewMode = viewMode,
        settings = DesktopSettings(
            useCelsius = false,
            weatherSource = source.id,
            visibleSources = listOf(nws.id, meteo.id),
        ),
    )

    private fun reading(id: String, type: StationType, km: Float, temp: Float, minutesAgo: Int) = ObservationReading(
        stationId = id,
        stationName = id,
        timestamp = nowMs - minutesAgo * 60_000L,
        temperature = temp,
        condition = "observed",
        locationLat = 37.4220,
        locationLon = -122.0841,
        distanceKm = km,
        stationType = type,
        api = nws.id,
    )

    /**
     * The 2026-11-01 pool: the official station is 5 minutes behind the nearer personal one, and the
     * personal one reports more often, so the lone-station guard lets it stand alone.
     */
    private val stationPool = listOf(
        reading("AW020", StationType.PERSONAL, 2.2f, 66f, minutesAgo = 40),
        reading("KNUQ", StationType.OFFICIAL, 3.8f, 64.4f, minutesAgo = 35),
        reading("AW020", StationType.PERSONAL, 2.2f, 66f, minutesAgo = 30),
        reading("AW020", StationType.PERSONAL, 2.2f, 66f, minutesAgo = 20),
        reading("KNUQ", StationType.OFFICIAL, 3.8f, 64.4f, minutesAgo = 15),
        reading("AW020", StationType.PERSONAL, 2.2f, 66f, minutesAgo = 10),
    )

    @Before
    fun actualsFromTheNwsPool() {
        ActualsProviderResolver.installPreferenceSource { source -> nws.takeIf { source == meteo } }
    }

    @After
    fun resetActualsPreference() {
        ActualsProviderResolver.resetPreferenceSource()
    }

    private fun hourly(source: WeatherSource, rain: Int = 0): List<HourlyForecast> {
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // Far enough ahead that no day in view needs an on-demand fetch of its own.
        return (-12L..72L).map { h ->
            HourlyForecast(
                dateTime = hourStart.plusHours(h).atZone(zone).toInstant().toEpochMilli(),
                temperature = 64f,
                condition = "Clear",
                source = source.id,
                precipProbability = rain,
            )
        }
    }

    private fun snapshot(source: WeatherSource, rain: Int = 0) = ForecastSnapshot(
        sourceId = source.id,
        raw = RawFetch(
            hourly = hourly(source, rain),
            daily = listOf(DailyForecast(LocalDate.now().toString(), 66f, 47f, "Clear", precipProbability = rain, source = source.id)),
            rawObservations = stationPool,
        ),
        resolved = ResolvedView(currentTemp = 64f, currentCondition = "Clear"),
    )

    private fun namedStation(): String? =
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(HourlyDominantStationId), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .config
            .getOrNull(HourlyDominantStationId)

    @Test
    fun `the hourly graph names the same station while the new snapshot loads`() {
        var shownConfig by mutableStateOf(config(nws))
        var forecast by mutableStateOf(snapshot(nws))
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                WidgetPopup(
                    config = shownConfig,
                    forecast = forecast,
                    dataStatus = DataStatus.Live(nowMs),
                    resolvedCurrentTemp = 64f,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("before the switch", "KNUQ", namedStation())

        shownConfig = config(meteo)
        composeTestRule.waitForIdle()
        assertEquals("config moved, snapshot still NWS's", "KNUQ", namedStation())

        forecast = snapshot(meteo)
        composeTestRule.waitForIdle()
        assertEquals("the new source's snapshot arrived", "KNUQ", namedStation())
    }

    @Test
    fun `the pan check waits for the new snapshot instead of fetching on the old one`() {
        var shownConfig by mutableStateOf(config(nws))
        var forecast by mutableStateOf(snapshot(nws))
        val panChecks = mutableListOf<String>()
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                WidgetPopup(
                    config = shownConfig,
                    forecast = forecast,
                    dataStatus = DataStatus.Live(nowMs),
                    resolvedCurrentTemp = 64f,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onHourlyAudit = { tag, message -> if (tag == "HOURLY_PAN_CHECK") panChecks += message },
                )
            }
        }
        composeTestRule.waitForIdle()
        settle()
        panChecks.clear()

        shownConfig = config(meteo)
        composeTestRule.waitForIdle()
        settle()
        assertTrue("no decision from the old source's rows: $panChecks", panChecks.isEmpty())

        // The check was deferred, not dropped: rows that really stop short still fetch.
        val partial = snapshot(meteo).let { full ->
            full.copy(raw = full.raw.copy(hourly = full.raw.hourly.filter { it.dateTime <= nowMs }))
        }
        forecast = partial
        composeTestRule.waitForIdle()
        settle()
        assertTrue("the new source's own rows decide: $panChecks", panChecks.singleOrNull()?.contains("rowsBySource={OPEN_METEO=") == true)
    }

    @Test
    fun `daily columns come from the snapshot's source while the new one loads`() {
        val nwsSnapshot = snapshot(nws, rain = 60)
        val dimensions = DesktopDailyForecastModel.dimensions(900, 300)
        val now = LocalDateTime.now()

        val settled = DesktopDailyForecastModel.build(config(nws, ViewMode.DAILY), nwsSnapshot, dimensions, now, currentTemp = 64f)
        val duringSwitch = DesktopDailyForecastModel.build(config(meteo, ViewMode.DAILY), nwsSnapshot, dimensions, now, currentTemp = 64f)

        assertEquals(settled.days, duringSwitch.days)
    }

    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(HourlyOnDemand.PAN_SETTLE_MS + 200)
        composeTestRule.waitForIdle()
    }
}
