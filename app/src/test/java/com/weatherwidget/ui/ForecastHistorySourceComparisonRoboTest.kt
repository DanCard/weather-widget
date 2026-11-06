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
import java.time.LocalDate

/**
 * The Forecast History "All sources for <day>" card renders the shared [com.weatherwidget.shared.graph.DaySourceComparison]
 * rows from Room through the screen's real Hilt-injected DAOs: sorted by high, short names, the viewed source bold, a missing source last.
 * plans/261105-all-sources-day-comparison.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastHistorySourceComparisonRoboTest {
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

    @After
    fun tearDown() = runBlocking { db.forecastDao().deleteForecastsAtSite(lat, lon); Unit }

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
        val intent = Intent(context, ForecastHistoryActivity::class.java).apply {
            putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, tomorrow.toString())
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }
        val controller = Robolectric.buildActivity(ForecastHistoryActivity::class.java, intent)
        val activity = controller.get()
        controller.setup()

        val table = activity.findViewById<LinearLayout>(R.id.source_comparison_table)
        val deadline = System.currentTimeMillis() + 10_000
        while (table.childCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun cellTexts(row: Int) = (table.getChildAt(row) as LinearLayout).let { r -> (0 until r.childCount).map { (r.getChildAt(it) as TextView).text.toString() } }
        println("ForecastHistorySourceComparisonRoboTest rows: " + (0 until table.childCount).map { cellTexts(it) })

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.source_comparison_card).visibility)
        assertEquals("header + 3 sources", 4, table.childCount)
        assertEquals(listOf("Meteo", "NWS", "Silur"), (1..3).map { cellTexts(it)[0] })
        val meteo = cellTexts(1)
        assertEquals("68°", meteo[1])
        assertEquals("15%", meteo[3])
        assertEquals("5%", meteo[4])
        assertEquals("40%", meteo[5]) // Amount column dropped (no row has one): Cloud moves to index 5.
        assertEquals("—", meteo[6]) // no accuracy history in the seeded database
        assertEquals("2h", meteo[7])
        assertEquals(listOf("—", "—", "—"), cellTexts(3).subList(1, 4))
        val nwsSource = (table.getChildAt(2) as LinearLayout).getChildAt(0) as TextView
        assertTrue("viewed source is bold", nwsSource.typeface?.style?.and(Typeface.BOLD) == Typeface.BOLD)
        val meteoSource = (table.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        assertTrue("other sources are not bold", (meteoSource.typeface?.style ?: 0) and Typeface.BOLD == 0)
    }
}
