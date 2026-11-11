package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.fog.FogLevel
import com.weatherwidget.shared.fog.MorningFog
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate

/**
 * The fog columns on real SQLite (plans/261111-daily-bar-morning-fog.md): forecast fog round-trips
 * through the daily write, observed fog through `daily_history` and the blend's conditional update,
 * and a v37 database gains the columns without losing its rows.
 */
@Category(ShortDuration::class)
class DesktopFogStorageTest {
    private val path = Files.createTempFile("weather-fog-test", ".db")
    private val lat = 37.417
    private val lon = -122.089

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `forecast fog round-trips on the daily row`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val today = LocalDate.now()
        val fog = MorningFog(FogLevel.LIGHT, topTempF = 56.9f, visibilityMi = 0.06f)
        dao.upsertForecasts(
            lat, lon, "OPEN_METEO",
            listOf(
                DailyForecast(today.plusDays(1).toString(), 70.9f, 52.8f, "Light Fog", source = "OPEN_METEO")
                    .let { it.withHourlySummary(it.hourlySummary.copy(fog = fog)) },
                DailyForecast(today.plusDays(2).toString(), 71f, 52f, "Clear", source = "OPEN_METEO")
                    .let { it.withHourlySummary(it.hourlySummary.copy(fog = MorningFog.NONE)) },
            ),
        )
        val rows = dao.getDailyForecasts(lat, lon, "OPEN_METEO").associateBy { it.date }
        println("rows=${rows.mapValues { it.value.hourlySummary.fog }}")
        assertEquals(fog, rows.getValue(today.plusDays(1).toString()).hourlySummary.fog)
        assertEquals(MorningFog.NONE, rows.getValue(today.plusDays(2).toString()).hourlySummary.fog)
    }

    @Test
    fun `observed fog round-trips and the blend update writes it`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val date = LocalDate.of(2026, 11, 9).toEpochDay() * 86_400_000L
        val row = DailyHistory(date, "NWS", lat, lon, 69.8f, 49.8f, "Fog", updatedAt = 1_000L)
        dao.upsertDailyHistory(listOf(row))
        assertNull(dao.getExtremesInRange(date, date, lat, lon).single().observedFog)

        val dense = MorningFog(FogLevel.DENSE, topTempF = 59f, visibilityMi = 0.125f)
        assertEquals(1, dao.updateBlendIfUnchanged(row.withObservedFog(dense), newUpdatedAt = 2_000L, expectedUpdatedAt = 1_000L))
        val stored = dao.getExtremesInRange(date, date, lat, lon).single()
        assertEquals(dense, stored.observedFog)
        assertEquals(49.8f, stored.computedLowTemp)
    }

    @Test
    fun `a v37 database gains the fog columns and keeps its rows`() {
        DesktopWeatherDatabase(path).initialize()
        // Rewind to v37: drop the fog columns (rebuild without them) and set the old version.
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO daily_history (date, source, locationLat, locationLon, computedHighTemp, computedLowTemp, condition, updatedAt) " +
                        "VALUES (1794355200000, 'NWS', $lat, $lon, 70.0, 52.0, 'Clear', 1)",
                )
                DesktopWeatherDatabase.DAILY_HISTORY_FOG_COLUMNS.forEach { (c, _) -> st.execute("ALTER TABLE daily_history DROP COLUMN $c") }
                DesktopWeatherDatabase.FORECAST_FOG_COLUMNS.forEach { (c, _) -> st.execute("ALTER TABLE forecasts DROP COLUMN $c") }
                st.execute("ALTER TABLE hourly_forecasts DROP COLUMN ${DesktopWeatherDatabase.HOURLY_VISIBILITY_COLUMN}")
                st.execute("PRAGMA user_version = 37")
            }
        }

        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1)) }
                val columns = mutableSetOf<String>()
                st.executeQuery("PRAGMA table_info(forecasts)").use { while (it.next()) columns += it.getString("name") }
                st.executeQuery("PRAGMA table_info(daily_history)").use { while (it.next()) columns += it.getString("name") }
                st.executeQuery("PRAGMA table_info(hourly_forecasts)").use { while (it.next()) columns += "hourly." + it.getString("name") }
                assertTrue(columns.containsAll(listOf("fogLevel", "fogTopTemp", "fogVisibilityMi", "observedFogLevel", "observedFogTopTemp", "observedFogVisibilityMi", "hourly.visibilityMi")))
            }
        }
        val kept = dao.getExtremesInRange(1794355200000L, 1794355200000L, lat, lon).single()
        assertEquals(52f, kept.computedLowTemp)
        assertNull(kept.observedFog)
    }

    @Test
    fun `hourly visibility round-trips so today's fog ahead keeps the provider's number`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val hour = 3_600_000L
        val start = (System.currentTimeMillis() / hour) * hour
        dao.upsertHourlyForecasts(
            lat, lon, "OPEN_METEO",
            listOf(
                com.weatherwidget.data.model.HourlyForecast(start, 53.2f, "Light Fog", source = "OPEN_METEO", fetchedAt = start, visibilityMi = 0f),
                com.weatherwidget.data.model.HourlyForecast(start + hour, 55f, "Light Fog", source = "OPEN_METEO", fetchedAt = start, visibilityMi = 0.06f),
            ),
        )
        val read = dao.getHourlyWithHistory(lat, lon, "OPEN_METEO", start, start + hour, nowMs = start)
        println("read=${read.map { it.dateTime to it.visibilityMi }}")
        assertEquals(listOf(0f, 0.06f), read.sortedBy { it.dateTime }.map { it.visibilityMi })
    }
}
