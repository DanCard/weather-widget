package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.shared.graph.DaySourceComparison.SortColumn
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.weatherwidget.data.local.HourlyForecastEntity
import java.time.LocalDate

/**
 * The Forecast Comparison "All sources for <day>" card renders the shared [com.weatherwidget.shared.graph.DaySourceComparison]
 * rows from Room through the screen's real Hilt-injected DAOs: sorted by high, short names, the viewed source bold, a missing source last.
 * plans/261105-all-sources-day-comparison.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastComparisonSourcesRoboTest {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private val lat = 37.422
    private val lon = -122.084

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        // The activity's Hilt-injected DAOs read the app singleton, so seed that (never closed:
        // a closed singleton poisons later tests); the seeded site is cleared after.
        db = WeatherDatabase.getDatabase(context)
        runBlocking { db.forecastDao().deleteForecastsAtSite(lat, lon) }
    }

    /**
     * Every activity opened here. A resumed one left behind keeps collecting `dataSeen.reloads()`,
     * so a later test in the same JVM sees its reloads (HistoryReloadOnReturnRoboTest counted 4-5
     * extra `SCREEN_RELOAD` lines from a leaked tomorrow-dated screen, 2026-11-19).
     */
    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<ForecastComparisonActivity>>()

    @After
    fun tearDown() = runBlocking {
        opened.forEach { runCatching { it.pause().stop().destroy() }.onFailure { e -> println("teardown: ${e.message}") } }
        db.forecastDao().deleteForecastsAtSite(lat, lon)
        Unit
    }

    @Test
    fun `card lists every visible source sorted by high with the viewed source bold`() {
        val tomorrow = LocalDate.now().plusDays(1)
        val epoch = tomorrow.toEpochDay() * 86_400_000L
        val now = System.currentTimeMillis()
        runBlocking {
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = epoch, dateOfPrediction = LocalDate.now().toEpochDay() * 86_400_000L,
                        locationLat = lat, locationLon = lon, highTemp = 66f, lowTemp = 50f, condition = "Cloudy",
                        source = WeatherSource.NWS.id, precipProbability = 30, fetchedAt = now - 3_600_000L,
                        lastConfirmedAt = now - 3_600_000L,
                    ),
                    ForecastEntity(
                        targetDate = epoch, dateOfPrediction = LocalDate.now().toEpochDay() * 86_400_000L,
                        locationLat = lat, locationLon = lon, highTemp = 68f, lowTemp = 52f, condition = "Cloudy",
                        source = WeatherSource.OPEN_METEO.id, noonCloudPercent = 40, hourlyDayPrecipMax = 15,
                        hourlyNightPrecipMax = 5, fetchedAt = now - 2 * 3_600_000L,
                        lastConfirmedAt = now - 2 * 3_600_000L,
                    ),
                ),
            )
        }
        WidgetStateManager(context).setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
        )
        val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
            putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, tomorrow.toString())
            putExtra(ForecastComparisonActivity.EXTRA_LAT, lat)
            putExtra(ForecastComparisonActivity.EXTRA_LON, lon)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }
        val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
        opened += controller
        val activity = controller.get()
        controller.setup()

        val table = activity.findViewById<LinearLayout>(R.id.source_comparison_table)
        val deadline = System.currentTimeMillis() + 10_000
        while (table.childCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun cellTexts(row: Int) = (table.getChildAt(row) as LinearLayout).let { r -> (0 until r.childCount).map { (r.getChildAt(it) as TextView).text.toString() } }
        println("ForecastComparisonSourcesRoboTest rows: " + (0 until table.childCount).map { cellTexts(it) })

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.source_comparison_card).visibility)
        assertEquals("header + 3 sources", 4, table.childCount)
        assertEquals(listOf("Meteo", "NWS", "Silur"), (1..3).map { cellTexts(it)[0] })
        val meteo = cellTexts(1)
        assertEquals("68°", meteo[1])
        assertEquals("15%", meteo[3])
        assertEquals("—", meteo[4]) // Peak: no stored peak hour
        assertEquals("5%", meteo[5])
        assertEquals("40%", meteo[6]) // Amount column dropped (no row has one): Cloud moves to index 6.
        assertEquals("—", meteo[7]) // no accuracy history in the seeded database
        assertEquals("2h", meteo[8])
        assertEquals(listOf("—", "—", "—"), cellTexts(3).subList(1, 4))
        val nwsSource = (table.getChildAt(2) as LinearLayout).getChildAt(0) as TextView
        assertTrue("viewed source is bold", nwsSource.typeface?.style?.and(Typeface.BOLD) == Typeface.BOLD)
        val meteoSource = (table.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        assertTrue("other sources are not bold", (meteoSource.typeface?.style ?: 0) and Typeface.BOLD == 0)
        // Legend: the shared column choice (no Amount: its column is dropped here), desktop's wording.
        val legend = activity.findViewById<TextView>(R.id.source_comparison_legend).text.toString()
        println("ForecastComparisonSourcesRoboTest legend: $legend")
        assertEquals(listOf(SortColumn.DAY_RAIN, SortColumn.PEAK, SortColumn.NIGHT_RAIN, SortColumn.CLOUD, SortColumn.BIAS, SortColumn.AGE),
            DaySourceComparison.legendColumns(showAmount = false))
        assertEquals(
            "Rain highest hourly chance, 8am–8pm\nPeak hour of the highest daytime chance\n" +
                "Night highest hourly chance, 8pm–8am\nCloud cloud cover at noon\n" +
                "Bias average forecast high minus actual, last 30 days (+ = ran warm)\n" +
                "Age time since the source last confirmed this forecast; grey after 24 h",
            legend,
        )
    }

    @Test
    fun `tapping a header sorts by that column, reverses on a second tap, and is remembered on the next open`() {
        val tomorrow = LocalDate.now().plusDays(1)
        val epoch = tomorrow.toEpochDay() * 86_400_000L
        val now = System.currentTimeMillis()
        // NWS: hotter but drier; Open-Meteo: cooler but wetter.
        runBlocking {
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = epoch, dateOfPrediction = LocalDate.now().toEpochDay() * 86_400_000L,
                        locationLat = lat, locationLon = lon, highTemp = 70f, lowTemp = 50f, condition = "Cloudy",
                        source = WeatherSource.NWS.id, hourlyDayPrecipMax = 10, fetchedAt = now - 3_600_000L,
                        lastConfirmedAt = now - 3_600_000L,
                    ),
                    ForecastEntity(
                        targetDate = epoch, dateOfPrediction = LocalDate.now().toEpochDay() * 86_400_000L,
                        locationLat = lat, locationLon = lon, highTemp = 68f, lowTemp = 52f, condition = "Cloudy",
                        source = WeatherSource.OPEN_METEO.id, hourlyDayPrecipMax = 60, fetchedAt = now - 2 * 3_600_000L,
                        lastConfirmedAt = now - 2 * 3_600_000L,
                    ),
                ),
            )
        }
        val state = WidgetStateManager(context)
        state.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        state.setSourceCompareSort(com.weatherwidget.shared.graph.DaySourceComparison.Sort.DEFAULT.encode())

        fun open(): LinearLayout {
            val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
                putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, tomorrow.toString())
                putExtra(ForecastComparisonActivity.EXTRA_LAT, lat)
                putExtra(ForecastComparisonActivity.EXTRA_LON, lon)
                putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
            }
            val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
            opened += controller
            controller.setup()
            val table = controller.get().findViewById<LinearLayout>(R.id.source_comparison_table)
            val deadline = System.currentTimeMillis() + 10_000
            while (table.childCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            return table
        }
        fun cell(table: LinearLayout, row: Int, col: Int) = ((table.getChildAt(row) as LinearLayout).getChildAt(col) as TextView)
        fun order(table: LinearLayout) = (1 until table.childCount).map { cell(table, it, 0).text.toString() }
            .also { println("ForecastComparisonSourcesRoboTest order: $it header=${(0 until (table.getChildAt(0) as LinearLayout).childCount).map { c -> cell(table, 0, c).text }}") }

        val table = open()
        assertEquals(listOf("NWS", "Meteo", "Silur"), order(table))
        assertEquals("High ▾", cell(table, 0, 1).text.toString())

        cell(table, 0, 3).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Meteo", "NWS", "Silur"), order(table))
        assertEquals("Rain ▾", cell(table, 0, 3).text.toString())
        assertEquals("High", cell(table, 0, 1).text.toString())

        cell(table, 0, 3).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("blank stays last when reversed", listOf("NWS", "Meteo", "Silur"), order(table))
        assertEquals("Rain ▴", cell(table, 0, 3).text.toString())
        assertEquals("DAY_RAIN:asc", state.sourceCompareSort())

        // A fresh open applies the remembered sort.
        val reopened = open()
        assertEquals(listOf("NWS", "Meteo", "Silur"), order(reopened))
        assertEquals("Rain ▴", cell(reopened, 0, 3).text.toString())
    }

    @Test
    fun `today - a source whose later fetch left today out gets a starred age and the footnote`() {
        // Desktop 2026-11-13: Silurian today 12:10, tomorrow 16:59 (UTC days after 4 PM Pacific).
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        fun row(source: WeatherSource, dayOffset: Long, high: Float, ageMs: Long) = ForecastEntity(
            targetDate = today.plusDays(dayOffset).toEpochDay() * 86_400_000L, dateOfPrediction = today.toEpochDay() * 86_400_000L,
            locationLat = lat, locationLon = lon, highTemp = high, lowTemp = 50f, condition = "Cloudy",
            source = source.id, fetchedAt = now - ageMs, lastConfirmedAt = now - ageMs,
        )
        runBlocking {
            db.forecastDao().insertAll(
                listOf(
                    row(WeatherSource.NWS, 0, 63f, 60_000L), row(WeatherSource.NWS, 1, 65f, 60_000L),
                    row(WeatherSource.SILURIAN, 0, 64f, 5 * 3_600_000L), row(WeatherSource.SILURIAN, 1, 66f, 12 * 60_000L),
                ),
            )
        }
        WidgetStateManager(context).setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.SILURIAN))
        val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
            putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, today.toString())
            putExtra(ForecastComparisonActivity.EXTRA_LAT, lat)
            putExtra(ForecastComparisonActivity.EXTRA_LON, lon)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }
        val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
        opened += controller
        val activity = controller.get()
        controller.setup()
        val table = activity.findViewById<LinearLayout>(R.id.source_comparison_table)
        val deadline = System.currentTimeMillis() + 10_000
        while (table.childCount < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun cellTexts(row: Int) = (table.getChildAt(row) as LinearLayout).let { r -> (0 until r.childCount).map { (r.getChildAt(it) as TextView).text.toString() } }
        val rows = (1 until table.childCount).map { cellTexts(it) }
        println("ForecastComparisonSourcesRoboTest today rows: $rows")
        assertEquals("5h*", rows.single { it[0] == "Silur" }.last())
        assertEquals("1m", rows.single { it[0] == "NWS" }.last())
        val legend = activity.findViewById<TextView>(R.id.source_comparison_legend).text.toString()
        println("ForecastComparisonSourcesRoboTest today legend: $legend")
        assertTrue(legend, legend.endsWith("grey after 24 h\n* " + context.getString(R.string.source_compare_legend_star)))
    }

    /**
     * Pixel 7 Pro width (411 dp at 420 dpi, real font metrics): seven sources with decimal lows and an
     * Open-Meteo 8am–8pm hourly amount still fit every column, Amount included, with decimals kept.
     * plans/261117-all-sources-content-sized-columns.md
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `phone-width table fits the Amount column with decimal lows`() {
        // Its own site, so these hourly rows never reach the other tests' tables.
        val siteLat = 40.015
        val siteLon = -105.27
        val tomorrow = LocalDate.now().plusDays(1)
        val epoch = tomorrow.toEpochDay() * 86_400_000L
        val now = System.currentTimeMillis()
        val sources = listOf(
            WeatherSource.OPEN_WEATHER_MAP to (58.0f to 48.2f), WeatherSource.NWS to (60f to 47f),
            WeatherSource.GOOGLE_WEATHER to (55.8f to 53f), WeatherSource.WEATHER_API to (55.1f to 51.2f),
            WeatherSource.TOMORROW_IO to (54f to 49.4f), WeatherSource.SILURIAN to (54.5f to 50.9f),
            WeatherSource.OPEN_METEO to (53.7f to 47f),
        )
        val zone = java.time.ZoneId.systemDefault()
        try {
            runBlocking {
                db.forecastDao().insertAll(
                    sources.map { (source, temps) ->
                        ForecastEntity(
                            targetDate = epoch, dateOfPrediction = LocalDate.now().toEpochDay() * 86_400_000L,
                            locationLat = siteLat, locationLon = siteLon, highTemp = temps.first, lowTemp = temps.second,
                            condition = "Rain", source = source.id, noonCloudPercent = 100, hourlyDayPrecipMax = 100,
                            hourlyNightPrecipMax = 100, fetchedAt = now - 60_000L, lastConfirmedAt = now - 60_000L,
                        )
                    },
                )
                db.hourlyForecastDao().insertAll(
                    (8 until 20).map { hour ->
                        HourlyForecastEntity(
                            dateTime = tomorrow.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
                            locationLat = siteLat, locationLon = siteLon, temperature = 52f, condition = "Rain",
                            source = WeatherSource.OPEN_METEO.id, precipProbability = 100, precipAmountMm = 2f,
                            fetchedAt = now - 60_000L,
                        )
                    },
                )
            }
            WidgetStateManager(context).setVisibleSourcesOrder(sources.map { it.first })
            WidgetStateManager(context).setSourceCompareSort(DaySourceComparison.Sort.DEFAULT.encode())
            val intent = Intent(context, ForecastComparisonActivity::class.java).apply {
                putExtra(ForecastComparisonActivity.EXTRA_TARGET_DATE, tomorrow.toString())
                putExtra(ForecastComparisonActivity.EXTRA_LAT, siteLat)
                putExtra(ForecastComparisonActivity.EXTRA_LON, siteLon)
                putExtra(ForecastComparisonActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
            }
            val controller = Robolectric.buildActivity(ForecastComparisonActivity::class.java, intent)
            opened += controller
            val activity = controller.get()
            controller.setup()
            val table = activity.findViewById<LinearLayout>(R.id.source_comparison_table)
            val deadline = System.currentTimeMillis() + 10_000
            while (table.childCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            println("ForecastComparisonSourcesRoboTest phone widthDp=${activity.resources.configuration.screenWidthDp} density=${activity.resources.displayMetrics.density}")
            fun cells(row: Int) = (table.getChildAt(row) as LinearLayout).let { r -> (0 until r.childCount).map { r.getChildAt(it) as TextView } }
            (0 until table.childCount).forEach { row ->
                println("ForecastComparisonSourcesRoboTest phone row $row: " + cells(row).map { "${it.text}@${it.layoutParams.width}" })
            }

            val header = cells(0).map { it.text.toString() }
            assertEquals("all ten columns", 10, header.size)
            assertEquals("Amount", header[6])
            val owm = cells((1 until table.childCount).first { cells(it)[0].text == "OWM" }).map { it.text.toString() }
            assertEquals("48.2°", owm[2]) // decimals kept: rounding is only a fallback
            val meteo = cells((1 until table.childCount).first { cells(it)[0].text == "Meteo" }).map { it.text.toString() }
            assertTrue("Open-Meteo amount ${meteo[6]}", meteo[6] != "—")
            // Every cell's text fits its width (no clipping), and the row fits the table.
            val tablePx = activity.resources.displayMetrics.widthPixels - (48 * activity.resources.displayMetrics.density).toInt()
            (0 until table.childCount).forEach { row ->
                val views = cells(row)
                assertTrue("row $row fits", views.sumOf { it.layoutParams.width } <= tablePx)
                views.forEach { v -> assertTrue("'${v.text}' fits ${v.layoutParams.width}", v.paint.measureText(v.text.toString()) <= v.layoutParams.width) }
            }
            val legend = activity.findViewById<TextView>(R.id.source_comparison_legend).text.toString()
            assertTrue(legend, legend.contains("Amount "))
        } finally {
            runBlocking { db.forecastDao().deleteForecastsAtSite(siteLat, siteLon) }
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM hourly_forecasts WHERE locationLat = ? AND locationLon = ?", arrayOf<Any>(siteLat, siteLon),
            )
        }
    }
}
