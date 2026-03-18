import re

path = 'app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt'
with open(path, 'r') as f:
    content = f.read()

if 'getObservationsInRange' not in content:
    old = """        suspend fun getHourlyActuals(
            startDateTime: String,
            endDateTime: String,
            source: String,
            latitude: Double,
            longitude: Double,
        ): List<HourlyActualEntity> = forecastRepository.getHourlyActuals(startDateTime, endDateTime, source, latitude, longitude)"""
    new = """        suspend fun getHourlyActuals(
            startDateTime: String,
            endDateTime: String,
            source: String,
            latitude: Double,
            longitude: Double,
        ): List<HourlyActualEntity> = forecastRepository.getHourlyActuals(startDateTime, endDateTime, source, latitude, longitude)

        suspend fun getObservationsInRange(
            startTimestamp: Long,
            endTimestamp: Long,
            latitude: Double,
            longitude: Double,
        ): List<com.weatherwidget.data.local.ObservationEntity> = forecastRepository.getObservationsInRange(startTimestamp, endTimestamp, latitude, longitude)"""
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print("Updated WeatherRepository.kt")
else:
    print("WeatherRepository.kt already has getObservationsInRange")

path = 'app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt'
with open(path, 'r') as f:
    content = f.read()

if 'getObservationsInRange' not in content:
    old = """        suspend fun getHourlyActuals(
            startDateTime: String,
            endDateTime: String,
            source: String,
            latitude: Double,
            longitude: Double,
        ): List<HourlyActualEntity> = hourlyActualDao.getActualsInRange(startDateTime, endDateTime, source, latitude, longitude)"""
    new = """        suspend fun getHourlyActuals(
            startDateTime: String,
            endDateTime: String,
            source: String,
            latitude: Double,
            longitude: Double,
        ): List<HourlyActualEntity> = hourlyActualDao.getActualsInRange(startDateTime, endDateTime, source, latitude, longitude)

        suspend fun getObservationsInRange(
            startTimestamp: Long,
            endTimestamp: Long,
            latitude: Double,
            longitude: Double,
        ): List<com.weatherwidget.data.local.ObservationEntity> = observationDao.getObservationsInRange(startTimestamp, endTimestamp, latitude, longitude)"""
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print("Updated ForecastRepository.kt")
else:
    print("ForecastRepository.kt already has getObservationsInRange")
