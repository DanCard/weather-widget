import re

path = 'app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt'
with open(path, 'r') as f:
    content = f.read()

old_block = """    @androidx.annotation.VisibleForTesting
    internal fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actuals: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
    ): List<TemperatureGraphRenderer.HourData> {
        val hours = mutableListOf<TemperatureGraphRenderer.HourData>()
        val now = LocalDateTime.now()

        // Index actuals by dateTime for O(1) lookup
        val actualsByTime = actuals.associateBy { it.dateTime }

        // Group by dateTime and prefer the selected source, fallback to generic gap
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == displaySource.id }
                    val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    val fallback = entry.value.firstOrNull()
                    preferred ?: gap ?: fallback
                }

        // Round to nearest hour
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)

        val labelInterval = zoom.labelInterval

        var currentHour = startHour
        var hourIndex = 0

        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val forecast = forecastsByTime[hourKey]

            if (forecast != null) {
                val isCurrentHour = currentHour == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                val showLabel =
                    when (zoom) {
                        com.weatherwidget.widget.ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                        com.weatherwidget.widget.ZoomLevel.NARROW -> true
                    }

                val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
                val iconRes = WeatherIconMapper.getIconResource(
                    condition = forecast.condition,
                    isNight = isNight,
                    cloudCover = forecast.cloudCover,
                )
                val isSunny =
                    iconRes == R.drawable.ic_weather_clear ||
                        iconRes == R.drawable.ic_weather_mostly_clear ||
                        iconRes == R.drawable.ic_weather_night
                val isRainy =
                    iconRes == R.drawable.ic_weather_rain ||
                        iconRes == R.drawable.ic_weather_storm ||
                        iconRes == R.drawable.ic_weather_snow
                val isMixed =
                    iconRes == R.drawable.ic_weather_mostly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_cloudy_night ||
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_partly_cloudy_night ||
                        iconRes == R.drawable.ic_weather_fog_cloudy

                val actual = actualsByTime[hourKey]
                hours.add(
                    TemperatureGraphRenderer.HourData(
                        dateTime = currentHour,
                        temperature = forecast.temperature,          // always forecast
                        label = formatHourLabel(currentHour),
                        iconRes = iconRes,
                        isNight = isNight,
                        isSunny = isSunny,
                        isRainy = isRainy,
                        isMixed = isMixed,
                        isCurrentHour = isCurrentHour,
                        showLabel = showLabel,
                        isActual = actual != null,
                        actualTemperature = actual?.temperature,     // null for future hours
                    ),
                )
                hourIndex++
            }

            currentHour = currentHour.plusHours(1)
        }

        return hours
    }"""

