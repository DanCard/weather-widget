package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.HeaderContent.View
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/** The header content both platforms draw (`plans/261021-header-shared-content-audit.md`). */
@Category(ShortDuration::class)
class HeaderContentTest {
    private val zone = ZoneId.systemDefault()
    private val lat = 37.422
    private val lon = -122.084

    private fun row(time: LocalDateTime, precip: Int?, condition: String = "Rain", cloud: Int? = 90, source: String = "NWS") =
        HourlyForecast(
            dateTime = time.atZone(zone).toInstant().toEpochMilli(),
            temperature = 60f,
            condition = condition,
            precipProbability = precip,
            cloudCover = cloud,
            source = source,
            fetchedAt = 1L,
            locationLat = lat,
            locationLon = lon,
        )

    private fun build(
        view: View,
        hourly: List<HourlyForecast>,
        now: LocalDateTime,
        centerTime: LocalDateTime = now,
        fallbackDailyPrecip: Int? = null,
        delta: Float? = 2.9f,
        rainNow: com.weatherwidget.shared.observations.RainNow.Level? = null,
        // These cases were written for the 6 h window; the default is 1 h since 2026-11-20.
        rainLookaheadHours: Int = 6,
    ) = HeaderContent.build(
        view = view,
        hourly = hourly,
        displaySourceId = "NWS",
        dailyFallback = null,
        fallbackDailyPrecip = fallbackDailyPrecip,
        currentTemp = 62.7f,
        isStaleEstimate = false,
        numColumns = 5,
        deltaFromYesterday = delta,
        now = now,
        centerTime = centerTime,
        lat = lat,
        lon = lon,
        useCelsius = false,
        widthDp = 400,
        rainNow = rainNow,
        rainLookaheadHours = rainLookaheadHours,
    )

    @Test
    fun labelSourceNamesTheLabelOnly() {
        val now = LocalDateTime.of(2026, 6, 1, 10, 0)
        fun content(labelSourceId: String) = HeaderContent.build(
            view = View.DAILY,
            hourly = listOf(10, 11, 12, 13, 14).map { row(now.withHour(it), precip = 0, source = "NWS") },
            displaySourceId = "NWS",
            dailyFallback = null,
            fallbackDailyPrecip = 40,
            currentTemp = 62.7f,
            isStaleEstimate = false,
            numColumns = 5,
            deltaFromYesterday = 2.9f,
            now = now,
            centerTime = now,
            lat = lat,
            lon = lon,
            useCelsius = false,
            widthDp = 400,
            rainNow = null,
            labelSourceId = labelSourceId,
        )

        val same = content("NWS")
        val other = content("OPEN_METEO")

        assertEquals(same.copy(sourceLabel = "Meteo"), other)
        assertEquals(
            "source=NWS view=DAILY icon=${other.iconName} temp=${other.tempText} delta=${other.deltaText} precip=null label=Meteo",
            HeaderContent.logLine("NWS", View.DAILY, other),
        )
    }

    /** 2026-10-31: NWS 8% fell after sunset, Silurian 5% before it, so the 5% drew bigger. */
    @Test
    fun higherChanceDrawsLargerWhicheverSideOfSunsetItFalls() {
        val now = LocalDateTime.of(2026, 10, 31, 17, 13)
        fun evening(vararg precip: Int) = precip.mapIndexed { i, p -> row(now.withMinute(0).plusHours(i.toLong()), p) }
        val mostlyAfterSunset = build(View.DAILY, evening(7, 6, 8, 7, 7, 7, 2), now)
        val mostlyBeforeSunset = build(View.DAILY, evening(0, 5, 0, 0, 0, 0, 0), now)

        assertEquals("8%", mostlyAfterSunset.precipText)
        assertEquals("5%", mostlyBeforeSunset.precipText)
        assertTrue(mostlyAfterSunset.precipFontScale!! > mostlyBeforeSunset.precipFontScale!!)
    }

