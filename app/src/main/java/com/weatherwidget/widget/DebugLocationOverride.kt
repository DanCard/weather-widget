package com.weatherwidget.widget

import android.content.Context
import android.location.Location
import com.weatherwidget.BuildConfig
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * A debug-only stand-in for the device's passive location fix, so the move-and-return scenario can
 * be driven on an emulator.
 *
 * **Why this has to exist in the app rather than in the emulator.** Measured 2026-08-28 on
 * `Generic_Foldable_API36`, neither way of injecting a location reaches this app:
 *
 *  - `adb emu geo fix` returns `OK` and injects nothing. `dumpsys location` held a fix with
 *    `et=+12h` throughout. Nothing ever powers on the emulator's GPS, because no background path in
 *    this app requests an active fix (the Samsung precise-location rule), so the injected NMEA has
 *    no listener.
 *  - `cmd location providers set-test-provider-location fused` moves the *platform* provider —
 *    verified as `Location[fused 37.774900,-122.419400 mock]` — but [GpsResampler] reads Play
 *    services' `FusedLocationProviderClient.lastLocation`, which keeps its own store and ignored it.
 *    `GPS_RESAMPLE` logged `outcome=same_site lat=37.4167967` for half an hour afterwards.
 *
 * A passive-only location design is close to un-drivable from outside the process, so the seam has
 * to be inside it.
 *
 * **This is not a mock-location feature and must never become one.** It is read only by
 * [GpsResampler.awaitLastLocation], the passive background read. It deliberately does not touch
 * `ConfigActivity`'s user-initiated precise-location button, the geocoding search, or any stored
 * location: a real coordinate the user chose always wins, and turning the override off restores the
 * real fix immediately with nothing to clean up.
 *
 * **Release builds cannot reach it.** Every accessor is gated on [BuildConfig.DEBUG], and the
 * broadcast receiver that sets it lives in `src/debug` so it is not merged into a release manifest
 * at all. Both, not either: the gate alone would still ship a receiver capable of moving a user's
 * weather location, and the source-set split alone would leave a live setter one reflective call
 * away.
 */
object DebugLocationOverride {
    private const val PREFS_NAME = "weather_prefs"
    private const val KEY_LAT = "debug_location_override_lat"
    private const val KEY_LON = "debug_location_override_lon"

    /** Marks the [Location] this returns, so a puzzling fix is traceable to the override. */
    const val PROVIDER = "debug_override"

    /**
     * The overriding fix, or null when unset — and always null in release.
     *
     * Stored as strings rather than floats: a coordinate round-tripped through `Float` is not equal
     * to the double it came from, which is the trap behind `float_prefs_break_coordinate_equality`
     * and would make an injected site fail `sameSite` against itself.
     */
    fun get(context: Context): Location? {
        if (!BuildConfig.DEBUG) return null
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        return Location(PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }

    /** True when an override is armed; for breadcrumbs that must say so out loud. */
    fun isActive(context: Context): Boolean = get(context) != null

    fun set(context: Context, lat: Double, lon: Double) {
        if (!BuildConfig.DEBUG) return
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .commit()
    }

    fun clear(context: Context) {
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit()
            .remove(KEY_LAT)
            .remove(KEY_LON)
            .commit()
    }
}