new_block = """    @androidx.annotation.VisibleForTesting
    internal fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actuals: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
    ): List<TemperatureGraphRenderer.HourData> {
        val hours = mutableListOf<TemperatureGraphRenderer.HourData>()
        val now = LocalDateTime.now()

        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == displaySource.id }
                    val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    val fallback = entry.value.firstOrNull()
                    preferred ?: gap ?: fallback
                }

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)

        val labelInterval = zoom.labelInterval

        var currentHour = startHour
        var hourIndex = 0

        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        // 1. Collect top-of-hour forecasts
        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val forecast = forecastsByTime[hourKey]

            if (forecast != null) {
                val isCurrentHour = currentHour == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                val showLabel =
                    when (zoom) {
                        com.weatherwidget.widget.ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                        com.weatherwidget.widget.ZoomLevel.NARROW -> true
                    }

                val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
                val iconRes = WeatherIconMapper.getIconResource(
                    condition = forecast.condition,
                    isNight = isNight,
                    cloudCover = forecast.cloudCover,
                )
                val isSunny = iconRes == R.drawable.ic_weather_clear || iconRes == R.drawable.ic_weather_mostly_clear || iconRes == R.drawable.ic_weather_night
                val isRainy = iconRes == R.drawable.ic_weather_rain || iconRes == R.drawable.ic_weather_storm || iconRes == R.drawable.ic_weather_snow
                val isMixed = iconRes == R.drawable.ic_weather_mostly_cloudy || iconRes == R.drawable.ic_weather_mostly_cloudy_night || iconRes == R.drawable.ic_weather_partly_cloudy || iconRes == R.drawable.ic_weather_partly_cloudy_night || iconRes == R.drawable.ic_weather_fog_cloudy

                hours.add(
                    TemperatureGraphRenderer.HourData(
                        dateTime = currentHour,
                        temperature = forecast.temperature,
                        label = formatHourLabel(currentHour),
                        iconRes = iconRes,
                        isNight = isNight,
                        isSunny = isSunny,
                        isRainy = isRainy,
                        isMixed = isMixed,
                        isCurrentHour = isCurrentHour,
                        showLabel = showLabel,
                        isActual = false, 
                        actualTemperature = null, 
                    ),
                )
                hourIndex++
            }
            currentHour = currentHour.plusHours(1)
        }

        // 2. Inject sub-hourly actuals
        val finalHours = mutableListOf<TemperatureGraphRenderer.HourData>()
        val allTimes = hours.map { it.dateTime }.toMutableSet()
        val actualMap = mutableMapOf<LocalDateTime, Float>()

        actuals.forEach { obs ->
            val obsTime = java.time.Instant.ofEpochMilli(obs.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            
            if (!obsTime.isBefore(startHour) && !obsTime.isAfter(endHour) && obsTime.isBefore(now)) {
                allTimes.add(obsTime)
                actualMap[obsTime] = obs.temperature
            }
        }

        val sortedTimes = allTimes.sorted()

        for (time in sortedTimes) {
            val isTopHour = time.minute == 0 && time.second == 0
            val isPast = time.isBefore(now)
            val actualTemp = actualMap[time]

            if (isTopHour) {
                val topHourData = hours.find { it.dateTime == time }
                if (topHourData != null) {
                    finalHours.add(topHourData.copy(
                        isActual = isPast,
                        actualTemperature = actualTemp
                    ))
                }
            } else {
                val prevTopHour = hours.lastOrNull { !it.dateTime.isAfter(time) }
                val nextTopHour = hours.firstOrNull { it.dateTime.isAfter(time) }
                
                val forecastTemp = if (prevTopHour != null && nextTopHour != null) {
                    val totalSecs = java.time.Duration.between(prevTopHour.dateTime, nextTopHour.dateTime).seconds
                    val elapsedSecs = java.time.Duration.between(prevTopHour.dateTime, time).seconds
                    val fraction = elapsedSecs.toFloat() / totalSecs.toFloat()
                    prevTopHour.temperature + (nextTopHour.temperature - prevTopHour.temperature) * fraction
                } else {
                    prevTopHour?.temperature ?: nextTopHour?.temperature ?: 0f
                }

                finalHours.add(
                    TemperatureGraphRenderer.HourData(
                        dateTime = time,
                        temperature = forecastTemp,
                        label = formatHourLabel(time),
                        iconRes = null,
                        isNight = SunPositionUtils.isNight(time, lat, lon),
                        isSunny = false,
                        isRainy = false,
                        isMixed = false,
                        isCurrentHour = false,
                        showLabel = false,
                        isActual = true,
                        actualTemperature = actualTemp
                    )
                )
            }
        }

        var lastActual: Float? = null
        for (i in finalHours.indices) {
            if (finalHours[i].isActual && finalHours[i].actualTemperature != null) {
                lastActual = finalHours[i].actualTemperature
            } else if (finalHours[i].dateTime.isBefore(now)) {
                finalHours[i] = finalHours[i].copy(isActual = true, actualTemperature = lastActual)
            }
        }

        return finalHours
    }"""

if old_block in content:
    content = content.replace(old_block, new_block)
    with open(path, 'w') as f:
        f.write(content)
    print("Replaced buildHourDataList successfully.")
else:
    print("Could not find the exact old_block to replace. Attempting regex...")
    # fallback
