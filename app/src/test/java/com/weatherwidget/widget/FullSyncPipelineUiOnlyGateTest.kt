package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.test.category.MediumDuration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `uiOnlyRefresh` means "repaint from cache, touch nothing" — and it is enforced by **eleven
 * separate `!input.uiOnlyRefresh` conditions** spread through one 385-line `run()`. Nothing tested
 * the class at all.
 *
 * That shape fails in one specific way, and it did: on 2026-08-23 a METAR fetch was added *outside*
 * the guards, so a `uiOnly=true` run issued a live network request — the exact battery cost the flag
 * exists to prevent. It was caught by reading device logs, not by a test.
 *
 * A pure-function policy test would not have caught it. The defect was not a wrong condition, it was
 * a call site that never consulted one, so the assertion has to be on the *outcome* of a real run.
 *
 * **Both directions are asserted deliberately.** "No side effects occurred" passes vacuously against
 * a pipeline that is broken and does nothing, so every absence below is paired with the presence
 * case in [full sync performs the network work that ui-only skips].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class FullSyncPipelineUiOnlyGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var weatherRepository: WeatherRepository
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var appLogDao: AppLogDao
    private lateinit var gpsResampler: GpsResampler
    private lateinit var hourlyForecastLoader: HourlyForecastLoader
    private lateinit var dataBundleLoader: WidgetDataBundleLoader
    private lateinit var painter: WidgetPaintCoordinator
    private lateinit var metarRefresher: MetarObservationRefresher
    private lateinit var synopticRefresher: SynopticObservationRefresher

    private val lat = 37.4220
    private val lon = -122.0841

    /** Wide enough to absorb the Float round-trip through SharedPreferences, tight enough to pin a site. */
    private val COORD_TOLERANCE = 1e-4

    @Before
    fun setUp() {
        // resolve() returns the persisted canonical location before consulting anything else, so the
        // pipeline gets past its no-location gate without mocking a single static.
        ActiveLocationResolver.persist(context, lat, lon)

        weatherRepository = mockk(relaxed = true)
        widgetStateManager = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        gpsResampler = mockk(relaxed = true)
        hourlyForecastLoader = mockk(relaxed = true)
        dataBundleLoader = mockk(relaxed = true)
        painter = mockk(relaxed = true)
        metarRefresher = mockk(relaxed = true)
        synopticRefresher = mockk(relaxed = true)

        // A successful fetch carrying an NWS row: the NWS backfill branch keys on the fetched
        // sources when targetSourceId is null, and asserting it never runs under uiOnly is only
        // meaningful if it WOULD otherwise have run.
        coEvery {
            weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any())
        } returns Result.success(listOf(forecastRow()))
        every { hourlyForecastLoader.currentDisplaySourceIds() } returns listOf(WeatherSource.NWS.id)
        every { hourlyForecastLoader.hourlySourceIds() } returns listOf(WeatherSource.NWS.id)
        coEvery { hourlyForecastLoader.load(any(), any(), any()) } returns emptyList<HourlyForecastEntity>()
        coEvery { dataBundleLoader.fetchForecastSnapshots(any(), any()) } returns emptyMap()
        coEvery { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) } just Runs
        coEvery { synopticRefresher.refreshIfDue(any(), any(), any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        WeatherDatabase.getDatabase(context).close()
    }

    private fun pipeline() = FullSyncPipeline(
        context = context,
        weatherRepository = weatherRepository,
        widgetStateManager = widgetStateManager,
        appLogDao = appLogDao,
        gpsResampler = gpsResampler,
        hourlyForecastLoader = hourlyForecastLoader,
        dataBundleLoader = dataBundleLoader,
        painter = painter,
        metarRefresher = metarRefresher,
        synopticRefresher = synopticRefresher,
    )

    @Test
    fun `ui-only refresh touches the network in no way at all`() = runBlocking {
        pipeline().run(workInput(uiOnly = true), device(), stopReason = 0)

        // THE regression guard. This call sat outside the gate on 2026-08-23.
        coVerify(exactly = 0) { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { synopticRefresher.refreshIfDue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { weatherRepository.backfillNwsObservationsIfNeeded(any(), any()) }

        // The fetch still runs, but forbidden from going out to the network, and never forced.
        val networkAllowed = slot<Boolean>()
        val forceRefresh = slot<Boolean>()
        coVerify(exactly = 1) {
            weatherRepository.getWeatherData(
                any(), any(), capture(forceRefresh), capture(networkAllowed), any(), any(),
            )
        }
        assertFalse("uiOnly must forbid network access", networkAllowed.captured)
        assertFalse("uiOnly must never force a refresh", forceRefresh.captured)

        // Stored data must not be rewritten by a repaint.
        coVerify(exactly = 0) { weatherRepository.ensureForecastOnlyHistoryRows(any(), any()) }
        coVerify(exactly = 0) { weatherRepository.snapshotDisplayedRainChance(any(), any()) }
        coVerify(exactly = 0) { weatherRepository.backfillForecastChanceSnapshotsIfNeeded(any(), any()) }
        coVerify(exactly = 0) { weatherRepository.backfillFrozenDisplayColumnsIfNeeded(any(), any()) }
        coVerify(exactly = 0) { weatherRepository.repairFrozenRainChanceIfNeeded(any(), any()) }

        // GPS resampling piggybacks on full syncs only — it is a location write, not a repaint.
        coVerify(exactly = 0) { gpsResampler.resample(any(), any()) }

        // The actuals recompute is the expensive one; a repaint reads what is already there.
        val recompute = slot<Boolean>()
        coVerify(exactly = 1) {
            dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), capture(recompute))
        }
        assertFalse("uiOnly must not recompute actuals", recompute.captured)

        // It must still actually paint — that is the entire point of the mode.
        val uiOnlyFlag = slot<Boolean>()
        coVerify(exactly = 1) {
            painter.updateAllWidgets(
                any(), any(), any(), any(), any(), any(), capture(uiOnlyFlag), any(), any(), any(), any(), any(), any(),
            )
        }
        assertTrue("uiOnly must reach the painter, flagged as uiOnly", uiOnlyFlag.captured)
    }

    /**
     * The paired positive case. Without it every `exactly = 0` above would still pass if `run()`
     * returned early and did nothing whatsoever.
     */
    @Test
    fun `full sync performs the network work that ui-only skips`() = runBlocking {
        pipeline().run(workInput(uiOnly = false), device(), stopReason = 0)

        val tiers = slot<Set<MetarFetchPolicy.Tier>>()
        val metarLat = slot<Double>()
        val metarLon = slot<Double>()
        coVerify(exactly = 1) {
            metarRefresher.refreshIfDue(capture(tiers), capture(metarLat), capture(metarLon), any(), any())
        }
        assertEquals(
            "The full sync is the only path METAR reliably gets a turn on, so it accepts both tiers",
            setOf(MetarFetchPolicy.Tier.PRIMARY, MetarFetchPolicy.Tier.NON_PRIMARY),
            tiers.captured,
        )
        // Compared with a tolerance, never with `eq`: ActiveLocationResolver persists coordinates as
        // Float, so 37.4220 comes back as 37.422000885009766. Exact equality against the Double
        // literal fails, which is the same trap that broke coordinate matching elsewhere — hence
        // LocationMatch.sameSite rather than `==` throughout the app.
        assertEquals(lat, metarLat.captured, COORD_TOLERANCE)
        assertEquals(lon, metarLon.captured, COORD_TOLERANCE)

        coVerify(exactly = 1) { weatherRepository.backfillNwsObservationsIfNeeded(any(), any()) }

        val networkAllowed = slot<Boolean>()
        coVerify(exactly = 1) {
            weatherRepository.getWeatherData(any(), any(), any(), capture(networkAllowed), any(), any())
        }
        assertTrue("a full sync is allowed on the network", networkAllowed.captured)

        coVerify(exactly = 1) { weatherRepository.ensureForecastOnlyHistoryRows(any(), any()) }
        coVerify(exactly = 1) { weatherRepository.snapshotDisplayedRainChance(any(), any()) }
        coVerify(exactly = 1) { weatherRepository.backfillForecastChanceSnapshotsIfNeeded(any(), any()) }
        coVerify(exactly = 1) { weatherRepository.backfillFrozenDisplayColumnsIfNeeded(any(), any()) }
        coVerify(exactly = 1) { weatherRepository.repairFrozenRainChanceIfNeeded(any(), any()) }

        coVerify(exactly = 1) { gpsResampler.resample(any(), any()) }

        val recompute = slot<Boolean>()
        coVerify(exactly = 1) {
            dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), capture(recompute))
        }
        assertTrue("a full sync recomputes actuals", recompute.captured)
    }

    /**
     * METAR is fetched before the actuals recompute, never after: rows landing later would sit
     * unused until the next cycle, which is a whole battery-aware interval of stale actuals.
     */
    @Test
    fun `METAR lands before the actuals recompute`() = runBlocking {
        val order = mutableListOf<String>()
        coEvery { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) } answers {
            order += "metar"
        }
        coEvery { dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), any()) } answers {
            order += "actuals"
            mockk(relaxed = true)
        }

        pipeline().run(workInput(uiOnly = false), device(), stopReason = 0)

        assertEquals(listOf("metar", "actuals"), order)
    }

    private fun forecastRow() = com.weatherwidget.data.local.ForecastEntity(
        targetDate = System.currentTimeMillis(),
        dateOfPrediction = System.currentTimeMillis(),
        highTemp = 70f,
        lowTemp = 50f,
        condition = "Clear",
        source = WeatherSource.NWS.id,
        locationLat = lat,
        locationLon = lon,
    )

    private fun device() = DeviceContext(
        isCharging = true,
        batteryLevel = 90,
        isScreenInteractive = true,
        lastFullFetchAgeSeconds = 3_600L,
    )

    private fun workInput(uiOnly: Boolean) = WorkInput(
        uiOnlyRefresh = uiOnly,
        forceRefresh = false,
        candidateLocationRefresh = false,
        currentTempOnly = false,
        nonPrimaryCurrentTempOnly = false,
        opportunisticCurrentTemp = false,
        currentTempReason = "test",
        targetSourceId = null,
        userInteraction = false,
        observationBackfillMode = false,
        backfillLat = 0.0,
        backfillLon = 0.0,
        backfillHours = 0L,
        backfillReason = "",
        noHourlyWidgetId = 0,
        noHourlyDate = null,
        noHourlyLat = 0.0,
        noHourlyLon = 0.0,
        shouldBroadcastNoHourlyComplete = false,
    )
}
