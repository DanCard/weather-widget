package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.shared.parity.ForecastDeltaIncident
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ViewMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * One forecast delta in every view, through the real path: SQLite rows, the stitched read,
 * `loadCached`, then the popup rendered in the daily view and the temperature graph. Each view is
 * held to the literal delta, not merely to the other view, so two wrong views cannot pass
 * (plans/261030-one-forecast-delta-all-views.md).
 */
@Category(LongDuration::class)
class DesktopForecastDeltaViewsIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Tomorrow keeps the fixture's now ahead of the wall clock at any hour, so a read that ignores
    // the passed-in now fails every run, not just the ones before 10:48.
    private val incident = ForecastDeltaIncident(day = LocalDate.now().plusDays(1))
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var snapshot: ForecastSnapshot

    private val daily = DesktopConfig(
        lat = ForecastDeltaIncident.LAT,
        lon = ForecastDeltaIncident.LON,
        label = "Mountain View",
        settings = DesktopSettings(
            useCelsius = false,
            weatherSource = ForecastDeltaIncident.SOURCE.id,
            todayOverlayDelta = true,
        ),
    )

    @Before
    fun setup() = runBlocking {
        tempDbPath = Files.createTempFile("forecast-delta-views", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        val lat = ForecastDeltaIncident.LAT
        val lon = ForecastDeltaIncident.LON
        val source = ForecastDeltaIncident.SOURCE.id
        dao.upsertHourlyForecasts(lat, lon, source, incident.lateRun)
        dao.upsertHourlyForecastHistory(lat, lon, source, incident.ms(5, 3), incident.earlyRun)
        dao.upsertHourlyForecastHistory(lat, lon, source, incident.ms(10, 24), incident.lateRun)
        dao.upsertObservations(
            incident.observations.map {
                DesktopObservationEntity(
                    stationId = it.stationId,
                    stationName = it.stationName,
                    timestamp = it.timestamp,
                    temperature = it.temperature,
                    condition = it.condition,
                    locationLat = it.locationLat,
                    locationLon = it.locationLon,
                    distanceKm = it.distanceKm,
                    stationType = it.stationType,
                    fetchedAt = it.fetchedAt,
                    api = it.api,
                )
            },
        )
        val service = DesktopWeatherService(lat, lon, source)
        val repository = DesktopWeatherRepository(service, dao, lat, lon, source)
        snapshot = requireNotNull(repository.loadCached(incident.nowMs)) { "no cached snapshot" }
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `the daily view and the temperature graph print the same resolved delta`() {
        assertEquals(ForecastDeltaIncident.DELTA, snapshot.resolved.appliedDelta!!, 0.01f)

        var config by mutableStateOf(daily)
        var graphLabel: ForecastDeltaLabel.Outcome? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = config,
                forecast = snapshot,
                dataStatus = DataStatus.Live(incident.nowMs),
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {},
                onOpenObservations = {},
                nowOverrideMs = incident.nowMs,
                onForecastDeltaPlaced = { graphLabel = it },
            )
        }

        composeTestRule.onNode(
            SemanticsMatcher.expectValue(TodayColumnForecastDelta, ForecastDeltaIncident.DELTA_TEXT),
        ).assertExists()

        config = daily.copy(viewMode = ViewMode.TEMPERATURE)
        composeTestRule.waitForIdle()

        val label = requireNotNull(graphLabel) { "the graph must report its delta label" }
        assertEquals("label must be drawn: $label", "drawn", label.reason)
        assertEquals(ForecastDeltaIncident.DELTA_TEXT, label.valueText)
        assertEquals(
            "the ghost line carries the same delta",
            ForecastDeltaIncident.GHOST_AT_11,
            label.expectedTempsByHour.getValue(incident.ms(11)),
            0.01f,
        )

        // The label is the resolver's number, so panning the window cannot change it.
        config = config.copy(hourlyOffset = -2)
        composeTestRule.waitForIdle()
        assertEquals(ForecastDeltaIncident.DELTA_TEXT, graphLabel?.valueText)
    }
}
