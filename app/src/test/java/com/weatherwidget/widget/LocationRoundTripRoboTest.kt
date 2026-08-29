package com.weatherwidget.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The full move-and-return trip, driven through the real handoff store: home → San Francisco →
 * home, asserting at each leg that nothing the widget draws goes stale, missing, or wrong.
 *
 * This exists because **the emulator cannot run this scenario.** Two independent injection methods
 * were tried on 2026-08-28 (`Generic_Foldable_API36`):
 *  - `adb emu geo fix` returns `OK` and injects nothing — `dumpsys location` held a fix with
 *    `et=+12h` throughout. Nothing ever powers on the emulator's GPS, because this app deliberately
 *    never requests an active fix from a background path (the Samsung precise-location rule), so the
 *    injected NMEA has no listener.
 *  - `cmd location providers set-test-provider-location fused` does move the *platform* provider —
 *    verified as `Location[fused 37.774900,-122.419400 mock]` — but [GpsResampler] reads Play
 *    services' `FusedLocationProviderClient.lastLocation`, which keeps its own store and ignored it.
 *    `GPS_RESAMPLE` logged `outcome=same_site lat=37.4167967` for half an hour.
 *
 * So the GPS *plumbing* is untestable off-device, and only a real move on real hardware exercises
 * it. Everything above that plumbing — which is where every bug in this area has actually lived — is
 * driven here through [GpsResampler]'s injected `locationProvider` seam, deterministically and in
 * seconds instead of a 30-minute wall-clock run.
 *
 * `proposeCandidate` is deliberately NOT stubbed (unlike [GpsResamplerTest], which is testing the
 * decision alone): the point here is the round trip through [LocationHandoffStore], including that
 * the candidate is actually cleared on return.
 */
