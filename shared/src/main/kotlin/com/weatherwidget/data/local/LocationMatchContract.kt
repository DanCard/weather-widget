package com.weatherwidget.data.local

/**
 * Canonical cases that BOTH persistence layers must satisfy for [LocationMatch]: the Android Room
 * DAOs (`:app`) and the desktop JDBC DAO (`:shared`). Each backend has a thin test that seeds a row
 * at ([Case.storedLat], [Case.storedLon]) and asserts that querying ([Case.queryLat],
 * [Case.queryLon]) returns it exactly when [Case.shouldMatch] is true.
 *
 * Because the two DAOs are hand-maintained in parallel (Room annotations vs raw JDBC), this shared
 * spec is what keeps them from silently drifting — the divergence that once left the desktop
 * cloud-cover graph flat when its data sat under a neighbouring coordinate. See [LocationMatch].
 */
object LocationMatchContract {
    data class Case(
        val name: String,
        val storedLat: Double,
        val storedLon: Double,
        val queryLat: Double,
        val queryLon: Double,
        val shouldMatch: Boolean,
    )

    val CASES: List<Case> = listOf(
        Case("exact same coordinate", 37.4167, -122.089, 37.4167, -122.089, true),
        // The bug: same address, geocoded vs manually entered at different precision (~15 m apart).
        Case("geocoding precision jitter", 37.416883, -122.089009, 37.4167, -122.089, true),
        // Still the same neighbourhood, comfortably inside the ~7-mile box.
        Case("a few blocks away", 37.4167, -122.089, 37.4225, -122.0955, true),
        // A genuinely different location must NOT be merged in.
        Case("different city (~70 mi)", 38.5806, -121.4936, 37.4167, -122.089, false),
    )
}
