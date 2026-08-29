package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * [DebugLocationOverride] is the seam that makes a location move drivable on an emulator, where
 * neither `adb emu geo fix` nor a platform test provider reaches this app.
 *
 * The precision case is the one that matters most here: an injected coordinate that does not survive
 * storage would fail `sameSite` against itself, and the resulting "the app ignored my injected
 * location" would look exactly like the emulator failures this was written to escape.
 */
@Category(ShortDuration::class)
class DebugLocationOverrideTest : RobolectricTest() {

    private lateinit var context: Context

    private val sfLat = 37.7749
    private val sfLon = -122.4194

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DebugLocationOverride.clear(context)
    }

    @After
    fun tearDown() = DebugLocationOverride.clear(context)

    @Test
    fun `unset override reads as no override at all`() {
        assertNull(DebugLocationOverride.get(context))
        assertFalse(DebugLocationOverride.isActive(context))
    }

    @Test
    fun `an injected coordinate survives storage exactly`() {
        DebugLocationOverride.set(context, sfLat, sfLon)

        val fix = DebugLocationOverride.get(context)
        assertNotNull(fix)
        // Exact equality, no delta: a Float round-trip would pass a tolerance check and still break
        // sameSite comparisons downstream (see float_prefs_break_coordinate_equality).
        assertEquals(sfLat, fix!!.latitude, 0.0)
        assertEquals(sfLon, fix.longitude, 0.0)
        assertTrue(
            "an injected coordinate must be the same site as itself",
            LocationMatch.sameSite(sfLat, sfLon, fix.latitude, fix.longitude),
        )
    }

    @Test
    fun `the fix is labelled so a puzzling location can be traced back here`() {
        DebugLocationOverride.set(context, sfLat, sfLon)

        assertEquals(DebugLocationOverride.PROVIDER, DebugLocationOverride.get(context)!!.provider)
    }

    @Test
    fun `clearing restores the real fix with nothing left behind`() {
        DebugLocationOverride.set(context, sfLat, sfLon)
        assertTrue(DebugLocationOverride.isActive(context))

        DebugLocationOverride.clear(context)

        assertNull(DebugLocationOverride.get(context))
        assertFalse(DebugLocationOverride.isActive(context))
    }

    /**
     * The gate this whole design rests on. Unit tests run the debug variant, so this asserts the
     * premise rather than the release behaviour itself — if the app is ever built such that tests
     * run without `BuildConfig.DEBUG`, every accessor returns null and the tests above would fail
     * loudly rather than the override silently going live in a release.
     */
    @Test
    fun `the override is debug-gated`() {
        assertTrue(
            "unit tests must run the debug variant for these assertions to mean anything",
            BuildConfig.DEBUG,
        )
        DebugLocationOverride.set(context, sfLat, sfLon)
        assertEquals(BuildConfig.DEBUG, DebugLocationOverride.isActive(context))
    }
}
