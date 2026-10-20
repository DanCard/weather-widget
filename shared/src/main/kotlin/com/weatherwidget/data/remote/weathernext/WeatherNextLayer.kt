package com.weatherwidget.data.remote.weathernext

data class WeatherNextSwatch(val hexColor: String, val label: String)

enum class WeatherNextLayer(
    val id: String,
    val displayName: String,
    val shortTitle: String,
    val fullTitle: String,
    val subtitle: String,
    val legendNote: String,
    val swatches: List<WeatherNextSwatch>,
) {
    QPF1_IMERG(
        id = "qpf1_imerg",
        displayName = "1-Hour Rain Rate (qpf1_imerg)",
        shortTitle = "1-Hour Rain Rate & Timing",
        fullTitle = "⏱ 1-Hour Rain Rate & Showers (NASA IMERG-calibrated)",
        subtitle = "Color: Rain expected in this 1-hour window (inches/hr) · Hour-by-hour resolution out to 48 hours",
        legendNote = "Precipitation Intensity (inches/hr)",
        swatches = listOf(
            WeatherNextSwatch("#97f58d", "0.05\"/h"),
            WeatherNextSwatch("#51f051", "0.1\"/h"),
            WeatherNextSwatch("#2d7dee", "0.25\"/h"),
            WeatherNextSwatch("#ffc03d", "0.5\"/h"),
            WeatherNextSwatch("#e41800", "1.0\"/h+"),
        ),
    ),
    PCP6(
        id = "pcp6",
        displayName = "6-Hour Rain & Fronts (pcp6)",
        shortTitle = "6-Hour Rain, Pressure & Fronts",
        fullTitle = "🌧 6-Hour Rain, Surface Pressure & Frontal Temperature",
        subtitle = "Color: Rain amount over past 6h (inches) · Black lines: Isobars / Pressure (hPa) · Red lines: 850mb Frontal Temp (°C)",
        legendNote = "Black = Pressure (hPa) | Red = 850mb Temp (°C)",
        swatches = listOf(
            WeatherNextSwatch("#97f58d", "0.1\""),
            WeatherNextSwatch("#51f051", "0.3\""),
            WeatherNextSwatch("#2d7dee", "0.5\""),
            WeatherNextSwatch("#ffc03d", "1.0\""),
            WeatherNextSwatch("#ff6100", "1.5\""),
            WeatherNextSwatch("#e41800", "2.0\"+"),
        ),
    ),
    QPF_ACC(
        id = "qpf_acc",
        displayName = "Total Rain Accumulation (qpf_acc)",
        shortTitle = "Total Rain Accumulation",
        fullTitle = "💧 Total Cumulative Rain Accumulation",
        subtitle = "Color: Total rainfall accumulated since run start (inches) · Shows expected storm total impact",
        legendNote = "Total Storm Accumulation (inches)",
        swatches = listOf(
            WeatherNextSwatch("#97f58d", "0.1\""),
            WeatherNextSwatch("#51f051", "0.5\""),
            WeatherNextSwatch("#2d7dee", "1.0\""),
            WeatherNextSwatch("#ffc03d", "2.0\""),
            WeatherNextSwatch("#e41800", "4.0\"+"),
        ),
    ),
    IVT(
        id = "ivt",
        displayName = "Water Vapor Transport (ivt)",
        shortTitle = "Integrated Vapor Transport",
        fullTitle = "🌊 Integrated Vapor Transport (Atmospheric Rivers)",
        subtitle = "Color: Moisture flux 1000–300 mb (kg/m/s) · Streamlines show moisture transport wind direction",
        legendNote = "Arrows: Moisture Wind Flow",
        swatches = listOf(
            WeatherNextSwatch("#d9f0c8", "250"),
            WeatherNextSwatch("#8dce78", "500"),
            WeatherNextSwatch("#3aa0c8", "750"),
            WeatherNextSwatch("#1f6fbf", "1000+"),
        ),
    );

    companion object {
        fun fromId(id: String): WeatherNextLayer = entries.firstOrNull { it.id == id } ?: QPF1_IMERG
    }
}