    @Test
    fun rainScaleIsTheSameInEveryView() {
        val now = LocalDateTime.of(2026, 6, 1, 22, 0)
        val hourly = listOf(row(now, 90), row(now.plusHours(1), 90))
        assertEquals(build(View.TEMPERATURE, hourly, now).precipFontScale, build(View.DAILY, hourly, now).precipFontScale)
    }

    @Test
    fun zeroRainIsHiddenInEveryView() {
        // Desktop rain-chance view, 2026-11-04: "Should print nothing instead of 0%".
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        View.entries.forEach { view ->
            val content = build(view, emptyList(), now, fallbackDailyPrecip = 0)
            println("zero rain view=$view precipText=${content.precipText}")
            assertNull("$view", content.precipText)
            assertNull("$view", content.precipProbability)
        }
        assertEquals(0, build(View.DAILY, emptyList(), now, fallbackDailyPrecip = 0).computedPrecipProbability)
    }

    @Test
    fun rainIsTheDailyViewsValueInEveryViewAndPan() {
        // plans/261119-header-rain-constant-across-views.md. 30% within 6 h of now, 80% tomorrow
        // afternoon: panned to tomorrow, the old rule showed 80% in the hourly views; now every view
        // shows the daily view's 30% whatever the pan.
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        val tomorrow = now.plusDays(1)
        val hourly = listOf(
            row(now, 0), row(now.plusHours(2), 30), row(now.plusHours(3), 0),
            row(tomorrow, 80), row(tomorrow.plusHours(1), 80),
        )
        val daily = build(View.DAILY, hourly, now).precipText
        assertEquals("30%", daily)
        for (view in View.entries) {
            for (center in listOf(now, now.minusDays(2), tomorrow, now.plusDays(5))) {
                val shown = build(view, hourly, now, centerTime = center).precipText
                println("view=$view center=$center precip=$shown daily=$daily")
                assertEquals("$view panned to $center", daily, shown)
            }
        }
    }

    @Test
    fun precipHourlyIsWhatTheRainReads() {
        // Android hands the now-centred rows as precipHourly and its panned graph rows as hourly:
        // the % comes from precipHourly only.
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        val graphRows = listOf(row(now.plusDays(3), 90), row(now.plusDays(3).plusHours(1), 90))
        val nowRows = listOf(row(now, 0), row(now.plusHours(1), 20), row(now.plusHours(2), 0))
        val content = HeaderContent.build(
            view = View.PRECIPITATION, hourly = graphRows, precipHourly = nowRows, displaySourceId = "NWS",
            dailyFallback = null, fallbackDailyPrecip = 55, currentTemp = 62.7f, isStaleEstimate = false,
            numColumns = 5, deltaFromYesterday = null, now = now, centerTime = now.plusDays(3), lat = lat,
            lon = lon, useCelsius = false, widthDp = 400, rainNow = null,
        )
        assertEquals("20%", content.precipText)
    }

    @Test
    fun nowRowsWindowCoversTheCurrentTempWindowAndTheLongestRainLookahead() {
        val now = LocalDateTime.of(2026, 11, 18, 14, 10)
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val window = HeaderContent.nowRowsWindowMs(nowMs, zone)
        val resolution = com.weatherwidget.widget.CurrentTemperatureResolver.resolutionWindowMs(nowMs, zone)
        val rain = HeaderContent.precipReserveWindowMs(
            now, zone, com.weatherwidget.shared.settings.DisplaySettings.HEADER_RAIN_LOOKAHEAD.max,
        )
        println("nowRows=$window resolution=$resolution rain=$rain")
        assertEquals(resolution.first, window.first)
        assertEquals(rain.last, window.last)
        assertTrue("reaches past the current-temp window", window.last > resolution.last)
    }

    @Test
    fun deltaOnlyInDailyAndTemperatureViews() {
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        assertEquals("+2.9", build(View.DAILY, emptyList(), now).deltaText)
        assertEquals("+2.9", build(View.TEMPERATURE, emptyList(), now).deltaText)
        assertNull(build(View.PRECIPITATION, emptyList(), now).deltaText)
        assertNull(build(View.CLOUD_COVER, emptyList(), now).deltaText)
        assertNull(build(View.DAILY, emptyList(), now, delta = 0.05f).deltaText)
    }

