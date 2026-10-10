package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopWeatherRepositoryTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-test-repo", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        
        // Pass dummy weatherService since loadCached does not make network calls
        val dummyService = DesktopWeatherService(37.4220, -122.0841, "NWS")
        repository = DesktopWeatherRepository(dummyService, dao, 37.4220, -122.0841, "NWS")
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `loadCached resolves correct temperature delta`() = runTest {
        try {
            val baseTime = System.currentTimeMillis()
            val now = (baseTime / 3600_000L) * 3600_000L

            // 1. Insert hourly forecasts
            val hourly = listOf(
                HourlyForecast(now - 3600_000L, 70f, "Clear"), // 1 hour ago
                HourlyForecast(now, 72f, "Clear"),             // Now
                HourlyForecast(now + 3600_000L, 74f, "Clear"), // 1 hour from now
            )
            dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", hourly)

            // 2. Insert observation matching 1 hour ago
            // Observation says 73.3 degrees, but forecast 1 hour ago was 70.0 degrees.
            // So the raw delta is +3.3 degrees.
            val obs = listOf(
                DesktopObservationEntity(
                    stationId = "STATION_A",
                    stationName = "Station A",
                    timestamp = now - 3600_000L,
                    temperature = 73.3f,
                    condition = "Clear",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 0f,
                    stationType = StationType.UNKNOWN,
                    fetchedAt = now,
                    api = "NWS"
                )
            )
            dao.upsertObservations(obs)

            // 3. Load cache
            val result = repository.loadCached()
            assertNotNull(result)

            val elapsedMs = System.currentTimeMillis() - now
            val fraction = elapsedMs.toFloat() / 3600_000f
            val expectedForecast = 72f + (74f - 72f) * fraction
            val expectedTemp = expectedForecast + 3.3f

            println("DIAGNOSTIC: currentTemp=${result!!.resolved.currentTemp} appliedDelta=${result.resolved.appliedDelta} expectedTemp=$expectedTemp")

            // 4. Verify display temperature and applied delta
            assertEquals(expectedTemp, result.resolved.currentTemp!!, 0.05f)
            assertEquals(3.3f, result.resolved.appliedDelta!!, 0.01f)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `loadCached ignores cached silurian temperature actuals`() = runTest {
        val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        val silurianService = DesktopWeatherService(37.4220, -122.0841, "SILURIAN")
        val silurianRepository = DesktopWeatherRepository(
            silurianService,
            dao,
            37.4220,
            -122.0841,
            "SILURIAN",
            currentTimeMillis = { now },
        )
        dao.upsertHourlyForecasts(
            37.4220,
            -122.0841,
            "SILURIAN",
            listOf(
                HourlyForecast(now, 64f, "Forecast clear", source = WeatherSource.SILURIAN.id),
                HourlyForecast(now + 3600_000L, 66f, "Forecast clear", source = WeatherSource.SILURIAN.id),
            ),
        )
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "SILURIAN_MAIN",
                    stationName = "Silurian history backfill",
                    timestamp = now,
                    temperature = 91f,
                    condition = "Synthetic observed condition",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = now,
                    api = WeatherSource.SILURIAN.id,
                ),
            ),
        )

        val result = silurianRepository.loadCached(now)

        assertNotNull(result)
        assertEquals(64f, result!!.resolved.currentTemp!!, 0.01f)
        assertNull(result.resolved.appliedDelta)
        assertNull(result.resolved.currentObservedAt)
        assertEquals("Forecast clear", result.resolved.currentCondition)
        // Silurian's own include_past rows are forecast output, never temperature actuals.
        // Borrowed Synoptic/METAR highs land in daily_history and load — see the test below.
        assertTrue(result.raw.dailyActuals.isEmpty())
        silurianService.close()
    }

    /**
     * Silurian borrows measured actuals (Synoptic/METAR). Those blend rows live under
     * `source=SILURIAN` with computedHighTemp/LowTemp and must load — desktop used to drop them via
     * `!supportsTemperatureActuals`. The row is yesterday's: a persisted row for TODAY is never read
     * (today is the live blend only, as on Android — see DailyActualsAssembler).
     */
    @Test
    fun `loadCached includes silurian borrowed actuals from daily_history`() = runTest {
        val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val yesterdayMs = yesterday.toEpochDay() * 86_400_000L
        val silurianService = DesktopWeatherService(37.4220, -122.0841, "SILURIAN")
        val silurianRepository = DesktopWeatherRepository(
            silurianService,
            dao,
            37.4220,
            -122.0841,
            "SILURIAN",
            currentTimeMillis = { now },
        )
        dao.upsertHourlyForecasts(
            37.4220,
            -122.0841,
            "SILURIAN",
            listOf(
                HourlyForecast(now, 64f, "Forecast clear", source = WeatherSource.SILURIAN.id),
            ),
        )
        dao.upsertForecasts(
            37.4220,
            -122.0841,
            "SILURIAN",
            listOf(DailyForecast(today.toString(), 88f, 58f, "Sunny")),
        )
        dao.upsertDailyHistory(
            listOf(
                com.weatherwidget.data.model.DailyHistory(
                    date = yesterdayMs,
                    source = WeatherSource.SILURIAN.id,
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    computedHighTemp = 97.7f,
                    computedLowTemp = 60.3f,
                    condition = "Sunny",
                    updatedAt = now,
                ),
            ),
        )

        val result = silurianRepository.loadCached(now)

        assertNotNull(result)
        val actual = result!!.raw.dailyActuals[yesterday.toString()]
        assertNotNull("borrowed Synoptic/METAR high must load for Silurian", actual)
        assertEquals(97.7f, actual!!.computedHighTemp!!, 0.01f)
        assertEquals(60.3f, actual.computedLowTemp!!, 0.01f)
        silurianService.close()
    }

    /**
     * Today's high must come from the configured actuals provider (Synoptic) live, not only
     * from a possibly stale daily_history row — Android DailyActualsLoader parity.
     *
     * `now` is pinned to 14:00 today so the coverage gate below is exercised the same way whatever
     * hour the suite runs (relative stamps used to start at `now-3h`, which only covered the day's
     * start just after midnight).
     */
    @Test
    fun `loadCached computes today high from live synoptic observations for silurian`() = runTest {
        val actual = loadSilurianTodayFromSynoptic(firstReadingHour = 0)
        assertEquals(69f, actual.computedHighTemp!!, 0.01f)
        assertEquals(65f, actual.computedLowTemp!!, 0.01f)
    }

    /**
     * Desktop used to lack Android's late-start gate: readings that begin at 11:00 made 65° (the
     * 11:00 reading) today's observed low. The low is "lowest since we started watching" and must
     * be null so the forecast low renders; the high still stands.
     */
    @Test
    fun `loadCached nulls today's low when live observations start late`() = runTest {
        val actual = loadSilurianTodayFromSynoptic(firstReadingHour = 11)
        assertEquals(69f, actual.computedHighTemp!!, 0.01f)
        assertNull("a late-starting day has no observed low", actual.computedLowTemp)
        assertTrue(dao.getRecentLogs(50).any { it.tag == "TODAY_LOW_UNCOVERED" })
    }

    /** Three Synoptic readings today — 65° at [firstReadingHour], 67° at 12:00, 69° at 13:00. */
    private suspend fun loadSilurianTodayFromSynoptic(firstReadingHour: Int): com.weatherwidget.data.model.DailyHistory {
        com.weatherwidget.shared.observations.ActualsProviderResolver.installPreferenceSource { source ->
            WeatherSource.SYNOPTIC.takeIf { source == WeatherSource.SILURIAN }
        }
        try {
            val today = LocalDate.now()
            val zone = java.time.ZoneId.systemDefault()
            val at = { hour: Int -> today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() }
            val now = at(14)
            val silurianService = DesktopWeatherService(37.4220, -122.0841, "SILURIAN")
            val silurianRepository = DesktopWeatherRepository(
                silurianService,
                dao,
                37.4220,
                -122.0841,
                "SILURIAN",
                currentTimeMillis = { now },
            )
            dao.upsertHourlyForecasts(
                37.4220,
                -122.0841,
                "SILURIAN",
                listOf(HourlyForecast(now - 3600_000L, 64f, "Clear", source = WeatherSource.SILURIAN.id)),
            )
            dao.upsertForecasts(
                37.4220,
                -122.0841,
                "SILURIAN",
                listOf(DailyForecast(today.toString(), 88f, 58f, "Sunny")),
            )
            val synopticObs = listOf(firstReadingHour, 12, 13).mapIndexed { i, hour ->
                DesktopObservationEntity(
                    stationId = "G4110",
                    stationName = "Synoptic site",
                    timestamp = at(hour),
                    temperature = 65f + i * 2f, // 65, 67, 69
                    condition = "Clear",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 1.2f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = now,
                    api = WeatherSource.SYNOPTIC.id,
                )
            }
            dao.upsertObservations(synopticObs)

            val result = silurianRepository.loadCached(now)

            assertNotNull(result)
            val actual = result!!.raw.dailyActuals[today.toString()]
            assertNotNull(
                "today's actual must come from live Synoptic observations; keys=${result.raw.dailyActuals.keys}",
                actual,
            )
            silurianService.close()
            return actual!!
        } finally {
            com.weatherwidget.shared.observations.ActualsProviderResolver.resetPreferenceSource()
        }
    }

    @Test
    fun `loadCached uses five minute Tomorrow history and ignores retired realtime`() = runTest {
        val hour = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        val now = hour + 20 * 60_000L
        val realtimeTimestamp = hour - 26 * 60_000L
        val historyTimestamp = hour
        val tomorrowService = DesktopWeatherService(37.4220, -122.0841, WeatherSource.TOMORROW_IO.id)
        val tomorrowRepository = DesktopWeatherRepository(
            tomorrowService,
            dao,
            37.4220,
            -122.0841,
            WeatherSource.TOMORROW_IO.id,
            currentTimeMillis = { now },
        )
        dao.upsertHourlyForecasts(
            37.4220,
            -122.0841,
            WeatherSource.TOMORROW_IO.id,
            listOf(
                HourlyForecast(hour - 3600_000L, 72f, "Forecast", source = WeatherSource.TOMORROW_IO.id),
                HourlyForecast(hour, 75f, "Forecast", source = WeatherSource.TOMORROW_IO.id),
                HourlyForecast(hour + 3600_000L, 78f, "Forecast", source = WeatherSource.TOMORROW_IO.id),
            ),
        )
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = TomorrowIoActuals.REALTIME_STATION_ID,
                    stationName = TomorrowIoActuals.REALTIME_STATION_NAME,
                    timestamp = realtimeTimestamp,
                    temperature = 72.70f,
                    condition = "Older realtime",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = hour - 25 * 60_000L,
                    api = WeatherSource.TOMORROW_IO.id,
                ),
                DesktopObservationEntity(
                    stationId = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID,
                    stationName = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_NAME,
                    timestamp = historyTimestamp,
                    temperature = 75.60f,
                    condition = "Newer history",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = hour + 19 * 60_000L,
                    api = WeatherSource.TOMORROW_IO.id,
                ),
            ),
        )

        try {
            val result = tomorrowRepository.loadCached(now)

            assertNotNull(result)
            assertEquals(historyTimestamp, result!!.resolved.currentObservedAt)
            assertEquals("Newer history", result.resolved.currentCondition)
        } finally {
            tomorrowService.close()
        }
    }

    /**
     * Desktop stopped storing NWS_BLEND (plans/261009-desktop-stops-storing-nws-blend.md): the
     * observed-at / condition come from the blend computed on read from the station rows, as on
     * Android. A row stored before the change must not win — it used to, whatever its age.
     */
    @Test
    fun `loadCached computes the NWS blend on read and ignores a stored leftover`() = runTest {
        val hour = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        val now = hour + 30 * 60_000L
        val service = DesktopWeatherService(37.4220, -122.0841, WeatherSource.NWS.id)
        val nwsRepository = DesktopWeatherRepository(
            service, dao, 37.4220, -122.0841, WeatherSource.NWS.id, currentTimeMillis = { now },
        )
        dao.upsertHourlyForecasts(
            37.4220, -122.0841, WeatherSource.NWS.id,
            listOf(
                HourlyForecast(hour - 3600_000L, 66f, "Forecast", source = WeatherSource.NWS.id),
                HourlyForecast(hour, 67f, "Forecast", source = WeatherSource.NWS.id),
                HourlyForecast(hour + 3600_000L, 68f, "Forecast", source = WeatherSource.NWS.id),
            ),
        )
        fun row(id: String, ts: Long, temp: Float, condition: String, km: Float, type: StationType = StationType.OFFICIAL) =
            DesktopObservationEntity(
                stationId = id, stationName = id, timestamp = ts, temperature = temp,
                condition = condition, locationLat = 37.4220, locationLon = -122.0841,
                distanceKm = km, stationType = type, fetchedAt = ts, api = WeatherSource.NWS.id,
            )
        val knuqTs = now - 5 * 60_000L
        dao.upsertObservations(
            listOf(
                row("KNUQ", knuqTs, 68f, "Clear", km = 3.8f),
                // Nearest station: its condition is the blend's condition.
                row("KPAO", now - 20 * 60_000L, 67f, "Sunny", km = 2.0f),
                // Stored by an older build at the previous fetch.
                row("NWS_BLEND", now - 3 * 3600_000L, 61f, "Stale blend", km = 0f, type = StationType.BLENDED),
            ),
        )

        try {
            val result = nwsRepository.loadCached(now)

            assertNotNull(result)
            assertEquals(knuqTs, result!!.resolved.currentObservedAt)
            assertEquals("Sunny", result.resolved.currentCondition)
        } finally {
            service.close()
        }
    }

    @Test
    fun `loadCached fills missing cloud cover from hourly history`() = runTest {
        val baseTime = System.currentTimeMillis()
        val now = (baseTime / 3600_000L) * 3600_000L

        val liveRows = listOf(
            HourlyForecast(
                dateTime = now - 3600_000L,
                temperature = 70f,
                condition = "Clear",
                cloudCover = null,
                source = "NWS",
                fetchedAt = now,
            ),
            HourlyForecast(
                dateTime = now + 3600_000L,
                temperature = 72f,
                condition = "Sunny",
                cloudCover = 14,
                source = "NWS",
                fetchedAt = now,
            ),
        )
        val historyRows = listOf(
            HourlyForecast(
                dateTime = now - 3600_000L,
                temperature = 68f,
                condition = "Cloudy",
                cloudCover = 82,
                source = "NWS",
                fetchedAt = now - 10_000L,
            ),
        )

        dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", liveRows)
        dao.upsertHourlyForecastHistory(37.4220, -122.0841, "NWS", now - 4 * 3600_000L, historyRows)

        val result = repository.loadCached()
        assertNotNull(result)

        val merged = result!!.raw.hourly.associateBy { it.dateTime }
        // Past hour shows the LATEST forecast: the live row wins for temp/condition, and history only
        // backfills the cloudCover the live row was missing — see HourlyForecastStitcher.
        val repaired = merged[now - 3600_000L]!!
        assertEquals(82, repaired.cloudCover) // backfilled from history (live had null)
        assertEquals(70f, repaired.temperature, 0.0f) // live forecast, not the history snapshot
        assertEquals("Clear", repaired.condition)
        // Future hour keeps the live forecast.
        assertEquals(14, merged[now + 3600_000L]!!.cloudCover)
        assertEquals(72f, merged[now + 3600_000L]!!.temperature, 0.0f)
    }

    @Test
    fun `loadCached fills future gap days with climate normals`() = runTest {
        val today = LocalDate.now()

        // Real forecast covers only today..today+2.
        val realForecast = (0..2).map { offset ->
            val d = today.plusDays(offset.toLong())
            DailyForecast(date = d.toString(), highTemp = 70f + offset, lowTemp = 50f + offset, condition = "Clear")
        }
        dao.upsertForecasts(37.4220, -122.0841, "NWS", realForecast)

        // Cached climate normals (distinct per month so values are recognizable).
        val monthlyHigh = (1..12).associateWith { (it * 5 + 40).toFloat() }
        val monthlyLow = (1..12).associateWith { (it * 5 + 20).toFloat() }
        dao.upsertClimateNormals(ClimateNormals.locationKey(37.4220, -122.0841), monthlyHigh, monthlyLow)

        val daily = repository.loadCached()!!.raw.daily
        val byDate = daily.associateBy { LocalDate.parse(it.date) }

        // Real forecast days are untouched (not climate normals).
        assertFalse(byDate[today]!!.isClimateNormal)
        assertFalse(byDate[today.plusDays(2)]!!.isClimateNormal)
        // The gap beyond real coverage is filled with climate normals...
        val gapDay = today.plusDays(6)
        assertTrue("expected a climate-normal row at $gapDay", byDate[gapDay]?.isClimateNormal == true)
        // ...and its value matches the expanded monthly normal for that calendar day.
        val expected = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)[java.time.MonthDay.from(gapDay)]!!
        assertEquals(expected.first, byDate[gapDay]!!.highTemp!!, 0.001f)
    }

    /**
     * Shared `DailyColumnSource` rule, as on Android: climate filler only after today+2. A source with
     * no rows yet (just enabled) shows today..+2 missing until its fetch lands, not climate averages.
     * plans/261006-daily-future-column-cross-source-snapshot-fallback.md.
     */
    @Test
    fun `loadCached never fills today through plus two with climate normals`() = runTest {
        val today = LocalDate.now()
        val monthlyHigh = (1..12).associateWith { (it * 5 + 40).toFloat() }
        val monthlyLow = (1..12).associateWith { (it * 5 + 20).toFloat() }
        dao.upsertClimateNormals(ClimateNormals.locationKey(37.4220, -122.0841), monthlyHigh, monthlyLow)
        // Only a far-future real row, so today..+2 are uncovered.
        dao.upsertForecasts(
            37.4220, -122.0841, "NWS",
            listOf(DailyForecast(date = today.plusDays(5).toString(), highTemp = 70f, lowTemp = 50f, condition = "Clear")),
        )

        val byDate = repository.loadCached()!!.raw.daily.associateBy { LocalDate.parse(it.date) }

        (0L..2L).forEach { d ->
            assertTrue("no climate filler at today+$d", byDate[today.plusDays(d)]?.isClimateNormal != true)
        }
        assertTrue("climate filler resumes after today+2", byDate[today.plusDays(3)]?.isClimateNormal == true)
    }

    /**
     * A future day with only one value takes the climate normal (Android's DailyFutureDayResolver);
     * NWS's last, low-only day is real data and stays. Desktop used to store such days as low = high
     * or drop them, so it never needed this.
     */
    @Test
    fun `loadCached fills partial future days from normals but keeps the terminal NWS low-only day`() = runTest {
        val today = LocalDate.now()
        dao.upsertForecasts(37.4220, -122.0841, "NWS", (0..4).map { offset ->
            val d = today.plusDays(offset.toLong()).toString()
            when (offset) {
                2 -> DailyForecast(date = d, highTemp = null, lowTemp = 52f, condition = "Clear")
                4 -> DailyForecast(date = d, highTemp = null, lowTemp = 54f, condition = "Clear")
                else -> DailyForecast(date = d, highTemp = 70f + offset, lowTemp = 50f + offset, condition = "Clear")
            }
        })
        val monthlyHigh = (1..12).associateWith { (it * 5 + 40).toFloat() }
        val monthlyLow = (1..12).associateWith { (it * 5 + 20).toFloat() }
        dao.upsertClimateNormals(ClimateNormals.locationKey(37.4220, -122.0841), monthlyHigh, monthlyLow)

        val byDate = repository.loadCached()!!.raw.daily.associateBy { LocalDate.parse(it.date) }

        val middle = byDate.getValue(today.plusDays(2))
        val normal = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)[java.time.MonthDay.from(today.plusDays(2))]!!
        assertTrue("a partial middle day draws the climate normal", middle.isClimateNormal)
        assertEquals(normal.first, middle.highTemp!!, 0.001f)
        assertEquals(normal.second, middle.lowTemp!!, 0.001f)

        val terminal = byDate.getValue(today.plusDays(4))
        assertFalse("the terminal low-only NWS day is real data", terminal.isClimateNormal)
        assertNull(terminal.highTemp)
        assertEquals(54f, terminal.lowTemp!!, 0.001f)
    }

    @Test
    fun `resolveCurrentTempInMemory returns exact same temperature as loadCached`() = runTest {
        val now = System.currentTimeMillis()
        val hourly = listOf(
            HourlyForecast(now - 3600_000L, 70f, "Clear"),
            HourlyForecast(now, 72f, "Clear"),
            HourlyForecast(now + 3600_000L, 74f, "Clear"),
        )
        dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", hourly)

        val obs = listOf(
            DesktopObservationEntity(
                stationId = "STATION_A",
                stationName = "Station A",
                timestamp = now - 3600_000L,
                temperature = 73.3f,
                condition = "Clear",
                locationLat = 37.4220,
                locationLon = -122.0841,
                distanceKm = 0f,
                stationType = StationType.UNKNOWN,
                fetchedAt = now,
                api = "NWS"
            )
        )
        dao.upsertObservations(obs)

        val daily = listOf(
            DailyForecast(LocalDate.now().toString(), 74f, 68f, "Clear", "NWS")
        )
        dao.upsertForecasts(37.4220, -122.0841, "NWS", daily)

        val cachedResult = repository.loadCached(now)
        assertNotNull(cachedResult)

        val inMemoryResult = repository.resolveCurrentTempInMemory(cachedResult!!.raw, now)
        assertNotNull(inMemoryResult)
        assertEquals(cachedResult.resolved.currentTemp, inMemoryResult.displayTemp)
        assertEquals(cachedResult.resolved.appliedDelta, inMemoryResult.appliedDelta)
        assertEquals(cachedResult.resolved.deltaFromYesterday, inMemoryResult.deltaFromYesterday)
    }
}
