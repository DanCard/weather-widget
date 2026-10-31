package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.HeaderContent.View
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun dailyHeaderAppliesNightScale() {
        val now = LocalDateTime.of(2026, 6, 1, 22, 0)
        val c = build(View.DAILY, listOf(row(now, 90), row(now.plusHours(1), 90)), now)
        assertEquals(90, c.precipProbability)
        assertEquals(DailyRainLabels.NIGHT_SCALE, c.precipFontScale!!, 1e-6f)
    }

    @Test
    fun hourlyHeaderHasNoNightScale() {
        val now = LocalDateTime.of(2026, 6, 1, 22, 0)
        val c = build(View.TEMPERATURE, listOf(row(now, 90), row(now.plusHours(1), 90)), now)
        assertEquals(1f, c.precipFontScale!!, 1e-6f)
    }

    @Test
    fun zeroRainIsHiddenExceptInThePrecipView() {
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        assertNull(build(View.DAILY, emptyList(), now, fallbackDailyPrecip = 0).precipText)
        assertNull(build(View.CLOUD_COVER, emptyList(), now, fallbackDailyPrecip = 0).precipText)
        assertEquals("0%", build(View.PRECIPITATION, emptyList(), now, fallbackDailyPrecip = 0).precipText)
        assertEquals(0, build(View.DAILY, emptyList(), now, fallbackDailyPrecip = 0).computedPrecipProbability)
    }

    @Test
    fun hourlyRainFollowsThePannedHour() {
        // Dry now, rain tomorrow afternoon: panned there, the hourly header shows tomorrow's chance.
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        val tomorrow = now.plusDays(1)
        val hourly = listOf(row(now, 0), row(now.plusHours(1), 0), row(tomorrow, 80), row(tomorrow.plusHours(1), 80))
        assertNull(build(View.TEMPERATURE, hourly, now).precipText)
        assertEquals("80%", build(View.TEMPERATURE, hourly, now, centerTime = tomorrow).precipText)
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
            val labels = HeaderContent.precipLabels(view, hourly, listOf("NWS", "OPEN_METEO", "SILURIAN"), now, now, lat, lon)
            for (source in listOf("NWS", "OPEN_METEO")) {
                val shown = HeaderContent.build(
                    view = view, hourly = hourly, displaySourceId = source, dailyFallback = null,
                    fallbackDailyPrecip = null, currentTemp = 62.7f, isStaleEstimate = false, numColumns = 5,
                    deltaFromYesterday = 2.9f, now = now, centerTime = now, lat = lat, lon = lon,
                    useCelsius = false, widthDp = 400,
                )
                assertEquals("$view $source", HeaderContent.PrecipLabel(shown.precipText!!, shown.precipFontScale!!), labels[source])
            }
            assertNull("$view dry source", labels["SILURIAN"])
        }
    }
}