    @Test
    fun hourlyIconNeverBorrowsAnotherSourcesRow() {
        // Android's old hourly lookup fell back to any source's row for the hour.
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        val hourly = listOf(row(now, 0, condition = "Clear", cloud = 0, source = "OPEN_METEO"))
        for (view in listOf(View.TEMPERATURE, View.PRECIPITATION, View.CLOUD_COVER)) {
            assertNull("$view draws no icon", build(view, hourly, now).iconName)
        }
    }

    @Test
    fun dailyViewWithNothingKeepsTheUnknownIcon() {
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        assertEquals(WeatherConditionResolver.IC_UNKNOWN, build(View.DAILY, emptyList(), now).iconName)
    }

    @Test
    fun tempIsOneDecimal() {
        assertEquals(String.format("%.1f°", 62.7f), build(View.DAILY, emptyList(), LocalDateTime.of(2026, 6, 1, 12, 0)).tempText)
    }

    @Test
    fun precipLabelsAreWhatEachSourceWouldShow() {
        // Desktop reserves the widest of these (user, 2026-10-26): each must be exactly the label
        // build gives that source, and a dry source none.
        val now = LocalDateTime.of(2026, 6, 1, 13, 0)
        val hourly = (0..6).flatMap { h ->
            val t = now.plusHours(h.toLong())
            listOf(row(t, 5, source = "NWS"), row(t, 70, source = "OPEN_METEO"), row(t, 0, condition = "Clear", source = "SILURIAN"))
        }
        for (view in listOf(View.DAILY, View.TEMPERATURE)) {
            val labels = HeaderContent.precipLabels(hourly, listOf("NWS", "OPEN_METEO", "SILURIAN"), now)
            for (source in listOf("NWS", "OPEN_METEO")) {
                val shown = HeaderContent.build(
                    view = view, hourly = hourly, displaySourceId = source, dailyFallback = null,
                    fallbackDailyPrecip = null, currentTemp = 62.7f, isStaleEstimate = false, numColumns = 5,
                    deltaFromYesterday = 2.9f, now = now, centerTime = now, lat = lat, lon = lon,
                    useCelsius = false, widthDp = 400, rainNow = null,
                )
                assertEquals("$view $source", HeaderContent.PrecipLabel(shown.precipText!!, shown.precipFontScale!!), labels[source])
            }
            assertNull("$view dry source", labels["SILURIAN"])
        }
    }

    /** plans/261115-header-rain-now-dots.md: rain falling now draws blue dots in every view, over a clear forecast. */
    @Test
    fun rainNowDotsReplaceTheForecastIconInEveryView() {
        val now = LocalDateTime.of(2026, 6, 1, 13, 0)
        val hourly = (12..20).map { row(now.withHour(it), precip = 0, condition = "Clear", cloud = 0, source = "NWS") }
        val levels = mapOf(
            com.weatherwidget.shared.observations.RainNow.Level.SPRINKLING to WeatherConditionResolver.IC_RAIN_NOW_LIGHT,
            com.weatherwidget.shared.observations.RainNow.Level.RAINING to WeatherConditionResolver.IC_RAIN_NOW,
            com.weatherwidget.shared.observations.RainNow.Level.HEAVY to WeatherConditionResolver.IC_RAIN_NOW_HEAVY,
        )
        for (view in View.entries) {
            val forecastIcon = build(view, hourly, now).iconName
            println("view=$view forecastIcon=$forecastIcon")
            assertEquals("$view without rain keeps the forecast icon", WeatherConditionResolver.IC_CLEAR, forecastIcon)
            for ((level, icon) in levels) {
                assertEquals("$view $level", icon, build(view, hourly, now, rainNow = level).iconName)
            }
        }
    }

