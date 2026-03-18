import re

path = 'app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt'
with open(path, 'r') as f:
    content = f.read()

# Replace the fetching logic
old_fetch = """                val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
                val actuals = repository?.getHourlyActuals(
                    startDateTime = graphStart.format(fmt),
                    endDateTime = graphEnd.format(fmt),
                    source = displaySource.id,
                    latitude = lat,
                    longitude = lon,
                ) ?: emptyList()
                android.util.Log.d("ActualsDebug", "getHourlyActuals: widget=$appWidgetId ${actuals.size} rows, window=[${graphStart.format(fmt)}..${graphEnd.format(fmt)}], zoom=$zoom, source=${displaySource.id}, lat=$lat, lon=$lon, repoNull=${repository == null}")
                val hourData = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource, zoom, actuals)"""

new_fetch = """                val minEpoch = graphStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val maxEpoch = graphEnd.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val observations = repository?.getObservationsInRange(minEpoch, maxEpoch, lat, lon) ?: emptyList()
                
                // Filter by source
                val filteredObs = observations.filter { obs ->
                    when (displaySource) {
                        WeatherSource.OPEN_METEO -> obs.stationId.startsWith("OPEN_METEO")
                        WeatherSource.WEATHER_API -> obs.stationId.startsWith("WEATHER_API")
                        WeatherSource.SILURIAN -> obs.stationId.startsWith("SILURIAN")
                        WeatherSource.NWS -> !obs.stationId.startsWith("OPEN_METEO") && !obs.stationId.startsWith("WEATHER_API") && !obs.stationId.startsWith("SILURIAN")
                        else -> true
                    }
                }
                
                android.util.Log.d("ActualsDebug", "getObservations: widget=$appWidgetId ${filteredObs.size} rows, zoom=$zoom")
                val hourData = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource, zoom, filteredObs)"""

content = content.replace(old_fetch, new_fetch)

# Replace the method signature
old_sig = """    internal fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actuals: List<HourlyActualEntity> = emptyList(),
    ): List<TemperatureGraphRenderer.HourData> {"""

new_sig = """    internal fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actuals: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
    ): List<TemperatureGraphRenderer.HourData> {"""

content = content.replace(old_sig, new_sig)

with open(path, 'w') as f:
    f.write(content)
