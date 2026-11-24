package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.PartialHighDayContract
import com.weatherwidget.shared.daily.DailyColumnBuilder
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.shared.util.TempUtils
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Desktop half of [PartialHighDayContract]: a throwaway SQLite file (never `DesktopDbPaths`), the real
 * [DesktopForecastPersistHost], [DesktopWeatherRepository.loadCached] and [DesktopDailyForecastModel].
 * The Room half is `RoomPartialHighDayContractTest` (`:app`).
 */
@Category(ShortDuration::class)
class DesktopPartialHighDayContractTest : PartialHighDayContract() {
    private val paths = mutableListOf<Path>()

    override fun open(): Platform {
        val path = Files.createTempFile("weather_partial_high", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        return object : Platform {
            override fun persistHost(sourceId: String, lat: Double, lon: Double): ForecastPersistRun.Host<*> =
                DesktopForecastPersistHost(dao, sourceId, lat, lon)

            override suspend fun storeNormals(lat: Double, lon: Double, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) =
                dao.upsertClimateNormals(ClimateNormals.locationKey(lat, lon), monthlyHigh, monthlyLow)

            override suspend fun rawDaily(lat: Double, lon: Double, sourceId: String, date: LocalDate): List<Raw> =
                DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
                    conn.prepareStatement(
                        "SELECT highTemp, lowTemp, precipProbability FROM forecasts " +
                            "WHERE source = ? AND targetDate = ? AND locationLat = ? AND locationLon = ? ORDER BY fetchedAt DESC",
                    ).use { st ->
                        st.setString(1, sourceId)
                        st.setLong(2, date.toEpochDay() * 86_400_000L)
                        st.setDouble(3, LocationMatch.quantize(lat))
                        st.setDouble(4, LocationMatch.quantize(lon))
                        st.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        Raw(
                                            rs.getFloat(1).takeUnless { rs.wasNull() },
                                            rs.getFloat(2).takeUnless { rs.wasNull() },
                                            rs.getInt(3).takeUnless { rs.wasNull() },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

            override suspend fun shownDay(lat: Double, lon: Double, sourceId: String, date: LocalDate, now: LocalDateTime): Shown? {
                val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val repository = DesktopWeatherRepository(
                    DesktopWeatherService(lat, lon, sourceId), dao, lat, lon, sourceId, currentTimeMillis = { nowMs },
                )
                val snapshot = repository.loadCached(nowMs) ?: return null
                val config = DesktopConfig(
                    lat = lat,
                    lon = lon,
                    label = "PartialHigh",
                    dateOffset = 0,
                    settings = DesktopSettings(
                        weatherSource = sourceId,
                        visibleSources = listOf(sourceId),
                    ),
                )
                val state = DesktopDailyForecastModel.build(
                    config, snapshot, DesktopDailyForecastModel.dimensions(1600, 300), now, currentTemp = null,
                )
                println("desktop columns: ${state.days.map { "${it.date} ${it.solidHigh}/${it.solidLow} highNormal=${it.highIsClimateNormal} climate=${it.isClimateNormal}" }}")
                val day = state.days.firstOrNull { it.date == date } ?: return null
                if (day.solidHigh == null && day.solidLow == null) return null
                // As DesktopWidgetPopup prints them.
                val high = DailyColumnBuilder.textHigh(day)
                val low = DailyColumnBuilder.textLow(day)
                return Shown(
                    high = day.solidHigh,
                    low = day.solidLow,
                    highLabel = TempUtils.formatDayTemp(high, false, day.printsWholeHighDegrees),
                    lowLabel = TempUtils.formatDayTemp(low, false, day.printsWholeDegrees),
                    highIsClimateNormal = day.highIsClimateNormal,
                    climateDay = day.isClimateNormal,
                )
            }
        }
    }

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }
}
