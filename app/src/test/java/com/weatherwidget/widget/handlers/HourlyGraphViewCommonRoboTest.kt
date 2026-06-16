package com.weatherwidget.widget.handlers

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the shared text-mode binder produces the same per-cell strings the two handlers produced
 * before extraction: the per-graph value when a forecast is present, "--%" when it is absent.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HourlyGraphViewCommonRoboTest {

    private lateinit var context: Context
    private val source = WeatherSource.NWS
    private val center = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun forecastAt(time: LocalDateTime, cloud: Int?, precip: Int?): HourlyForecastEntity {
        val ms = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return HourlyForecastEntity(
            dateTime = ms,
            locationLat = 0.0,
            locationLon = 0.0,
            temperature = 60f,
            condition = "Cloudy",
            source = source.id,
            precipProbability = precip,
            cloudCover = cloud,
            precipAmountMm = null,
            fetchedAt = ms,
        )
    }

    private fun applyAndRead(views: RemoteViews): View {
        val applied = views.apply(context, FrameLayout(context) as ViewGroup)
        return applied
    }

    private fun textOf(root: View, id: Int): String = root.findViewById<TextView>(id).text.toString()

    @Test
    fun cloudValueLambda_showsPercentWhenPresent_andDashesWhenAbsent() {
        // Column 0 = now (cloud present), column 1 = now+3h (absent → no row).
        val forecasts = listOf(forecastAt(center, cloud = 60, precip = null))
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        HourlyGraphViewCommon.bindHourlyTextMode(views, forecasts, center, numColumns = 2, source) {
            it?.cloudCover?.let { c -> "$c%" } ?: "--%"
        }

        val root = applyAndRead(views)
        assertEquals("60%", textOf(root, R.id.day1_high))
        assertEquals("--%", textOf(root, R.id.day2_high)) // +6h has no forecast
    }

    @Test
    fun precipValueLambda_showsPercentWhenPresent_andZeroWhenNullProbability() {
        // Present forecast with null precipProbability → "0%"; absent column → "--%".
        val forecasts = listOf(forecastAt(center, cloud = null, precip = null))
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        HourlyGraphViewCommon.bindHourlyTextMode(views, forecasts, center, numColumns = 2, source) {
            if (it != null) "${it.precipProbability ?: 0}%" else "--%"
        }

        val root = applyAndRead(views)
        assertEquals("0%", textOf(root, R.id.day1_high))
        assertEquals("--%", textOf(root, R.id.day2_high))
    }

    @Test
    fun unusedColumnsAreHidden_whenFewerOffsetsThanContainers() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        // numColumns = 1 → a single offset (0), so containers 2..6 must be GONE.
        HourlyGraphViewCommon.bindHourlyTextMode(views, emptyList(), center, numColumns = 1, source) { "--%" }

        val root = applyAndRead(views)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.day1_container).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.day2_container).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.day6_container).visibility)
    }
}
