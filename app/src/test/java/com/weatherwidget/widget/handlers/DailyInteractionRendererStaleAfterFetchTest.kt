package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.repository.RoomForecastPersistHost
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.NavigationUtils
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The 2026-10-29 incident, end to end on Room: a widget tap fills [WidgetInteractionCache], then the
 * History screen's NWS refresh stores Saturday's new rain chance through the shared
 * [ForecastPersistRun]. The UI-only repaint that follows must read the stored row, not the cached one.
 * Before the stored-data version, the cache served Sat 12% for up to 60 s after 21% was stored
 * (plans/261029-stored-data-version-invalidates-interaction-cache.md).
 */
@Category(LongDuration::class)
class DailyInteractionRendererStaleAfterFetchTest : RobolectricTest() {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 29)
    private val saturday = today.plusDays(2)
    private val window = NavigationUtils.DailyLoadWindow(historyDays = 2, forecastDays = 9)

    @Before
    fun setUp() {
        WidgetInteractionCache.clear()
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        WidgetInteractionCache.clear()
        db.close()
    }

    private suspend fun persistNws(saturdayRain: Int, nowMs: Long) {
        val host = RoomForecastPersistHost(
            db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.appLogDao(), "NWS", lat, lon,
        ) { null }
        val daily = (0L..7L).map { offset ->
            val date = today.plusDays(offset)
            DailyForecast(
                date = date.toString(),
                highTemp = 65f,
                lowTemp = 50f,
                condition = "Mostly Cloudy",
                precipProbability = if (date == saturday) saturdayRain else 5,
                source = "NWS",
            )
        }
        ForecastPersistRun(host).persist(
            ForecastPersistRun.Fetch(hourly = emptyList(), daily = daily, keep = null),
            nowMs,
            zone,
        )
    }

    private suspend fun load() = DailyInteractionRenderer.loadData(
        context = context,
        database = db,
        lat = lat,
        lon = lon,
        today = today,
        range = DailyInteractionRenderer.rangeFor(today, window),
    )

    /** What the newest NWS row for Saturday says — the row the daily view draws. */
    private fun DailyInteractionRenderer.DailyRenderData.saturdayRain(): Int? =
        weatherListRaw
            .filter { it.source == "NWS" && it.targetDate == saturday.toEpochDay() * 86_400_000L }
            .maxByOrNull { it.fetchedAt }
            ?.precipProbability

    @Test
    fun `a forecast stored after a cached load is drawn by the next load within the TTL`() = runTest {
        val morning = today.atTime(9, 9).atZone(zone).toInstant().toEpochMilli()
        persistNws(saturdayRain = 12, nowMs = morning)
        assertEquals("the tap's load caches the morning row", 12, load().saturdayRain())

        persistNws(saturdayRain = 21, nowMs = morning + 3 * 3_600_000L)

        assertEquals("the repaint after the History refresh draws the stored row", 21, load().saturdayRain())
    }
}
