package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

@Category(ShortDuration::class)
class DailyForecastIconResolverTest {
    private val now = LocalDateTime.of(2030, 6, 15, 12, 0)
    private val today = now.toLocalDate()

    @Test
    fun `open meteo native weather code is preferred over condition`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.OPEN_METEO.id,
                condition = "Rain",
                nativeDailyIconToken = "2",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy, icon)
    }

    @Test
    fun `visual crossing icon token maps directly`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.VISUAL_CROSSING.id,
                condition = "Rain, Partially cloudy",
                nativeDailyIconToken = "partly-cloudy-day",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy, icon)
    }

    @Test
    fun `open weather map icon code maps directly`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.OPEN_WEATHER_MAP.id,
                condition = "Rain",
                nativeDailyIconToken = "01d",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_clear, icon)
    }

    @Test
    fun `weather api icon path maps directly`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.WEATHER_API.id,
                condition = "Patchy rain nearby",
                nativeDailyIconToken = "//cdn.weatherapi.com/weather/64x64/day/1003.png",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy, icon)
    }

    @Test
    fun `falls back to condition mapping when native token is missing`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = null,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_rain, icon)
    }

    @Test
    fun `nws chance light rain token maps to mixed chance rain icon`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 25,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, icon)
    }

    @Test
    fun `nws slight chance light rain token maps to mixed chance rain icon`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Patchy Fog then Slight Chance Light Rain",
                nativeDailyIconToken = "Patchy Fog then Slight Chance Light Rain",
                precipProbability = 34,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays rainy at 35 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 35,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays rainy at high daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 79,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_rain, icon)
    }

    @Test
    fun `nws stronger rain token stays rainy`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Rain",
                nativeDailyIconToken = "Rain",
                precipProbability = 25,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_rain, icon)
    }

    private fun forecast(
        source: String,
        condition: String,
        nativeDailyIconToken: String?,
        precipProbability: Int? = null,
    ) = ForecastEntity(
        targetDate = LocalDate.of(2030, 6, 15).toEpochDay(),
        forecastDate = LocalDate.of(2030, 6, 15).toEpochDay(),
        locationLat = 37.42,
        locationLon = -122.08,
        locationName = "Test",
        highTemp = 70f,
        lowTemp = 50f,
        condition = condition,
        nativeDailyIconToken = nativeDailyIconToken,
        source = source,
        precipProbability = precipProbability,
        fetchedAt = 1L,
    )
}
