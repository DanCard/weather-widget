package com.weatherwidget.widget.handlers

import android.content.Context
import android.os.SystemClock
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetPushDispatcher
import java.time.LocalDateTime

/** Converts one action-level render request into the daily or graph pipeline request. */
internal object InteractionRenderDispatcher {
    data class Request(
        val context: Context,
        val appWidgetId: Int,
        val refreshContext: WidgetRefreshContextResolver.Resolved,
        val now: LocalDateTime,
        val repository: WeatherRepository? = null,
        val startTimeMs: Long = SystemClock.elapsedRealtime(),
        val actionTag: String,
        val extraMetadata: String = "",
        val partialPush: Boolean = false,
        val origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    )

    suspend fun render(viewMode: ViewMode, request: Request) {
        if (viewMode.isGraphMode) {
            GraphInteractionRenderer.render(graphRequest(request))
        } else {
            DailyInteractionRenderer.render(dailyRequest(request))
        }
    }

    fun graphRequest(request: Request) =
        GraphInteractionRenderer.GraphRenderRequest(
            context = request.context,
            appWidgetId = request.appWidgetId,
            refreshContext = request.refreshContext,
            now = request.now,
            repository = request.repository,
            startTimeMs = request.startTimeMs,
            actionTag = request.actionTag,
            extraMetadata = request.extraMetadata,
            partialPush = request.partialPush,
            origin = request.origin,
        )

    fun dailyRequest(request: Request) =
        DailyInteractionRenderer.DailyRenderRequest(
            context = request.context,
            appWidgetId = request.appWidgetId,
            refreshContext = request.refreshContext,
            now = request.now,
            repository = request.repository,
            startTimeMs = request.startTimeMs,
            actionTag = request.actionTag,
            extraMetadata = request.extraMetadata,
            partialPush = request.partialPush,
            origin = request.origin,
        )
}
