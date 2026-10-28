package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetInteractionReceiver
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf

/**
 * Architectural contract test: verifies that all interactive user touch targets route to
 * [WidgetInteractionReceiver] and never to [WidgetActionReceiver].
 *
 * Routing user interactions to [WidgetActionReceiver] causes ActivityManagerService to queue
 * user taps behind long-running passive background maintenance broadcasts.
 */
@Category(MediumDuration::class)
class WidgetTouchTargetRoutingContractTest : RobolectricTest() {

    private lateinit var context: Context
    private val widgetId = 345

    private class CapturingViews(pkg: String) : RemoteViews(pkg, R.layout.widget_weather) {
        val clickIntents = mutableMapOf<Int, PendingIntent>()

        override fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?) {
            if (pendingIntent != null) {
                clickIntents[viewId] = pendingIntent
            }
            super.setOnClickPendingIntent(viewId, pendingIntent)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `toggle source and dead zone catch-all route to WidgetInteractionReceiver`() {
        val views = CapturingViews(context.packageName)
        setupDeadZoneCatchAll(context, views, widgetId)
        setupApiToggle(context, views, widgetId, numRows = 4)

        val deadZonePending = views.clickIntents[R.id.widget_root]
        assertNotNull("widget_root must have a click PendingIntent", deadZonePending)
        val deadZoneIntent = shadowOf(deadZonePending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, deadZoneIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, deadZoneIntent.component?.className)
        assertEquals(WidgetActions.ACTION_TOGGLE_API, deadZoneIntent.action)

        val apiZonePending = views.clickIntents[R.id.api_touch_zone]
        assertNotNull("api_touch_zone must have a click PendingIntent", apiZonePending)
        val apiIntent = shadowOf(apiZonePending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, apiIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, apiIntent.component?.className)
        assertEquals(WidgetActions.ACTION_TOGGLE_API, apiIntent.action)
    }

    @Test
    fun `hourly navigation buttons route to WidgetInteractionReceiver`() {
        val views = CapturingViews(context.packageName)
        val stateManager = WidgetStateManager(context)
        setupNavigationButtons(context, views, widgetId, stateManager)

        val leftPending = views.clickIntents[R.id.nav_left]
        assertNotNull("nav_left must have a click PendingIntent", leftPending)
        val leftIntent = shadowOf(leftPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, leftIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, leftIntent.component?.className)

        val rightPending = views.clickIntents[R.id.nav_right]
        assertNotNull("nav_right must have a click PendingIntent", rightPending)
        val rightIntent = shadowOf(rightPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, rightIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, rightIntent.component?.className)
    }

    @Test
    fun `daily click intent routes to WidgetInteractionReceiver`() {
        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = widgetId,
            dayIndex = 0,
            date = LocalDate.now(),
            iconRes = null,
            lat = 37.42,
            lon = -122.08,
            displaySource = WeatherSource.NWS,
        )

        assertEquals(WidgetInteractionReceiver::class.java.name, intent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, intent.component?.className)
        assertEquals(WidgetActions.ACTION_DAY_CLICK, intent.action)
    }

    @Test
    fun `header tap targets route to WidgetInteractionReceiver`() {
        val views = CapturingViews(context.packageName)
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, widgetId)
        HeaderTapTargetHelper.bindGraphSelector(context, views, widgetId, ViewMode.CLOUD_COVER)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, widgetId)

        val tempPending = views.clickIntents[R.id.current_temp]
        assertNotNull("current_temp must have a click PendingIntent", tempPending)
        val tempIntent = shadowOf(tempPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, tempIntent.component?.className)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, tempIntent.action)

        val selectorPending = views.clickIntents[R.id.graph_selector_icon]
        assertNotNull("graph_selector_icon must have a click PendingIntent", selectorPending)
        val selectorIntent = shadowOf(selectorPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, selectorIntent.component?.className)
        assertEquals(WidgetActions.ACTION_SET_VIEW, selectorIntent.action)

        val precipPending = views.clickIntents[R.id.precip_probability]
        assertNotNull("precip_probability must have a click PendingIntent", precipPending)
        val precipIntent = shadowOf(precipPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, precipIntent.component?.className)
        assertEquals(WidgetActions.ACTION_TOGGLE_PRECIP, precipIntent.action)
    }

    @Test
    fun `api source warning tap routes to WidgetInteractionReceiver`() {
        val views = CapturingViews(context.packageName)
        val warning = ApiSourceWarningHelper.SourceWarning(
            headerText = "Warning",
            summaryText = "Summary",
            detailText = "Detail",
            sourceLabelText = "NWS",
            toastMessage = "Toast",
        )
        ApiSourceWarningHelper.renderSourceWarningState(context, views, widgetId, warning)

        val warningPending = views.clickIntents[R.id.widget_warning_container]
        assertNotNull("widget_warning_container must have a click PendingIntent", warningPending)
        val warningIntent = shadowOf(warningPending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, warningIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, warningIntent.component?.className)
        assertEquals(WidgetActions.ACTION_SHOW_TOAST, warningIntent.action)
    }

    @Test
    fun `zoom tap zones route to WidgetInteractionReceiver`() {
        val views = CapturingViews(context.packageName)
        setupZoomTapZones(
            context = context,
            views = views,
            appWidgetId = widgetId,
            zoom = com.weatherwidget.shared.graph.ZoomStage.WIDE.window(),
            hourlyOffset = 0,
        )

        val zonePending = views.clickIntents[HOUR_ZONE_IDS[0]]
        assertNotNull("graph_hour_zone_0 must have a click PendingIntent", zonePending)
        val zoneIntent = shadowOf(zonePending).savedIntent
        assertEquals(WidgetInteractionReceiver::class.java.name, zoneIntent.component?.className)
        assertNotEquals(WidgetActionReceiver::class.java.name, zoneIntent.component?.className)
        assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, zoneIntent.action)
    }
}
