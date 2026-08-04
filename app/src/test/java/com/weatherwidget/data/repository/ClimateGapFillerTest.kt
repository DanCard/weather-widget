package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetQueryWindows
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ClimateGapFillerTest {
    private lateinit var db: WeatherDatabase
    private lateinit var gapFiller: ClimateGapFiller

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val locationKey = ClimateNormals.locationKey(lat, lon)
    private val today = LocalDate.now()
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        gapFiller = ClimateGapFiller(db.climateNormalDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedNormals(high: Float = 70f, low: Float = 50f) {
        db.climateNormalDao().insertAll(
            (1..12).map { month ->
                ClimateNormalEntity(
                    monthDay = "${month.toString().padStart(2, '0')}-15",
                    locationKey = locationKey,
                    highTemp = high,
                    lowTemp = low,
                )
            },
        )
    }

    private fun targetDateOf(row: com.weatherwidget.data.local.ForecastEntity): LocalDate =
        LocalDate.ofEpochDay(row.targetDate / WidgetConstants.MS_IN_A_DAY)

    @Test
    fun `gapRows returns empty when normals not cached (offline-safe)`() = runTest {
        val gaps = gapFiller.gapRows(lat, lon, coveredDates = emptySet(), today, horizonDays = 3)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `gapRows fills today through horizon with cached normals`() = runTest {
        seedNormals()

        val gaps = gapFiller.gapRows(lat, lon, coveredDates = emptySet(), today, horizonDays = 3)

        assertEquals((0..3).map { today.plusDays(it.toLong()) }, gaps.map(::targetDateOf))
    }

    @Test
    fun `gapRows produces the expected field values`() = runTest {
        seedNormals(high = 70f, low = 50f)

        val gap = gapFiller.gapRows(lat, lon, coveredDates = emptySet(), today, horizonDays = 0).single()

        assertEquals(WeatherSource.GENERIC_GAP.id, gap.source)
        assertEquals(0L, gap.fetchedAt)
        assertEquals(0L, gap.batchFetchedAt)
        assertTrue(gap.isClimateNormal)
        assertEquals(70f, gap.highTemp)
        assertEquals(50f, gap.lowTemp)
        assertEquals(LocationMatch.quantize(lat), gap.locationLat, 0.0)
        assertEquals(LocationMatch.quantize(lon), gap.locationLon, 0.0)
    }

    @Test
    fun `appendGaps fills after min-across-sources coverage, not any-row coverage`() = runTest {
        seedNormals()
        // NWS covers only through today+2; Open-Meteo already covers through today+5.
        val rows = listOf(
            TestData.forecast(targetDate = todayStr, source = "NWS", lat = lat, lon = lon),
            TestData.forecast(targetDate = today.plusDays(1).toString(), source = "NWS", lat = lat, lon = lon),
            TestData.forecast(targetDate = today.plusDays(2).toString(), source = "NWS", lat = lat, lon = lon),
            TestData.forecast(targetDate = todayStr, source = "OPEN_METEO", lat = lat, lon = lon),
            TestData.forecast(targetDate = today.plusDays(5).toString(), source = "OPEN_METEO", lat = lat, lon = lon),
        )

        val result = gapFiller.appendGaps(rows, lat, lon, today, horizonDays = 5)

        val gapDates = result.filter { it.source == WeatherSource.GENERIC_GAP.id }.map(::targetDateOf).toSet()
        // Fallback is still added for day+3..+5 even though Open-Meteo already has real rows there,
        // because NWS (the shorter-coverage source) needs its own fallback for those days.
        assertEquals(setOf(today.plusDays(3), today.plusDays(4), today.plusDays(5)), gapDates)
    }

    @Test
    fun `appendGaps at the shared daily horizon covers the eighth day past NWS coverage`() = runTest {
        seedNormals()
        // The reported case: NWS is the display source and covers today..today+7, while a
        // 10-column widget renders out to today+8.
        val rows = (0..7).map {
            TestData.forecast(
                targetDate = today.plusDays(it.toLong()).toString(),
                source = "NWS",
                lat = lat,
                lon = lon,
            )
        }
        val eighthDay = today.plusDays(8)

        val atRenderHorizon =
            gapFiller.appendGaps(rows, lat, lon, today, WidgetQueryWindows.DAILY_FORECAST_DAYS)
        val atOldStartupHorizon = gapFiller.appendGaps(rows, lat, lon, today, horizonDays = 7L)

        assertTrue(
            "today+8 needs a GENERIC_GAP row; without one the icon falls back to " +
                "ic_weather_unknown (a grey cloud) and the bar to slate-grey FORECAST_CLOUDY",
            atRenderHorizon.any {
                it.source == WeatherSource.GENERIC_GAP.id && targetDateOf(it) == eighthDay
            },
        )
        // Pins the actual defect: the old startup/worker horizon left that column with no row at all.
        assertTrue(
            "horizonDays=7 must not cover today+8 (this is what the fix changed)",
            atOldStartupHorizon.none { targetDateOf(it) == eighthDay },
        )
    }

    @Test
    fun `appendGaps with no real rows fills from today`() = runTest {
        seedNormals()

        val result = gapFiller.appendGaps(emptyList(), lat, lon, today, horizonDays = 2)

        assertEquals(3, result.size)
        assertTrue(result.all { it.source == WeatherSource.GENERIC_GAP.id })
    }

    @Test
    fun `appendGaps dedupes against a leftover persisted gap row`() = runTest {
        seedNormals()
        val leftoverGap = TestData.forecast(
            targetDate = today.plusDays(3).toString(),
            source = WeatherSource.GENERIC_GAP.id,
            isClimateNormal = true,
            lat = lat,
            lon = lon,
        )
        val rows = listOf(
            TestData.forecast(targetDate = todayStr, source = "NWS", lat = lat, lon = lon),
            leftoverGap,
        )

        val result = gapFiller.appendGaps(rows, lat, lon, today, horizonDays = 4)

        val gapRowsForDay3 = result.filter {
            it.source == WeatherSource.GENERIC_GAP.id && targetDateOf(it) == today.plusDays(3)
        }
        assertEquals(1, gapRowsForDay3.size) // no duplicate generated alongside the leftover row
    }

    @Test
    fun `appendGaps is idempotent`() = runTest {
        seedNormals()
        val rows = listOf(TestData.forecast(targetDate = todayStr, source = "NWS", lat = lat, lon = lon))

        val once = gapFiller.appendGaps(rows, lat, lon, today, horizonDays = 3)
        val twice = gapFiller.appendGaps(once, lat, lon, today, horizonDays = 3)

        assertEquals(once.size, twice.size)
    }

    @Test
    fun `coveredDates is min-across-sources max targetDate, plus existing gap dates`() {
        val rows = listOf(
            TestData.forecast(targetDate = todayStr, source = "NWS", lat = lat, lon = lon),
            TestData.forecast(targetDate = today.plusDays(1).toString(), source = "NWS", lat = lat, lon = lon),
            TestData.forecast(targetDate = today.plusDays(5).toString(), source = "OPEN_METEO", lat = lat, lon = lon),
            TestData.forecast(
                targetDate = today.plusDays(9).toString(),
                source = WeatherSource.GENERIC_GAP.id,
                isClimateNormal = true,
                lat = lat,
                lon = lon,
            ),
        )

        val covered = ClimateGapFiller.coveredDates(rows, today, horizonDays = 10)

        assertEquals(setOf(today, today.plusDays(1), today.plusDays(9)), covered)
    }

    @Test
    fun `coveredDates with no real rows is empty`() {
        assertTrue(ClimateGapFiller.coveredDates(emptyList(), today, horizonDays = 5).isEmpty())
    }
}