    @Test
    fun `precipReserveWindowMs spans 1h before now to lookahead+1h after now`() {
        val now = LocalDateTime.of(2026, 11, 18, 14, 0)
        val window = HeaderContent.precipReserveWindowMs(now, zone, lookaheadHours = 6)
        assertEquals(now.minusHours(1).atZone(zone).toInstant().toEpochMilli(), window.first)
        assertEquals(now.plusHours(7).atZone(zone).toInstant().toEpochMilli(), window.last)

        val wideWindow = HeaderContent.precipReserveWindowMs(now, zone, lookaheadHours = 8)
        assertEquals(now.minusHours(1).atZone(zone).toInstant().toEpochMilli(), wideWindow.first)
        assertEquals(now.plusHours(9).atZone(zone).toInstant().toEpochMilli(), wideWindow.last)
    }

    @Test
    fun `rainLookaheadHours controls both daily and hourly headers from 1h to 8h`() {
        val now = LocalDateTime.of(2026, 11, 18, 10, 0)
        // Dry for 0..1h, 40% at +3h, 85% at +7h (outside 6h, inside 8h).
        val hourly = listOf(
            row(now.plusHours(0), precip = 0, source = "NWS"),
            row(now.plusHours(1), precip = 0, source = "NWS"),
            row(now.plusHours(2), precip = 0, source = "NWS"),
            row(now.plusHours(3), precip = 40, source = "NWS"),
            row(now.plusHours(4), precip = 0, source = "NWS"),
            row(now.plusHours(5), precip = 0, source = "NWS"),
            row(now.plusHours(6), precip = 0, source = "NWS"),
            row(now.plusHours(7), precip = 85, source = "NWS"),
            row(now.plusHours(8), precip = 0, source = "NWS"),
        )
        for (view in listOf(View.DAILY, View.TEMPERATURE, View.PRECIPITATION, View.CLOUD_COVER)) {
            fun precipFor(hours: Int) = HeaderContent.build(
                view = view,
                hourly = hourly,
                displaySourceId = "NWS",
                dailyFallback = null,
                fallbackDailyPrecip = null,
                currentTemp = 60f,
                isStaleEstimate = false,
                numColumns = 5,
                deltaFromYesterday = null,
                now = now,
                centerTime = now,
                lat = 37.42,
                lon = -122.09,
                useCelsius = false,
                widthDp = 300,
                rainNow = null,
                rainLookaheadHours = hours,
                zone = zone,
            ).precipProbability

            assertEquals("$view 1h should be hidden (0%)", null, precipFor(1))
            assertEquals("$view 6h should see +3h 40%", 40, precipFor(6))
            assertEquals("$view 8h should see +7h 85%", 85, precipFor(8))
        }
    }

    @Test
    fun rainNowFlagAndSharedHeaderTapRouting() {
        val now = LocalDateTime.of(2026, 11, 19, 12, 0)
        val hourly = listOf(row(now, precip = 40, source = "NWS"))

        val dry = build(View.DAILY, hourly, now, rainNow = null)
        assertEquals(false, dry.isRainingNow)

        val wet = build(View.DAILY, hourly, now, rainNow = com.weatherwidget.shared.observations.RainNow.Level.RAINING)
        assertEquals(true, wet.isRainingNow)

        for (mode in com.weatherwidget.widget.ViewMode.entries) {
            val expectedTemp = if (mode == com.weatherwidget.widget.ViewMode.DAILY) {
                com.weatherwidget.widget.ViewMode.TEMPERATURE
            } else {
                com.weatherwidget.widget.ViewMode.DAILY
            }
            val expectedPrecip = if (mode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
                com.weatherwidget.widget.ViewMode.DAILY
            } else {
                com.weatherwidget.widget.ViewMode.PRECIPITATION
            }
            assertEquals("temp from $mode", expectedTemp, HeaderTapRouting.target(HeaderTapRouting.Zone.TEMP_AND_DELTA, mode))
            assertEquals("precip from $mode", expectedPrecip, HeaderTapRouting.target(HeaderTapRouting.Zone.PRECIP, mode))
            assertEquals("dry icon from $mode", expectedTemp, HeaderTapRouting.target(HeaderTapRouting.Zone.ICON, mode, isRainingNow = false))
            assertEquals("wet icon from $mode", expectedPrecip, HeaderTapRouting.target(HeaderTapRouting.Zone.ICON, mode, isRainingNow = true))
        }
    }
}
