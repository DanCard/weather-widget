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

/**
 * [GpsResampler.maybeResample]'s rate limit.
 *
 * The limit exists so resampling can be called from the places that have a *reason* to ask — a
 * paint, a screen-on, an opportunistic tick — instead of only from a full sync. Those callers are
 * far more frequent than syncs, so the cooldown is the only thing standing between them and a Play
 * services call per widget tap. See
 * plans/260828-detect-the-move-when-the-user-is-looking.md.
 */
@Category(LongDuration::class)
class GpsResampleCooldownTest : RobolectricTest() {

    private lateinit var context: Context
    private val logged = mutableListOf<AppLogEntity>()
    private var providerCalls = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
        ActiveLocationResolver.clear(context)
        bindWidgetAt(310, 37.4168, -122.0890)
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

    private fun resampler(): GpsResampler {
        val appLogDao = mockk<AppLogDao>()
        coEvery { appLogDao.insert(any()) } answers { logged.add(firstArg()); Unit }
        val resolver = mockk<SharedLocationResolver>()
        coEvery { resolver.fromCoordinates(any(), any()) } answers {
            ResolvedLocation(firstArg(), secondArg(), label = "Testville", source = "test")
        }
        return GpsResampler(
            appLogDao = appLogDao,
            sharedLocationResolver = resolver,
            locationProvider = { _ ->
                providerCalls++
                Location("test").apply { latitude = 37.4168; longitude = -122.0890 }
            },
            permissionChecker = { _, permission -> permission == Manifest.permission.ACCESS_FINE_LOCATION },
            applyLocation = { _, _, _, _, _ -> true },
        )
    }

    private fun outcomes(): List<String> =
        logged.filter { it.tag == GpsResampler.LOG_TAG }.map { it.message }

    @Test
    fun `a second request inside the cooldown does not read the location again`() = runTest {
        val resampler = resampler()

        resampler.maybeResample(context, "worker")
        resampler.maybeResample(context, "paint")

        assertEquals("the second request must not reach the location provider", 1, providerCalls)
        assertTrue(
            "the skip must leave a breadcrumb naming its cause: ${outcomes()}",
            outcomes().any { it.startsWith("outcome=skipped_cooldown trigger=paint") },
        )
    }

    @Test
    fun `a request after the cooldown reads again`() = runTest {
        val resampler = resampler()
        resampler.maybeResample(context, "worker")

        // Age the stored stamp rather than sleeping a minute in a unit test.
        val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        prefs.edit()
            .putLong("gps_resample_last_ms", System.currentTimeMillis() - GpsResampler.RESAMPLE_COOLDOWN_MS - 1)
            .commit()

        resampler.maybeResample(context, "screen_on")

        assertEquals(2, providerCalls)
    }

    /**
     * The cooldown gates the read, never the result. A move that has already been read is applied
     * regardless — otherwise a throttle intended to save battery would start losing locations.
     */
    @Test
    fun `the cooldown never suppresses a move that was read`() = runTest {
        val applied = mutableListOf<Pair<Double, Double>>()
        val appLogDao = mockk<AppLogDao>()
        coEvery { appLogDao.insert(any()) } answers { logged.add(firstArg()); Unit }
        val resolver = mockk<SharedLocationResolver>()
        coEvery { resolver.fromCoordinates(any(), any()) } answers {
            ResolvedLocation(firstArg(), secondArg(), label = "Elsewhere", source = "test")
        }
        val resampler = GpsResampler(
            appLogDao = appLogDao,
            sharedLocationResolver = resolver,
            locationProvider = { _ ->
                Location("test").apply { latitude = 37.7749; longitude = -122.4194 }
            },
            permissionChecker = { _, _ -> true },
            applyLocation = { _, lat, lon, _, _ -> applied.add(lat to lon); true },
        )

        assertTrue(resampler.maybeResample(context, "screen_on"))

        assertEquals(listOf(37.7749 to -122.4194), applied)
    }

    @Test
    fun `a clock that jumps backwards does not latch the cooldown on`() = runTest {
        val resampler = resampler()
        val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        // A stamp from the future: the device's clock moved back (timezone fix, NTP correction).
        prefs.edit().putLong("gps_resample_last_ms", System.currentTimeMillis() + 3_600_000).commit()

        resampler.maybeResample(context, "paint")

        assertEquals("a future stamp must not block resampling until the clock catches up", 1, providerCalls)
    }
}
