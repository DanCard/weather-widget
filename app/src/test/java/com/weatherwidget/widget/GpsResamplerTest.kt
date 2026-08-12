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
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.LocationMode
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf

@Category(LongDuration::class)
class GpsResamplerTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var appLogDao: AppLogDao
    private lateinit var resolver: SharedLocationResolver
    private val logged = mutableListOf<AppLogEntity>()
    private val proposed = mutableListOf<Triple<Double, Double, String>>()
    /** enqueueRefresh flag passed to proposeCandidate, per detected candidate. */
    private val enqueueRefreshFlags = mutableListOf<Boolean>()
    private var providerCalls = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appLogDao = mockk()
        coEvery { appLogDao.insert(any()) } answers {
            logged.add(firstArg())
            Unit
        }
        resolver = mockk()
        coEvery { resolver.fromCoordinates(any(), any()) } answers {
            ResolvedLocation(firstArg(), secondArg(), label = "Testville", source = "test")
        }
        LocationHandoffStore.clear(context)
    }

    private fun resampler(
        fix: Location?,
        fineGranted: Boolean = true,
    ) = GpsResampler(
        appLogDao = appLogDao,
        sharedLocationResolver = resolver,
        locationProvider = { _ ->
            providerCalls++
            fix
        },
        permissionChecker = { _, permission ->
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) fineGranted else true
        },
        proposeCandidate = { _, lat, lon, label, enqueueRefresh ->
            proposed.add(Triple(lat, lon, label))
            enqueueRefreshFlags.add(enqueueRefresh)
            true
        },
    )

    private fun fix(lat: Double, lon: Double): Location =
        Location("test").apply {
            latitude = lat
            longitude = lon
        }

    private fun bindWidgetAt(widgetId: Int, lat: Double, lon: Double) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", lon.toFloat())
            .commit()
    }

    /** A widget that exists but has never been given coordinates — the no-location state. */
    private fun bindWidgetWithoutLocation(widgetId: Int) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$widgetId")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$widgetId")
            .commit()
    }

    private fun seedHistoricalPoi(label: String, lat: Double, lon: Double) {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit()
            .putString("historical_pois", "$label|$lat|$lon")
            .commit()
    }

    private fun seedLegacyDeltaLocation(widgetId: Int, lat: Double, lon: Double) {
        SharedPreferencesUtil.getPrefs(context, "widget_state_prefs").edit()
            .putString("widget_current_temp_delta_lat_$widgetId", lat.toString())
            .putString("widget_current_temp_delta_lon_$widgetId", lon.toString())
            .commit()
    }

    private fun outcomes(): List<String> =
        logged.filter { it.tag == GpsResampler.LOG_TAG }.map { it.message }

    @Test
    fun `missing fine location permission skips sampling`() = runTest {
        resampler(fix = fix(40.7128, -74.0060), fineGranted = false).resample(context)

        assertEquals(0, providerCalls)
        assertTrue(proposed.isEmpty())
        assertEquals(listOf("outcome=skipped_no_permission trigger=worker"), outcomes())
    }

    @Test
    fun `empty location cache leaves breadcrumb and proposes nothing`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)

        resampler(fix = null).resample(context)

        assertEquals(1, providerCalls)
        assertTrue(proposed.isEmpty())
        assertEquals(listOf("outcome=no_fix mode=last_location trigger=worker"), outcomes())
    }

    @Test
    fun `event trigger labels the breadcrumb and asks for a candidate refresh`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)

        val changed = resampler(fix = fix(40.7128, -74.0060))
            .resample(context, trigger = "power_connected")

        assertTrue(changed)
        val proposalLog = logged.single { it.tag == GpsResampler.LOG_TAG }
        assertTrue(
            "Breadcrumb must name the caller, not the worker: ${proposalLog.message}",
            proposalLog.message.startsWith("outcome=candidate_detected trigger=power_connected"),
        )
        // The worker fetches the candidate itself mid-sync; an event-driven caller is not
        // mid-sync, so it must enqueue one or the candidate never gains the data to be promoted.
        assertEquals(listOf(true), enqueueRefreshFlags)
    }

    @Test
    fun `worker trigger leaves the candidate refresh to the sync in progress`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(false), enqueueRefreshFlags)
    }

    @Test
    fun `same-site fix proposes nothing`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)

        // Within LocationMatch.SAME_SITE_TOLERANCE_DEG (0.002) of the configured location.
        val changed = resampler(fix = fix(34.0525, -118.2438)).resample(context)

        assertFalse(changed)
        assertTrue(proposed.isEmpty())
        assertEquals(1, outcomes().size)
        assertTrue(outcomes()[0].startsWith("outcome=same_site trigger=worker"))
    }

    @Test
    fun `differing cached fix becomes a candidate with a resolved label`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)

        val changed = resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertTrue(changed)
        assertEquals(listOf(Triple(40.7128, -74.0060, "Testville")), proposed)
        val proposalLog = logged.single { it.tag == GpsResampler.LOG_TAG }
        assertTrue(proposalLog.message.startsWith("outcome=candidate_detected trigger=worker"))
        assertTrue(proposalLog.message.contains("label=Testville"))
        assertEquals("INFO", proposalLog.level)
    }

    @Test
    fun `label lookup failure still proposes, with a raw coordinate label`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        coEvery { resolver.fromCoordinates(any(), any()) } throws RuntimeException("geocoder down")

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(Triple(40.7128, -74.0060, "40.7128, -74.0060")), proposed)
    }

    @Test
    fun `fixed mode skips worker resample without reading location`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        LocationMode.set(context, LocationMode.FIXED)

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(0, providerCalls)
        assertTrue(proposed.isEmpty())
        assertEquals(listOf("outcome=skipped_pinned trigger=worker"), outcomes())
    }

    @Test
    fun `fixed mode skips foreground followDeviceIfMoved`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        LocationMode.set(context, LocationMode.FIXED)

        assertFalse(resampler(fix = null).followDeviceIfMoved(context, 40.7128, -74.0060, trigger = "foreground"))

        assertTrue(proposed.isEmpty())
        assertEquals(listOf("outcome=skipped_pinned trigger=foreground"), outcomes())
    }

    @Test
    fun `explicit follow_device mode proposes like the absent-key default`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        LocationMode.set(context, LocationMode.FOLLOW_DEVICE)

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(Triple(40.7128, -74.0060, "Testville")), proposed)
    }

    @Test
    fun `followDeviceIfMoved returns whether a candidate was proposed`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        val resampler = resampler(fix = null)

        assertFalse(resampler.followDeviceIfMoved(context, 34.0522, -118.2437, trigger = "foreground"))
        assertTrue(resampler.followDeviceIfMoved(context, 40.7128, -74.0060, trigger = "foreground"))
        assertEquals(1, proposed.size)
        assertTrue(outcomes().any { it.startsWith("outcome=same_site trigger=foreground") })
        assertTrue(outcomes().any { it.startsWith("outcome=candidate_detected trigger=foreground") })
    }

    // ---- "no location" must not be inferred away ----
    //
    // The sampler compares a fresh fix against where we currently are. Resolve that comparand through
    // anything inferred and a widget with no location reads as "already located": same site, no
    // candidate, and the no-location state can never be escaped by GPS — for a user standing exactly
    // where the app could have fixed it.

    @Test
    fun `a stale place name cannot masquerade as the current location`() = runTest {
        bindWidgetWithoutLocation(101)
        seedHistoricalPoi("Old Home", 34.0522, -118.2437)

        val changed = resampler(fix = fix(34.0522, -118.2437)).resample(context)

        assertTrue("a fix must be proposed even when it matches a saved place name", changed)
        assertEquals(listOf(Triple(34.0522, -118.2437, "Testville")), proposed)
        assertTrue(outcomes().single().startsWith("outcome=candidate_detected"))
    }

    @Test
    fun `a legacy delta-store coordinate cannot masquerade as the current location`() = runTest {
        bindWidgetWithoutLocation(101)
        seedLegacyDeltaLocation(101, 34.0522, -118.2437)

        val changed = resampler(fix = fix(34.0522, -118.2437)).resample(context)

        assertTrue(changed)
        assertEquals(listOf(Triple(34.0522, -118.2437, "Testville")), proposed)
    }

    /** A configured location still suppresses: this narrows what counts, it does not disable the check. */
    @Test
    fun `a stored location at the fix site still reports same site`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        seedHistoricalPoi("Old Home", 40.7128, -74.0060)

        val changed = resampler(fix = fix(34.0522, -118.2437)).resample(context)

        assertFalse(changed)
        assertTrue(proposed.isEmpty())
        assertTrue(outcomes().single().startsWith("outcome=same_site"))
    }

    @Test
    fun `no widgets leaves a breadcrumb that names the real reason`() = runTest {
        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf("outcome=skipped_no_widgets trigger=worker"), outcomes())
        assertTrue(proposed.isEmpty())
    }
}
