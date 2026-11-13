package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.local.HourlyForecastSql
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * plans/261113-nws-day-amount-null-elapsed-hour.md, on real SQLite: the newest-row-per-hour reads
 * return the newest non-null rain amount when the newest row has none. NWS Fri 2026-11-13: the
 * 09:57 fetch's null 08:00 hour sat over the 08:10 fetch's 0.0847 mm and blanked the day's amount.
 */
@Category(ShortDuration::class)
class HourlyForecastSqlTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.417
    private val lon = -122.089
    private val hour = 1_780_682_400_000L

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("hourly_sql_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    /** One fetch's row for [dateTime], filed under its own bucket as every fetch's rows are. */
    private fun file(source: String, dateTime: Long, fetchedAt: Long, amount: Float?, temp: Float = 60f) =
        dao.upsertHourlyForecastHistory(
            lat, lon, source, fetchedAt,
            listOf(HourlyForecast(dateTime, temp, "Clear", precipAmountMm = amount, fetchedAt = fetchedAt)),
        )

    @Test
    fun `a newest row with no amount reads the newest stored amount, on every newest-per-hour read`() {
        val now = System.currentTimeMillis()
        file("NWS", hour, now - 3_000, amount = 0.5f)
        file("NWS", hour, now - 2_000, amount = 0.08f)
        file("NWS", hour, now - 1_000, amount = null, temp = 66f)

        val bySource = dao.getHourlyForecasts(lat, lon, "NWS", hour, hour).single()
        val allSources = dao.getHourlyForecastsAllSources(lat, lon, hour, hour).single()
        val latest = dao.getLatestHourly(lat, lon, "NWS", 60_000L).single()

        for (row in listOf(bySource, allSources, latest)) {
            assertEquals(66f, row.temperature, 0f) // still the newest fetch's row
            assertEquals(0.08f, row.precipAmountMm!!, 0f)
        }
    }

    @Test
    fun `an amount of zero is a real amount and is not replaced`() {
        val now = System.currentTimeMillis()
        file("NWS", hour, now - 2_000, amount = 0.5f)
        file("NWS", hour, now - 1_000, amount = 0f)

        assertEquals(0f, dao.getHourlyForecasts(lat, lon, "NWS", hour, hour).single().precipAmountMm!!, 0f)
    }

    @Test
    fun `an hour no fetch has an amount for stays null and other sources never lend theirs`() {
        val now = System.currentTimeMillis()
        file("NWS", hour, now - 1_000, amount = null)
        file("GOOGLE_WEATHER", hour, now - 2_000, amount = 9f)

        assertNull(dao.getHourlyForecasts(lat, lon, "NWS", hour, hour).single().precipAmountMm)
    }

    /** The fragment lists columns by hand: a column added to the table must be added there too. */
    @Test
    fun `the newest-row column list is exactly the table's columns`() {
        val tableColumns = db.getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM pragma_table_info('hourly_forecasts')").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        // Split on top-level commas: the COALESCE(…) subquery holds none outside its parentheses.
        val listed = buildList {
            var depth = 0
            val current = StringBuilder()
            for (c in HourlyForecastSql.NEWEST_ROW_COLUMNS) {
                when {
                    c == '(' -> { depth++; current.append(c) }
                    c == ')' -> { depth--; current.append(c) }
                    c == ',' && depth == 0 -> { add(current.toString().trim()); current.clear() }
                    else -> current.append(c)
                }
            }
            add(current.toString().trim())
        }.map { it.substringAfterLast(" AS ") }

        assertEquals(tableColumns.sorted(), listed.sorted())
    }
}
