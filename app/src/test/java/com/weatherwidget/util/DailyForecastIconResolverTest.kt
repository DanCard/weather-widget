package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
    fun `nws chance light rain token maps to mixed slight chance rain icon at 25 percent`() {
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

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws slight chance light rain token maps to slight chance rain icon at 39 percent`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Patchy Fog then Slight Chance Light Rain",
                nativeDailyIconToken = "Patchy Fog then Slight Chance Light Rain",
                precipProbability = 39,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays slight chance rain at 55 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 55,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token becomes chance rain at 60 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 60,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays slight chance at 35 percent daily pop`() {
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

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays slight chance at 49 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 49,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays slight chance at 59 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 59,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token stays chance rain at 79 percent daily pop`() {
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

        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, icon)
    }

    @Test
    fun `nws chance light rain token becomes heavy rain at 80 percent daily pop`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 80,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_rain, icon)
    }

    @Test
    fun `nws stronger rain token maps to slight chance icon at low pop`() {
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

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws dense fog token maps to dense fog icon`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Dense Fog",
                nativeDailyIconToken = "Dense Fog",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_fog_dense, icon)
    }

    @Test
    fun `weather api mist maps to light fog`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.WEATHER_API.id,
                condition = "Mist",
                nativeDailyIconToken = "//cdn.weatherapi.com/weather/64x64/day/1030.png",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_fog_light, icon)
    }

    @Test
    fun `open meteo 45 maps to light fog`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.OPEN_METEO.id,
                condition = "Light Fog",
                nativeDailyIconToken = "45",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_fog_light, icon)
    }

    @Test
    fun `open meteo 48 maps to dense fog`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.OPEN_METEO.id,
                condition = "Dense Fog",
                nativeDailyIconToken = "48",
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_fog_dense, icon)
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

    @Test
    fun `distant day with 21 percent rain shows slight chance rain icon`() {
        val distant = today.plusDays(4)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 21,
            ),
            targetDate = distant,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `near term day with 21 percent rain shows slight chance rain icon`() {
        val nearTerm = today.plusDays(1)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 21,
            ),
            targetDate = nearTerm,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `distant day with 51 percent rain shows slight chance rain icon`() {
        val distant = today.plusDays(5)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 51,
            ),
            targetDate = distant,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `distant day with 61 percent rain shows chance rain icon`() {
        val distant = today.plusDays(5)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 61,
            ),
            targetDate = distant,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, icon)
    }

    @Test
    fun `distant day with 15 percent rain shows cloud icon via trace threshold`() {
        val distant = today.plusDays(7)
        // day 7: threshold = 4*7 + 1 = 29
        // both 15 < 29 -> suppressed
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 15,
            ),
            targetDate = distant,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
            dayPrecipProbability = 15,
            nightPrecipProbability = 15,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy, icon)
    }

    @Test
    fun `today with 21 percent rain shows slight chance rain icon`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 21,
            ),
            targetDate = today,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws thunderstorms token no longer bypasses moderate probability icon downgrade`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Thunderstorms",
                nativeDailyIconToken = "Thunderstorms",
                precipProbability = 58,
            ),
            targetDate = today.plusDays(4),
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `nws snow token no longer bypasses moderate probability icon downgrade`() {
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Snow",
                nativeDailyIconToken = "Snow",
                precipProbability = 58,
            ),
            targetDate = today.plusDays(4),
            now = now,
            latitude = 37.42,
            longitude = -122.08,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    // --- Night threshold formula tests ---

    @Test
    fun `day threshold at day 0 is 1`() {
        assertEquals(1, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(0))
    }

    @Test
    fun `day threshold at day 3 is 13`() {
        assertEquals(13, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(3))
    }

    @Test
    fun `day threshold at day 6 is 25`() {
        assertEquals(25, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(6))
    }

    @Test
    fun `day threshold at day 7 is 29`() {
        assertEquals(29, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(7))
    }

    @Test
    fun `day threshold is 41 for day 10 plus`() {
        assertEquals(41, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(10))
        assertEquals(401, DailyForecastIconResolver.getMinimumPrecipProbabilityDay(100))
    }

    @Test
    fun `night threshold at day 0 is 1`() {
        assertEquals(1, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(0))
    }

    @Test
    fun `night threshold at day 1 is 5`() {
        assertEquals(5, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(1))
    }

    @Test
    fun `night threshold at day 3 is 13`() {
        assertEquals(13, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(3))
    }

    @Test
    fun `night threshold at day 6 is 25`() {
        assertEquals(25, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(6))
    }

    @Test
    fun `night threshold is 41 for day 10 plus`() {
        assertEquals(41, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(10))
        assertEquals(401, DailyForecastIconResolver.getMinimumPrecipProbabilityNight(100))
    }

    // --- OR logic: suppression tests with shouldSuppressRainIcon ---

    @Test
    fun `shouldSuppressRainIcon both below threshold suppresses`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 15,
            daysFromToday = 4,
            isNight = false,
            dayPrecipProbability = 15,
            nightPrecipProbability = 10,
        )
        // day 4: threshold=17
        // day 15 < 17 (suppress), night 10 < 17 (suppress) → both suppress → true
        assertEquals(true, result)
    }

    @Test
    fun `shouldSuppressRainIcon day above threshold night below does not suppress`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 30,
            daysFromToday = 4,
            isNight = false,
            dayPrecipProbability = 30,
            nightPrecipProbability = 10,
        )
        // day 4: day threshold=19, night threshold=19
        // day 30 >= 19 (show), night 10 < 19 (suppress) → OR → false (not suppressed)
        assertEquals(false, result)
    }

    @Test
    fun `shouldSuppressRainIcon night above threshold day below does not suppress`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 20,
            daysFromToday = 4,
            isNight = false,
            dayPrecipProbability = 15,
            nightPrecipProbability = 48,
        )
        // day 4: day threshold=19, night threshold=19
        // day 15 < 19 (suppress), night 48 >= 19 (show) → OR → false (not suppressed)
        assertEquals(false, result)
    }

    @Test
    fun `shouldSuppressRainIcon both above threshold does not suppress`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 30,
            daysFromToday = 2,
            isNight = false,
            dayPrecipProbability = 30,
            nightPrecipProbability = 35,
        )
        // day 2: day threshold=14, night threshold=14
        // both pass → not suppressed
        assertEquals(false, result)
    }

    @Test
    fun `shouldSuppressRainIcon null day and night precip falls back to daily`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 20,
            daysFromToday = 4,
            isNight = false,
            dayPrecipProbability = null,
            nightPrecipProbability = null,
        )
        // day 4: day threshold=19, night threshold=19
        // both fall back to daily=20 → both 20 >= 19 → OR → not suppressed
        assertEquals(false, result)
    }

    @Test
    fun `shouldSuppressRainIcon null daily with day and night both below suppresses`() {
        val icon = R.drawable.ic_weather_partly_cloudy_slight_chance_rain
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 5,
            daysFromToday = 6,
            isNight = false,
            dayPrecipProbability = 10,
            nightPrecipProbability = 5,
        )
        // day 6: day threshold=24, night threshold=24
        // day 10 < 24, night 5 < 24 → both suppress → true
        assertEquals(true, result)
    }

    @Test
    fun `shouldSuppressRainIcon non rain indicator returns false`() {
        val icon = R.drawable.ic_weather_partly_cloudy
        val result = DailyForecastIconResolver.shouldSuppressRainIcon(
            icon = icon,
            dailyPrecipProbability = 5,
            daysFromToday = 6,
            isNight = false,
            dayPrecipProbability = 10,
            nightPrecipProbability = 5,
        )
        assertEquals(false, result)
    }

    // --- OR logic: resolveIcon integration tests with day/night precip ---

    @Test
    fun `distant day rain icon suppressed when both day and night precip below thresholds`() {
        val day4 = today.plusDays(4)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 15,
            ),
            targetDate = day4,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
            dayPrecipProbability = 15,
            nightPrecipProbability = 10,
        )
        // day 4: threshold=17
        // both below → suppressed → cloud icon
        assertEquals(R.drawable.ic_weather_partly_cloudy, icon)
    }

    @Test
    fun `distant day rain icon shown when day precip above threshold`() {
        val day4 = today.plusDays(4)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 30,
            ),
            targetDate = day4,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
            dayPrecipProbability = 30,
            nightPrecipProbability = 10,
        )
        // day 4: day threshold=19, night threshold=19
        // day 30 >= 19 → show (even though night 10 < 19)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    @Test
    fun `distant day rain icon shown when night precip above threshold even though day below`() {
        val day4 = today.plusDays(4)
        val icon = DailyForecastIconResolver.resolveIcon(
            weather = forecast(
                source = WeatherSource.NWS.id,
                condition = "Chance Light Rain",
                nativeDailyIconToken = "Chance Light Rain",
                precipProbability = 20,
            ),
            targetDate = day4,
            now = now,
            latitude = 37.42,
            longitude = -122.08,
            dayPrecipProbability = 15,
            nightPrecipProbability = 48,
        )
        // day 4: day threshold=19, night threshold=19
        // day 15 < 19 (suppress), night 48 >= 19 (show) → OR → show
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, icon)
    }

    // --- calculateDayNightPrecipProbabilities tests ---

    @Test
    fun `calculateDayNightPrecipProbabilities splits hours into day and night`() {
        val zoneId = ZoneId.systemDefault()
        val targetDate = LocalDate.of(2030, 6, 15)
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val hourlyForecasts = listOf(
            hourlyForecast(dateTime = startMs + 10 * 3600_000L, precip = 20, source = WeatherSource.OPEN_METEO.id),
            hourlyForecast(dateTime = startMs + 14 * 3600_000L, precip = 40, source = WeatherSource.OPEN_METEO.id),
            hourlyForecast(dateTime = startMs + 22 * 3600_000L, precip = 60, source = WeatherSource.OPEN_METEO.id),
            hourlyForecast(dateTime = startMs + 3 * 3600_000L, precip = 30, source = WeatherSource.OPEN_METEO.id),
        )

        val result = DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
            hourlyForecasts = hourlyForecasts,
            targetDate = targetDate,
            now = LocalDateTime.of(2030, 6, 15, 12, 0),
            latitude = 37.42,
            longitude = -122.08,
            displaySource = WeatherSource.OPEN_METEO,
        )

        // At lat=37.42 in June, sunrise ~5.8h, sunset ~19.5h
        // Hour 3 (3am) → night, hour 10 (10am) → day, hour 14 (2pm) → day, hour 22 (10pm) → night
        assertNotNull(result.dayMax)
        assertNotNull(result.nightMax)
        // day max = max(20, 40) = 40
        assertEquals(40, result.dayMax!!)
        // night max = max(30, 60) = 60
        assertEquals(60, result.nightMax!!)
    }

    @Test
    fun `calculateDayNightPrecipProbabilities returns nulls for empty hourly data`() {
        val result = DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
            hourlyForecasts = emptyList(),
            targetDate = LocalDate.of(2030, 6, 15),
            now = LocalDateTime.of(2030, 6, 15, 12, 0),
            latitude = 37.42,
            longitude = -122.08,
            displaySource = WeatherSource.OPEN_METEO,
        )

        assertNull(result.dayMax)
        assertNull(result.nightMax)
    }

    @Test
    fun `calculateDayNightPrecipProbabilities filters by display source`() {
        val zoneId = ZoneId.systemDefault()
        val targetDate = LocalDate.of(2030, 6, 15)
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val hourlyForecasts = listOf(
            hourlyForecast(dateTime = startMs + 12 * 3600_000L, precip = 50, source = WeatherSource.OPEN_METEO.id),
            hourlyForecast(dateTime = startMs + 12 * 3600_000L, precip = 10, source = WeatherSource.NWS.id),
        )

        val result = DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
            hourlyForecasts = hourlyForecasts,
            targetDate = targetDate,
            now = LocalDateTime.of(2030, 6, 15, 12, 0),
            latitude = 37.42,
            longitude = -122.08,
            displaySource = WeatherSource.OPEN_METEO,
        )

        // Should use OPEN_METEO source data (precip=50), not NWS (precip=10)
        assertEquals(50, result.dayMax!!)
    }

    private fun hourlyForecast(dateTime: Long, precip: Int, source: String): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = 70f,
            condition = "Rain",
            source = source,
            precipProbability = precip,
            fetchedAt = 1L,
        )
    }
}
