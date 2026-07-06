package com.weatherwidget.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
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
    private val healed = mutableListOf<Triple<Double, Double, String>>()
    private val providerCalls = mutableListOf<Boolean>()

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
    }

    private fun resampler(
        fix: Location?,
        fineGranted: Boolean = true,
        backgroundGranted: Boolean = true,
    ) = GpsResampler(
        appLogDao = appLogDao,
        sharedLocationResolver = resolver,
        locationProvider = { _, useActiveFix ->
            providerCalls.add(useActiveFix)
            fix
        },
        permissionChecker = { _, permission ->
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) fineGranted else backgroundGranted
        },
        applyHeal = { _, lat, lon, label -> healed.add(Triple(lat, lon, label)) },
    )

    private fun fix(lat: Double, lon: Double): Location =
        Location("test").apply {
            latitude = lat
            longitude = lon
        }

    private fun setBattery(charging: Boolean, level: Int) {
        val battery = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
            )
            putExtra(BatteryManager.EXTRA_PLUGGED, if (charging) BatteryManager.BATTERY_PLUGGED_AC else 0)
            putExtra(BatteryManager.EXTRA_LEVEL, level)
        }
        context.sendStickyBroadcast(battery)
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

    private fun outcomes(): List<String> =
        logged.filter { it.tag == GpsResampler.LOG_TAG }.map { it.message }

    @Test
    fun `unplugged below threshold does not skip but uses passive location`() = runTest {
        setBattery(charging = false, level = 60)
        bindWidgetAt(101, 34.0522, -118.2437)

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(false), providerCalls)
        assertEquals(listOf(Triple(40.7128, -74.0060, "Testville")), healed)
        val healedLog = logged.single { it.tag == GpsResampler.LOG_TAG }
        assertTrue(healedLog.message.startsWith("outcome=healed trigger=worker"))
    }

    @Test
    fun `missing fine location permission skips sampling`() = runTest {
        setBattery(charging = true, level = 50)

        resampler(fix = fix(40.7128, -74.0060), fineGranted = false).resample(context)

        assertTrue(providerCalls.isEmpty())
        assertTrue(healed.isEmpty())
        assertEquals(listOf("outcome=skipped_no_permission"), outcomes())
    }

    @Test
    fun `missing background permission degrades to passive last location`() = runTest {
        setBattery(charging = true, level = 50)

        resampler(fix = null, backgroundGranted = false).resample(context)

        assertEquals(listOf(false), providerCalls)
        assertEquals(listOf("outcome=no_fix mode=last_location"), outcomes())
    }

    @Test
    fun `no fix leaves breadcrumb and does not heal`() = runTest {
        setBattery(charging = true, level = 50)
        bindWidgetAt(101, 34.0522, -118.2437)

        resampler(fix = null).resample(context)

        assertEquals(listOf(true), providerCalls)
        assertTrue(healed.isEmpty())
        assertEquals(listOf("outcome=no_fix mode=active_fix"), outcomes())
    }

    @Test
    fun `same-site fix does not heal`() = runTest {
        setBattery(charging = true, level = 50)
        bindWidgetAt(101, 34.0522, -118.2437)

        // Within LocationMatch.SAME_SITE_TOLERANCE_DEG (0.002) of the configured location.
        resampler(fix = fix(34.0525, -118.2438)).resample(context)

        assertTrue(healed.isEmpty())
        assertEquals(1, outcomes().size)
        assertTrue(outcomes()[0].startsWith("outcome=same_site trigger=worker"))
    }

    @Test
    fun `differing fix heals all widgets with resolved label`() = runTest {
        setBattery(charging = true, level = 50)
        bindWidgetAt(101, 34.0522, -118.2437)

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(Triple(40.7128, -74.0060, "Testville")), healed)
        val healedLog = logged.single { it.tag == GpsResampler.LOG_TAG }
        assertTrue(healedLog.message.startsWith("outcome=healed trigger=worker"))
        assertTrue(healedLog.message.endsWith("label=Testville"))
        assertEquals("INFO", healedLog.level)
    }

    @Test
    fun `label lookup failure still heals with raw coordinate label`() = runTest {
        setBattery(charging = true, level = 50)
        bindWidgetAt(101, 34.0522, -118.2437)
        coEvery { resolver.fromCoordinates(any(), any()) } throws RuntimeException("geocoder down")

        resampler(fix = fix(40.7128, -74.0060)).resample(context)

        assertEquals(listOf(Triple(40.7128, -74.0060, "40.7128, -74.0060")), healed)
    }

    @Test
    fun `healIfNeeded returns whether a heal was applied`() = runTest {
        bindWidgetAt(101, 34.0522, -118.2437)
        val resampler = resampler(fix = null)

        assertFalse(resampler.healIfNeeded(context, 34.0522, -118.2437, trigger = "foreground"))
        assertTrue(resampler.healIfNeeded(context, 40.7128, -74.0060, trigger = "foreground"))
        assertEquals(1, healed.size)
        assertTrue(outcomes().any { it.startsWith("outcome=same_site trigger=foreground") })
        assertTrue(outcomes().any { it.startsWith("outcome=healed trigger=foreground") })
    }
}
