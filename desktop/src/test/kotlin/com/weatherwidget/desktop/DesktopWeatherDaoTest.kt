package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.CurrentTempStatus
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.shared.actuals.RetiredProductCleanup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopWeatherDaoTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-test-dao", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `upsertForecasts rounds future days to integers and keeps today decimal`() {
        // Parity with Android via the shared ForecastTempRounding rule: today keeps full precision
        // (accuracy tracking), future days round to integer (noise reduction). 90.61 was the exact
        // Silurian value that read 91 on Android but 90.1 on desktop before this rule was shared.
        val lat = 37.4168
        val lon = -122.0890
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val future = LocalDate.now(ZoneOffset.UTC).plusDays(2).toString()
        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = today, highTemp = 90.61f, lowTemp = 65.37f, condition = "Clear"),
            DailyForecast(date = future, highTemp = 90.61f, lowTemp = 65.37f, condition = "Rain"),
        ))

        val rows = dao.getDailyForecasts(lat, lon, "SILURIAN")
        val todayRow = rows.first { it.date == today }
        val futureRow = rows.first { it.date == future }

        assertEquals(90.61f, todayRow.highTemp, 0.001f)
        assertEquals(65.37f, todayRow.lowTemp, 0.001f)
        assertEquals(91.0f, futureRow.highTemp, 0.001f)
        assertEquals(65.0f, futureRow.lowTemp, 0.001f)
    }

    @Test
    fun `getLastSuccessfulFetch returns null when no logs exist`() {
        assertNull(dao.getLastSuccessfulFetch())
        assertNull(dao.getLastSuccessfulFetch("NWS"))
    }

    @Test
    fun `replacement coverage guards Tomorrow retired-product cleanup`() {
        val now = System.currentTimeMillis().let { it - Math.floorMod(it, 5 * 60_000L) }
        val lat = 37.417
        val lon = -122.089
        val farLat = 37.617
        fun observation(
            stationId: String,
            temperature: Float,
            locationLat: Double = lat,
            timestamp: Long = now,
        ) = DesktopObservationEntity(
            stationId = stationId,
            stationName = stationId,
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = locationLat,
            locationLon = lon,
            fetchedAt = now,
            api = WeatherSource.TOMORROW_IO.id,
        )
        dao.upsertObservations(
            listOf(
                observation("TOMORROW_IO_REALTIME", 74.6f),
                observation("TOMORROW_IO_RECENT_HISTORY", 77.07f),
                observation("TOMORROW_IO_5M_HISTORY", 99f, timestamp = now + 60_000L),
                observation("TOMORROW_IO_REALTIME", 63f, farLat),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(now, WeatherSource.TOMORROW_IO.id, lat, lon, 77.07f, 74.6f, "Clear", now),
                DailyHistory(now, WeatherSource.TOMORROW_IO.id, farLat, lon, 63f, 60f, "Clear", now),
            ),
        )

        assertTrue(dao.retireProductsIfCovered("TOMORROW_IO", lat, lon).isEmpty())
        assertEquals(3, dao.getObservationsInRange(now, now + 60_001L, lat, lon).size)

        dao.upsertObservations(listOf(observation("TOMORROW_IO_5M_HISTORY", 78.16f)))
        // Exact-key upsert accepts a provider revision without creating a second point.
        dao.upsertObservations(listOf(observation("TOMORROW_IO_5M_HISTORY", 78.05f)))
        val result = dao.retireProductsIfCovered("TOMORROW_IO", lat, lon).single()

        assertEquals(3, result.retiredObservations)
        assertEquals(1, result.dailyRows)
        val siteRows = dao.getObservationsInRange(now, now + 60_001L, lat, lon)
        assertEquals(listOf("TOMORROW_IO_5M_HISTORY"), siteRows.map { it.stationId })
        assertEquals(78.05f, siteRows.single().temperature, 0.001f)
        assertEquals(
            listOf("TOMORROW_IO_REALTIME"),
            dao.getObservationsInRange(now, now + 1L, farLat, lon).map { it.stationId },
        )
        assertTrue(dao.getExtremesInRange(now, now, lat, lon).isEmpty())
        assertEquals(1, dao.getExtremesInRange(now, now, farLat, lon).size)
        assertEquals(1, dao.getRecentLogsByTags(listOf(RetiredProductCleanup.LOG_TAG), 10).size)
    }

    /** Same regression as Android's: the cleanup must not delete a row built from five-minute data. */
    @Test
    fun `a site with only five minute data keeps its computed Tomorrow daily row`() {
        val now = System.currentTimeMillis().let { it - Math.floorMod(it, 5 * 60_000L) }
        val lat = 52.233
        val lon = 21.071
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "TOMORROW_IO_5M_HISTORY",
                    stationName = "TOMORROW_IO_5M_HISTORY",
                    timestamp = now,
                    temperature = 68.14f,
                    condition = "Clear",
                    locationLat = lat,
                    locationLon = lon,
                    fetchedAt = now,
                    api = WeatherSource.TOMORROW_IO.id,
                ),
            ),
        )
        dao.upsertDailyHistory(
            listOf(DailyHistory(now, WeatherSource.TOMORROW_IO.id, lat, lon, 68.14f, 54.45f, "Clear", now)),
        )

        repeat(3) { assertTrue(dao.retireProductsIfCovered("TOMORROW_IO", lat, lon).isEmpty()) }

        assertEquals(1, dao.getExtremesInRange(now, now, lat, lon).size)
        assertTrue(dao.getRecentLogsByTags(listOf(RetiredProductCleanup.LOG_TAG), 10).isEmpty())
    }

    @Test
    fun `getLastSuccessfulFetch retrieves latest refresh matching specific source`() {
        // Write a warning log (should be ignored)
        dao.log(tag = "REFRESH_FAIL", message = "source=NWS offline", level = "WARN")
        
        // Write a success NWS log
        val nwsTime = 1000L
        dao.log(tag = "REFRESH", message = "source=NWS hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        // We override timestamp for test correctness via SQL since DAO log uses System.currentTimeMillis()
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $nwsTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Write a success Open-Meteo log later
        val omTime = 2000L
        dao.log(tag = "REFRESH", message = "source=OPEN_METEO hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $omTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // 1. Generic check should return the latest overall (2000L)
        assertEquals(2000L, dao.getLastSuccessfulFetch())

        // 2. Specific source checks
        assertEquals(1000L, dao.getLastSuccessfulFetch("NWS"))
        assertEquals(2000L, dao.getLastSuccessfulFetch("OPEN_METEO"))
        assertNull(dao.getLastSuccessfulFetch("SILURIAN"))
    }

    @Test
    fun `getDailyForecasts repairs degenerate today using last genuine forecast`() {
        val lat = 37.0
        val lon = -122.0
        val source = "NWS"
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()

        // An earlier, genuine forecast for today (real high/low spread) plus a future day.
        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 91f, lowTemp = 62f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ))
        // Age that batch so the degenerate fetch below becomes the latest batch.
        setForecastBatchStamp(1000L)

        // The latest fetch collapsed today's forecast to high == low (the NWS late-day bug).
        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = 92f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ))

        val days = dao.getDailyForecasts(lat, lon, source).associateBy { it.date }
        // Today is repaired to the last genuine historical forecast, not the degenerate latest.
        assertEquals(91f, days.getValue(today).highTemp)
        assertEquals(62f, days.getValue(today).lowTemp)
        // The genuine future day is untouched.
        assertEquals(87f, days.getValue(tomorrow).highTemp)
        assertEquals(60f, days.getValue(tomorrow).lowTemp)
    }

    @Test
    fun `getDailyForecasts keeps degenerate day when no genuine forecast exists`() {
        val lat = 37.0
        val lon = -122.0
        val source = "NWS"
        val today = LocalDate.now(ZoneOffset.UTC).toString()

        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = 92f, condition = "Sunny"),
        ))

        val day = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(92f, day.highTemp)
        assertEquals(92f, day.lowTemp)
    }

    /** Force every stored forecast row to a fixed batch/fetched stamp so a later insert outranks it. */
    private fun setForecastBatchStamp(value: Long) {
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE forecasts SET batchFetchedAt = $value, fetchedAt = $value")
            }
        }
    }

    @Test
    fun `getLatestCurrentTempStatus retrieves the latest status matching specific source`() {
        // Initially should be null
        assertNull(dao.getLatestCurrentTempStatus("OPEN_METEO"))

        // Log one ok=false for OPEN_METEO
        val time1 = 1000L
        dao.log(tag = "CURRENT_TEMP_STATUS", message = "source=OPEN_METEO ok=false class=ConnectTimeoutException detail=Timeout", level = "WARN")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $time1 WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Log one ok=true for NWS at a later time
        val time2 = 2000L
        dao.log(tag = "CURRENT_TEMP_STATUS", message = "source=NWS ok=true", level = "INFO")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $time2 WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Log one ok=true for OPEN_METEO at a later time
        val time3 = 3000L
        dao.log(tag = "CURRENT_TEMP_STATUS", message = "source=OPEN_METEO ok=true", level = "INFO")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $time3 WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Verify NWS status
        val nwsStatus = dao.getLatestCurrentTempStatus("NWS")
        assertNotNull(nwsStatus)
        assertEquals(time2, nwsStatus!!.timestamp)
        assertTrue(nwsStatus.ok)
        assertEquals("source=NWS ok=true", nwsStatus.message)

        // Verify OPEN_METEO status (returns the latest one, which is time3 / ok=true)
        val omStatus = dao.getLatestCurrentTempStatus("OPEN_METEO")
        assertNotNull(omStatus)
        assertEquals(time3, omStatus!!.timestamp)
        assertTrue(omStatus.ok)
        assertEquals("source=OPEN_METEO ok=true", omStatus.message)
        
        // Log one ok=false for OPEN_METEO at an even later time
        val time4 = 4000L
        dao.log(tag = "CURRENT_TEMP_STATUS", message = "source=OPEN_METEO ok=false class=SocketTimeoutException detail=Timeout2", level = "WARN")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $time4 WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }
        
        val omStatus2 = dao.getLatestCurrentTempStatus("OPEN_METEO")
        assertNotNull(omStatus2)
        assertEquals(time4, omStatus2!!.timestamp)
        assertFalse(omStatus2.ok)
        assertEquals("source=OPEN_METEO ok=false class=SocketTimeoutException detail=Timeout2", omStatus2.message)
    }

    // plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md test #7: the JDBC
    // coverage read + ElapsedForecastBackfill.select + the history upsert, together.
    @Test
    fun `elapsed backfill files only uncovered same-site hours and is a no-op on a second pass`() {
        val h = 3_600_000L
        val lat = 37.4168
        val lon = -122.0890
        val source = WeatherSource.NWS.id
        val now = (System.currentTimeMillis() / h) * h + 20 * 60_000L
        fun hour(t: Long, temp: Float) = HourlyForecast(dateTime = t, temperature = temp, condition = "Clear", source = source)
        val payload = (-6..2).map { hour(now - now % h + it * h, 60f + it) }
        val window = ElapsedForecastBackfill.window(now)
        val offered = payload.filter { it.dateTime in window }
        assertEquals(6, offered.size)

        // A genuine snapshot from an earlier fetch on a 0.001-deg jitter fragment covers its hour;
        // a row 0.05 deg away (inside the +/-0.1 query box) is a different site and covers nothing.
        val snapshotted = offered[2].dateTime
        dao.upsertHourlyForecastHistory(lat + 0.001, lon, source, snapshotted - 24 * h, listOf(hour(snapshotted, 99f)))
        dao.upsertHourlyForecastHistory(lat + 0.05, lon, source, 0L, listOf(hour(offered[0].dateTime, 1f)))

        val covered = dao.getHourlyHistoryCoveredHours(lat, lon, source, offered.first().dateTime, offered.last().dateTime + 1)
        assertEquals(setOf(snapshotted), covered)

        val selected = ElapsedForecastBackfill.select(offered, now, covered)
        val bucket = (now / (4 * h)) * (4 * h)
        dao.upsertHourlyForecastHistory(lat, lon, source, bucket, selected)

        val stored = dao.getHourlyHistory(lat, lon, source, now - 24 * h, now + 24 * h, now)
        // Every elapsed hour now has a same-site row; the snapshotted one kept its original value.
        assertEquals(offered.map { it.dateTime }, stored.map { it.dateTime }.distinct().sorted())
        assertEquals(99f, stored.first { it.dateTime == snapshotted }.temperature, 0f)
        // Nothing reached the live table.
        assertTrue(dao.getHourlyForecasts(lat, lon, source, now - 24 * h, now + 24 * h).isEmpty())

        // Second pass: everything covered, nothing to write.
        val coveredAgain = dao.getHourlyHistoryCoveredHours(lat, lon, source, offered.first().dateTime, offered.last().dateTime + 1)
        assertTrue(ElapsedForecastBackfill.select(offered, now, coveredAgain).isEmpty())
    }
}
