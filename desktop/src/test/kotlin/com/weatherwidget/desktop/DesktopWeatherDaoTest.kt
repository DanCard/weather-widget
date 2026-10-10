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
        // Local today, as the writer decides it (PredictionDate). This test once used the UTC
        // date, matching the bug it should have caught: after 17:00 PDT local today was rounded.
        val today = LocalDate.now().toString()
        val future = LocalDate.now().plusDays(2).toString()
        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = today, highTemp = 90.61f, lowTemp = 65.37f, condition = "Clear"),
            DailyForecast(date = future, highTemp = 90.61f, lowTemp = 65.37f, condition = "Rain"),
        ), nowMs = earlyMorningMs())

        val rows = dao.getDailyForecasts(lat, lon, "SILURIAN")
        val todayRow = rows.first { it.date == today }
        val futureRow = rows.first { it.date == future }

        assertEquals(90.61f, todayRow.highTemp!!, 0.001f)
        assertEquals(65.37f, todayRow.lowTemp!!, 0.001f)
        assertEquals(91.0f, futureRow.highTemp!!, 0.001f)
        assertEquals(65.0f, futureRow.lowTemp!!, 0.001f)
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

    /**
     * NWS stops reporting today's low in the evening. Since 2026-10-10 the write keeps the stored low
     * (SameDayExtremeCutoff.keptTodayLow), so the evening row stands complete with its own newer high;
     * before, it was stored without a low and the reader swapped in the morning row, high included.
     * plans/261010-google-daily-low-filed-under-the-morning-it-ends.md
     */
    @Test
    fun `an evening fetch without today's low keeps the stored low and its own high`() {
        val lat = 37.0
        val lon = -122.0
        val source = "NWS"
        // Local date: the DAO decides "today" in local time, as the widget does.
        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()

        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 91f, lowTemp = 62f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ), nowMs = earlyMorningMs())
        // Same morning: within PartialForecastDays.COMPLETE_REPLACEMENT_MAX_AGE_MS of the evening fetch.
        setForecastBatchStamp(earlyMorningMs())

        // The evening fetch: today's low is gone.
        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = null, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ), nowMs = earlyMorningMs() + 60_000L)

        val days = dao.getDailyForecasts(lat, lon, source).associateBy { it.date }
        assertEquals(92f, days.getValue(today).highTemp)
        assertEquals(62f, days.getValue(today).lowTemp)
        assertEquals(87f, days.getValue(tomorrow).highTemp)
        assertEquals(60f, days.getValue(tomorrow).lowTemp)
    }

    /**
     * 2026-10-09 Pixel: the only complete row for today at the site was a week old (90/74) and
     * replaced that day's 69.5. A complete row more than a day older than the newest is no
     * stand-in; the fresh partial row stays and the column fills its low from hourly.
     */
    @Test
    fun `getDailyForecasts keeps a partial today over a week-old complete forecast`() {
        val lat = 37.0
        val lon = -122.0
        val source = "OPEN_METEO"
        val today = LocalDate.now().toString()

        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 90f, lowTemp = 74f, condition = "Sunny"),
        ), nowMs = earlyMorningMs() - 7 * 86_400_000L)
        setForecastBatchStamp(earlyMorningMs() - 7 * 86_400_000L)

        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 69.5f, lowTemp = null, condition = "Overcast"),
        ), nowMs = earlyMorningMs() + 60_000L)

        val day = dao.getDailyForecasts(lat, lon, source).associateBy { it.date }.getValue(today)
        assertEquals(69.5f, day.highTemp)
        assertNull(day.lowTemp)
    }

    @Test
    fun `getDailyForecasts keeps a partial today when no complete forecast exists`() {
        val lat = 37.0
        val lon = -122.0
        val today = LocalDate.now().toString()

        dao.upsertForecasts(lat, lon, "NWS", listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = null, condition = "Sunny"),
        ), nowMs = earlyMorningMs())

        val day = dao.getDailyForecasts(lat, lon, "NWS").single()
        assertEquals(92f, day.highTemp)
        assertNull("a missing low is stored and read as null, never 0 or the high", day.lowTemp)
    }

    /**
     * A source first fetched after 06:00 (Google, 2026-10-06): the low is still frozen out of
     * `lowTemp`, but the raw value is kept in `hindcastLowTemp` for the past-day dashed fallback.
     */
    @Test
    fun `upsertForecasts keeps a frozen same-day value as hindcast, not as the forecast`() {
        val lat = 37.4168
        val lon = -122.0890
        val today = LocalDate.now()
        val at1116 = today.atTime(11, 16).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dao.upsertForecasts(lat, lon, "GOOGLE_WEATHER", listOf(
            DailyForecast(date = today.toString(), highTemp = 81.9f, lowTemp = 60.4f, condition = "Clear"),
        ), nowMs = at1116)

        val stored = database.getConnection().use { conn ->
            conn.createStatement().executeQuery(
                "SELECT highTemp, lowTemp, hindcastHighTemp, hindcastLowTemp FROM forecasts WHERE source = 'GOOGLE_WEATHER'",
            ).use { rs ->
                assertTrue(rs.next())
                listOf(rs.getObject("highTemp"), rs.getObject("lowTemp"), rs.getObject("hindcastHighTemp"), rs.getObject("hindcastLowTemp"))
            }
        }
        assertEquals(81.9, (stored[0] as Number).toDouble(), 0.01)
        assertNull("a post-cutoff low never becomes the forecast", stored[1])
        assertNull("the high was not frozen, so nothing is hindcast", stored[2])
        assertEquals(60.4, (stored[3] as Number).toDouble(), 0.01)

        // A later batch makes the first one a snapshot, which carries the hindcast to the renderer.
        setForecastBatchStamp(1000L)
        dao.upsertForecasts(lat, lon, "GOOGLE_WEATHER", listOf(
            DailyForecast(date = today.plusDays(1).toString(), highTemp = 80f, lowTemp = 59f, condition = "Clear"),
        ))
        val snapshot = dao.getDailyForecastSnapshots(
            today.minusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            lat, lon, "GOOGLE_WEATHER",
        )[today.toString()]!!.single()
        assertNull(snapshot.lowTemp)
        assertEquals(60.4f, snapshot.hindcastLowTemp!!, 0.01f)
    }

    /** Before 06:00 local, so the same-day cutoffs (SameDayExtremeCutoff) store both values. */
    private fun earlyMorningMs(): Long =
        LocalDate.now().atTime(5, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Silurian's evening batches start at tomorrow (its days are UTC dates), so the newest batch
     * has NO row for today. Today must come from the stored rows (shared
     * PartialForecastDays.todayRow), not be left absent for the climate-normal fill, which drew
     * 76.2/56.8 in place of 90.1/67.4 on 2026-10-04.
     */
    @Test
    fun `getDailyForecasts restores today from stored rows when the newest batch has none`() {
        val lat = 37.417
        val lon = -122.089
        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()

        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = today, highTemp = 90f, lowTemp = 67f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 89f, lowTemp = 66f, condition = "Sunny"),
        ), nowMs = earlyMorningMs())
        setForecastBatchStamp(1000L)
        // The evening batch: tomorrow onward only.
        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = tomorrow, highTemp = 88f, lowTemp = 65f, condition = "Sunny"),
        ))

        val days = dao.getDailyForecasts(lat, lon, "SILURIAN")
        assertEquals(listOf(today, tomorrow), days.map { it.date })
        val todayRow = days.first()
        assertEquals(90f, todayRow.highTemp)
        assertEquals(67f, todayRow.lowTemp)
        assertTrue(!todayRow.isClimateNormal)
        assertEquals(88f, days[1].highTemp)
    }

    @Test
    fun `getDailyForecasts restores a one-sided today when no complete row was ever stored`() {
        val lat = 37.417
        val lon = -122.089
        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()

        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = today, highTemp = 89f, lowTemp = null, condition = "Sunny"),
        ), nowMs = earlyMorningMs())
        setForecastBatchStamp(1000L)
        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = tomorrow, highTemp = 89f, lowTemp = 66f, condition = "Sunny"),
        ))

        val todayRow = dao.getDailyForecasts(lat, lon, "SILURIAN").first()
        assertEquals(today, todayRow.date)
        assertEquals(89f, todayRow.highTemp)
        assertNull(todayRow.lowTemp)
    }

    @Test
    fun `getDailyForecasts leaves today absent when nothing was stored for it`() {
        val lat = 37.417
        val lon = -122.089
        val tomorrow = LocalDate.now().plusDays(1).toString()

        dao.upsertForecasts(lat, lon, "SILURIAN", listOf(
            DailyForecast(date = tomorrow, highTemp = 89f, lowTemp = 66f, condition = "Sunny"),
        ))

        // Absent, so the repository's climate-normal fill remains the last resort.
        assertEquals(listOf(tomorrow), dao.getDailyForecasts(lat, lon, "SILURIAN").map { it.date })
    }

    private val pacific = java.time.ZoneId.of("America/Los_Angeles")
    private val dayMs = 86_400_000L
    private fun pacificMs(local: String) =
        java.time.LocalDateTime.parse(local).atZone(pacific).toInstant().toEpochMilli()
    private fun dayEpoch(date: String) = LocalDate.parse(date).toEpochDay() * dayMs

    /**
     * An evening fetch is a prediction made TODAY (local). The writer used the UTC date, which
     * from 17:00 PDT is tomorrow, so 1-day-ahead accuracy graded the last fetch before 17:00
     * (plans/261005-desktop-forecast-today-is-utc-date-after-5pm.md). Uses the test JVM's
     * zone, pinned to America/Los_Angeles in desktop/build.gradle.kts.
     */
    @Test
    fun `upsertForecasts files an evening fetch under the local day, not the UTC day`() {
        val nowMs = pacificMs("2026-10-04T20:00") // 2026-10-05T03:00Z
        dao.upsertForecasts(37.417, -122.089, "SILURIAN", listOf(
            DailyForecast(date = "2026-10-05", highTemp = 89f, lowTemp = 66f, condition = "Sunny"),
            DailyForecast(date = "2026-10-06", highTemp = 92f, lowTemp = 65f, condition = "Sunny"),
        ), nowMs = nowMs)

        val rows = dao.getForecastsInRangeBySource(dayEpoch("2026-10-05"), dayEpoch("2026-10-06"), 37.417, -122.089, "SILURIAN")
        assertEquals(2, rows.size)
        rows.forEach { assertEquals("target ${it.targetDate}", dayEpoch("2026-10-04"), it.dateOfPrediction) }
    }

    private fun insertRawForecast(targetDate: String, dateOfPrediction: String, fetchedAt: Long, source: String = "NWS") {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp,
                   condition, isClimateNormal, source, batchFetchedAt, fetchedAt)
                   VALUES (?, ?, 37.417, -122.089, 80, 60, 'Sunny', 0, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setLong(1, dayEpoch(targetDate))
                stmt.setLong(2, dayEpoch(dateOfPrediction))
                stmt.setString(3, source)
                stmt.setLong(4, fetchedAt)
                stmt.setLong(5, fetchedAt)
                stmt.executeUpdate()
            }
        }
    }

    private fun storedPredictionDates(): Map<Pair<Long, Long>, Long> =
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT targetDate, fetchedAt, dateOfPrediction FROM forecasts")
                buildMap { while (rs.next()) put(rs.getLong(1) to rs.getLong(2), rs.getLong(3)) }
            }
        }

    @Test
    fun `repairUtcDatedPredictions moves UTC-dated evening rows back to the local day, once`() {
        val evening = pacificMs("2026-10-03T20:00") // UTC date 2026-10-04
        val afternoon = pacificMs("2026-10-03T14:00") // UTC date 2026-10-03, same as local
        // Old writer, evening: filed under the UTC date.
        insertRawForecast("2026-10-05", "2026-10-04", evening)
        // Old writer, evening, target = UTC date: min(utc, target) = 10-04, still one day late.
        insertRawForecast("2026-10-04", "2026-10-04", evening, source = "SILURIAN")
        // Old writer, evening, target before the UTC date: clamped to target, already right.
        insertRawForecast("2026-10-03", "2026-10-03", evening, source = "OPEN_METEO")
        // Afternoon: UTC and local agree, nothing to repair.
        insertRawForecast("2026-10-05", "2026-10-03", afternoon)

        assertEquals(2, dao.repairUtcDatedPredictions(pacific))

        val stored = storedPredictionDates()
        assertEquals(dayEpoch("2026-10-03"), stored.getValue(dayEpoch("2026-10-05") to evening))
        assertEquals(dayEpoch("2026-10-03"), stored.getValue(dayEpoch("2026-10-04") to evening))
        assertEquals(dayEpoch("2026-10-03"), stored.getValue(dayEpoch("2026-10-03") to evening))
        assertEquals(dayEpoch("2026-10-03"), stored.getValue(dayEpoch("2026-10-05") to afternoon))
        // What the fixed writer stores is never "UTC-dated", so a second pass finds nothing.
        assertEquals(0, dao.repairUtcDatedPredictions(pacific))
    }

    /** Future partial days are left to the repository's climate-normal fill; the DAO keeps them. */
    @Test
    fun `getDailyForecasts leaves a partial future day alone`() {
        val lat = 37.0
        val lon = -122.0
        val future = LocalDate.now().plusDays(6).toString()
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast(date = future, highTemp = 80f, lowTemp = 58f, condition = "Sunny")))
        setForecastBatchStamp(1000L)
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast(date = future, highTemp = null, lowTemp = 57f, condition = "Clear")))

        val day = dao.getDailyForecasts(lat, lon, "NWS").single()
        assertNull(day.highTemp)
        assertEquals(57f, day.lowTemp)
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
