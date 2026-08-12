package com.weatherwidget.widget

/**
 * Arbitrary coordinates for instrumented tests that need *a* location and do not care which.
 *
 * These tests previously reached for the worker's hardcoded default coordinates, which happened to be
 * convenient but tied them to a production constant that no longer exists — "no location" is now the
 * absence of coordinates rather than a stand-in for one. The values are unchanged so the fixtures
 * behave identically; only their meaning is now local to the tests.
 */
internal object TestLocations {
    const val LAT = 37.4220
    const val LON = -122.0841
}
