package com.weatherwidget.data.remote.weathernext

enum class WeatherNextDomain(
    val id: String,
    val title: String,
    val lat0: Double,
    val lat1: Double,
    val lon0: Double,
    val lon1: Double,
) {
    CA("ca", "California & West Coast", 27.0, 45.0, 215.0, 250.0),
    PNW("pnw", "Pacific Northwest", 40.0, 55.0, 215.0, 250.0),
    WIDE("wide", "North America & Pacific", 10.0, 75.0, 120.0, 300.0);

    companion object {
        fun resolve(lat: Double, lon: Double): WeatherNextDomain {
            val lon360 = (lon + 360.0) % 360.0
            return when {
                lat in 27.5..44.5 && lon360 in 216.0..249.0 -> CA
                lat in 40.5..54.5 && lon360 in 216.0..249.0 -> PNW
                else -> WIDE
            }
        }
    }
}
