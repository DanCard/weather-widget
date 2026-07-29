package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager

/**
 * Public widget-intent facade. Interaction serialization and truthful outcome metadata are applied
 * here; action transitions and daily/graph rendering are delegated to their owning components.
 */
object WidgetIntentRouter {

    fun forgetWidget(appWidgetId: Int) =
        WidgetInteractionCoordinator.forgetWidget(appWidgetId)

    @VisibleForTesting
    internal suspend fun <T> withWidgetInteractionLock(
        appWidgetId: Int,
        block: suspend () -> T,
    ): T = WidgetInteractionCoordinator.withWidgetLock(appWidgetId, block)

    @VisibleForTesting
    internal fun clearInteractionMutexesForTesting() =
        WidgetInteractionCoordinator.clearForTesting()

    @VisibleForTesting
    internal suspend fun runInteractionWithDao(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        tag: String,
        metadata: String = "",
        block: suspend () -> Unit,
    ) = WidgetInteractionCoordinator.runInteraction(
        appLogDao,
        appWidgetId,
        tag,
        metadata,
        block,
    )

    @VisibleForTesting
    internal suspend fun forEachWidgetIsolated(
        appWidgetIds: IntArray,
        onFailure: suspend (Int, Exception) -> Unit = { _, _ -> },
        render: suspend (Int) -> Unit,
    ) = WidgetInteractionCoordinator.forEachWidgetIsolated(appWidgetIds, onFailure, render)

    @VisibleForTesting
    internal suspend fun awaitLatestResizeRequest(appWidgetId: Int): Boolean =
        WidgetInteractionCoordinator.awaitLatestResizeRequest(appWidgetId)

    @VisibleForTesting
    fun setIsRefreshDisabledForTesting(disableRefreshFlag: Boolean) =
        RefreshScheduler.setIsRefreshDisabledForTesting(disableRefreshFlag)

    suspend fun renderAllWidgetsFromCache(
        context: Context,
        repository: WeatherRepository? = null,
    ) = WidgetIntentActionHandler.renderAllWidgetsFromCache(context, repository)

    suspend fun handleNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "NAV",
        metadata = { fixedMetadata("dir=${if (isLeft) "LEFT" else "RIGHT"}") },
    ) {
        WidgetIntentActionHandler.navigate(context, appWidgetId, isLeft, repository)
    }

    suspend fun handleCycleZoom(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "CYCLE_ZOOM",
        metadata = {
            val zoom = WidgetStateManager(context).getZoomLevel(appWidgetId)
            val tap = zoomCenterOffset?.let { " tapOffset=$it" }.orEmpty()
            fixedMetadata("from=${zoom.name}$tap")
        },
    ) {
        WidgetIntentActionHandler.cycleZoom(
            context,
            appWidgetId,
            zoomCenterOffset,
            repository,
        )
    }

    suspend fun handleToggleApi(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "TOGGLE_API",
        metadata = {
            fixedMetadata(
                "from=${WidgetStateManager(context).getCurrentDisplaySource(appWidgetId).id}",
            )
        },
    ) {
        WidgetIntentActionHandler.toggleApi(context, appWidgetId, repository)
    }

    suspend fun handleToggleView(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "TOGGLE_VIEW",
        metadata = {
            fixedMetadata("from=${WidgetStateManager(context).getViewMode(appWidgetId).name}")
        },
    ) {
        WidgetIntentActionHandler.toggleView(context, appWidgetId, repository)
    }

    suspend fun handleTogglePrecip(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "TOGGLE_PRECIP",
        metadata = {
            fixedMetadata("from=${WidgetStateManager(context).getViewMode(appWidgetId).name}")
        },
    ) {
        WidgetIntentActionHandler.togglePrecip(context, appWidgetId, repository)
    }

    suspend fun handleSetView(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: WeatherRepository? = null,
    ) = runInteraction(
        context,
        appWidgetId,
        "SET_VIEW",
        metadata = { fixedMetadata("mode=${targetMode.name} offset=$targetOffset") },
    ) {
        WidgetIntentActionHandler.setView(
            context,
            appWidgetId,
            targetMode,
            targetOffset,
            repository,
        )
    }

    suspend fun handleResize(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        if (!WidgetInteractionCoordinator.awaitLatestResizeRequest(appWidgetId)) return
        runInteraction(context, appWidgetId, "RESIZE") {
            WidgetIntentActionHandler.resize(context, appWidgetId, repository)
        }
    }

    private suspend fun runInteraction(
        context: Context,
        appWidgetId: Int,
        tag: String,
        metadata: suspend () -> WidgetInteractionCoordinator.Metadata = { fixedMetadata() },
        block: suspend () -> Unit,
    ) = WidgetInteractionCoordinator.runInteraction(
        WeatherDatabase.getDatabase(context).appLogDao(),
        appWidgetId,
        tag,
        metadata,
        block,
    )

    private fun fixedMetadata(value: String = "") =
        WidgetInteractionCoordinator.Metadata(value)
}
