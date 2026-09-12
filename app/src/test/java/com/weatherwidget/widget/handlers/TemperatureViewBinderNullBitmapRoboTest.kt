package com.weatherwidget.widget.handlers

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * The temperature view's "text mode" is the 1-row layout. It was also the fallback for a failed
 * graph render — but `text_container` is the daily text layout, and nothing on the temperature
 * path fills it, so that fallback pushed an empty body (widget #88 blank for four minutes,
 * 2026-09-12; plans/260912-cancelled-temperature-render-pushes-blank-body.md).
 *
 * Contract under test: with `useGraph` set and no bitmap, the binder leaves the graph views alone
 * so the previous good frame stays on screen; the genuine 1-row case still switches to text.
 *
 * Uses reapply() onto a view that already shows a graph — the state a real widget is in when the
 * failing paint arrives — because RemoteViews are deltas and only the flips matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureViewBinderNullBitmapRoboTest {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val widgetId = 988
    private val now = LocalDateTime.of(2026, 9, 12, 12, 10)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
    }

    @Test
    fun `failed graph render keeps the previous bitmap on screen`() {
        val shown = bindOntoGraphShowing(graphState(useGraph = true, showTextMode = false))

        assertEquals(View.VISIBLE, shown.findViewById<View>(R.id.graph_view).visibility)
        assertEquals(View.GONE, shown.findViewById<View>(R.id.text_container).visibility)
    }

    @Test
    fun `one-row layout still switches to text mode`() {
        val shown = bindOntoGraphShowing(graphState(useGraph = false, showTextMode = true))

        assertEquals(View.GONE, shown.findViewById<View>(R.id.graph_view).visibility)
        assertEquals(View.VISIBLE, shown.findViewById<View>(R.id.text_container).visibility)
    }

    private fun graphState(useGraph: Boolean, showTextMode: Boolean) =
        TemperatureWidgetState(
            appWidgetId = widgetId,
            numRows = if (useGraph) 4 else 1,
            widthDp = 373,
            header = TemperatureWidgetState.HeaderState(
                sourceIndicator = "NWS",
                iconRes = R.drawable.ic_weather_clear,
                currentTemp = "74°",
                currentTempSizeDp = 30f,
                deltaText = null,
                deltaColor = 0,
                precipProbability = null,
                precipTextSizeDp = 14f,
                isPrecipVisible = false,
                isCurrentTempVisible = true,
                isDeltaVisible = false,
                isStaleEstimate = false,
            ),
            graph = TemperatureWidgetState.GraphState(
                useGraph = useGraph,
                bitmap = null,
                hourData = emptyList(),
                showTextMode = showTextMode,
            ),
            warning = null,
            displaySource = WeatherSource.NWS,
            zoom = stateManager.getZoomWindow(widgetId),
            hourlyOffset = 0,
        )

    /** Inflates the widget as a prior good paint left it (graph up, text hidden), then reapplies the bind. */
    private fun bindOntoGraphShowing(state: TemperatureWidgetState): View {
        val root = FrameLayout(context)
        val shown = RemoteViews(context.packageName, R.layout.widget_weather).apply(context, root)
        shown.findViewById<View>(R.id.graph_view).visibility = View.VISIBLE
        shown.findViewById<View>(R.id.text_container).visibility = View.GONE

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        TemperatureViewBinder.bind(
            context = context,
            views = views,
            state = state,
            stateManager = stateManager,
            centerTime = now,
            hourlyForecasts = emptyList(),
        )
        views.reapply(context, shown)
        return shown
    }
}