@Category(LongDuration::class)
class LocationRoundTripRoboTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var appLogDao: AppLogDao
    private lateinit var resolver: SharedLocationResolver
    private val logged = mutableListOf<AppLogEntity>()

    private val homeLat = 37.416798
    private val homeLon = -122.089
    private val sfLat = 37.7749
    private val sfLon = -122.4194

    private val source = WeatherSource.NWS.id
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: LocalDateTime = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
    private val widgetId = 77

    private fun hourMs(offset: Long): Long =
        now.plusHours(offset).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)

        appLogDao = mockk()
        coEvery { appLogDao.insert(any()) } answers { logged.add(firstArg()); Unit }
        resolver = mockk()
        coEvery { resolver.fromCoordinates(any(), any()) } answers {
            ResolvedLocation(firstArg(), secondArg(), label = "Testville", source = "test")
        }

        ActiveLocationResolver.clear(context)
        bindWidgetAt(widgetId, homeLat, homeLon)
        ActiveLocationResolver.persist(context, homeLat, homeLon)
        seedHomeWeather()
    }

    @After
    fun tearDown() {
        ActiveLocationResolver.clear(context)
        db.close()
        WeatherDatabase.setIsTesting(false)
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun bindWidgetAt(id: Int, lat: Double, lon: Double) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$id", lon.toFloat())
            .commit()
    }

    /** A full local day of home forecasts and observations — what the widget draws before the trip. */
    private suspend fun seedHomeWeather() {
        db.hourlyForecastDao().insertAll(
            (-12L..12L).map { offset ->
                HourlyForecastEntity(
                    dateTime = hourMs(offset),
                    locationLat = homeLat,
                    locationLon = homeLon,
                    temperature = 68f,
                    condition = "Clear",
                    source = source,
                    cloudCover = 20,
                    fetchedAt = hourMs(0),
                )
            },
        )
        db.observationDao().insertAll(
            (-12L..0L).map { offset ->
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett Field",
                    timestamp = hourMs(offset),
                    temperature = 67f,
                    condition = "Clear",
                    locationLat = homeLat,
                    locationLon = homeLon,
                    distanceKm = 2.4f,
                    stationType = "OFFICIAL",
                    fetchedAt = hourMs(0),
                    api = source,
                    cloudCover = 20,
                    isMetar = true,
                )
            },
        )
    }

    private fun resampler(fix: Location) = GpsResampler(
        appLogDao = appLogDao,
        sharedLocationResolver = resolver,
        locationProvider = { _ -> fix },
        permissionChecker = { _, permission -> permission == Manifest.permission.ACCESS_FINE_LOCATION },
        // proposeCandidate left at its production default on purpose — see the class KDoc.
    )

    private fun fix(lat: Double, lon: Double): Location =
        Location("test").apply { latitude = lat; longitude = lon }

    private fun outcomes(): List<String> =
        logged.filter { it.tag == GpsResampler.LOG_TAG }.map { it.message }

    /** Hourly rows the renderer would receive, centred where the app currently believes it is. */
    private suspend fun hourlyAtActiveLocation(): List<HourlyForecastEntity> {
        val active = requireNotNull(ActiveLocationResolver.current(context)) { "no active location" }
        return HourlyForecastLoader(context, WidgetStateManager(context))
            .load(active.first, active.second, listOf(source), caller = "round_trip_test")
    }

    private suspend fun observationsAtActiveLocation(): List<ObservationEntity> {
        val active = requireNotNull(ActiveLocationResolver.current(context))
        return db.observationDao()
            .getObservationsInRange(hourMs(-24), hourMs(1), active.first, active.second)
    }

    private fun assertDrawableAndLocal(label: String, hourly: List<HourlyForecastEntity>, obs: List<ObservationEntity>) {
        assertTrue("$label: hourly forecast is missing", hourly.isNotEmpty())
        assertTrue("$label: observations are missing", obs.isNotEmpty())
        assertTrue(
            "$label: hourly rows came from a site the widget is not at: " +
                hourly.map { it.locationLat to it.locationLon }.distinct(),
            hourly.all { LocationMatch.sameSite(homeLat, homeLon, it.locationLat, it.locationLon) },
        )
        assertTrue(
            "$label: observations came from a foreign site: " +
                obs.map { it.locationLat to it.locationLon }.distinct(),
            obs.all { LocationMatch.sameSite(homeLat, homeLon, it.locationLat, it.locationLon) },
        )
        assertTrue("$label: cloud cover is missing", hourly.any { it.cloudCover != null })
    }

    @Test
    fun `home to San Francisco and back leaves temperature and cloud intact`() = runTest {
        // --- step 1: baseline at home -------------------------------------------------------
        val baselineHourly = hourlyAtActiveLocation()
        val baselineObs = observationsAtActiveLocation()
        assertDrawableAndLocal("baseline", baselineHourly, baselineObs)

        // --- step 2: move to San Francisco --------------------------------------------------
        resampler(fix(sfLat, sfLon)).resample(context)

        assertTrue(
            "the move must be applied, not queued: ${outcomes()}",
            outcomes().any { it.contains("location_moved") },
        )
        // The assertion that inverted on 2026-08-28. This used to require the active location to
        // STAY at home until the new site had "enough" data. It now moves at once: if the user is
        // looking at the phone they should see where they are, and fetch cost is bounded by the
        // battery-aware cadence rather than by withholding the location.
        assertEquals(
            "the active location must follow the device",
            sfLat,
            ActiveLocationResolver.current(context)!!.first,
            1e-5,
        )
        assertEquals(sfLon, ActiveLocationResolver.current(context)!!.second, 1e-5)

        // --- step 3: hold there -------------------------------------------------------------
        // Nothing has been fetched for SF yet, so the widget is briefly sparse there. That is the
        // accepted trade: honestly thin data for where you are, rather than a complete graph for a
        // city you have left. What must NOT happen is home's rows being served under SF's name.
        val awayHourly = hourlyAtActiveLocation()
        assertTrue(
            "home's rows must not be served for the new location: " +
                awayHourly.map { it.locationLat to it.locationLon }.distinct(),
            awayHourly.none { LocationMatch.sameSite(homeLat, homeLon, it.locationLat, it.locationLon) },
        )

        // --- step 4: return home ------------------------------------------------------------
        logged.clear()
        resampler(fix(homeLat, homeLon)).resample(context)

        assertTrue(
            "the return must be applied like any other move: ${outcomes()}",
            outcomes().any { it.contains("location_moved") },
        )
        assertEquals(
            "the active location must be home again",
            homeLat,
            ActiveLocationResolver.current(context)!!.first,
            1e-5,
        )

        // Nothing lost to the excursion: same rows, same count, same sites as before the trip.
        val afterHourly = hourlyAtActiveLocation()
        val afterObs = observationsAtActiveLocation()
        assertDrawableAndLocal("after return", afterHourly, afterObs)
        assertEquals("hourly rows were lost to the excursion", baselineHourly.size, afterHourly.size)
        assertEquals("observations were lost to the excursion", baselineObs.size, afterObs.size)
        assertEquals(
            "the observed temperatures changed across a trip that fetched nothing",
            baselineObs.map { it.temperature },
            afterObs.map { it.temperature },
        )
    }

    /**
     * A second excursion must not accumulate state. The candidate from trip one is cleared on
     * return, so trip two has to be detected on its own merits rather than seen as "same candidate".
     */
    @Test
    fun `a second excursion is detected again after the first was cleared`() = runTest {
        resampler(fix(sfLat, sfLon)).resample(context)
        resampler(fix(homeLat, homeLon)).resample(context)
        logged.clear()

        resampler(fix(sfLat, sfLon)).resample(context)

        assertTrue(
            "the second move must be applied, not swallowed as already-seen: ${outcomes()}",
            outcomes().any { it.contains("location_moved") },
        )
        assertEquals(sfLat, ActiveLocationResolver.current(context)!!.first, 1e-5)
    }
}
