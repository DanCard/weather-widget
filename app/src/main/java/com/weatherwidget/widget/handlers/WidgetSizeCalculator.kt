package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.TypedValue
import android.view.Display
import android.view.Surface
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Data class representing widget dimensions.
 *
 * `isIconWidth` flags widgets that are approximately one launcher-icon wide
 * (widthDp <= ICON_WIDTH_THRESHOLD_DP). Independent of `cols` (data columns).
 */
data class WidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
    val isIconWidth: Boolean,
    val deviceOrientation: Int = Configuration.ORIENTATION_UNDEFINED,
    val hostOrientation: Int = Configuration.ORIENTATION_UNDEFINED,
    val orientationSource: String = "unknown",
    val homePackageName: String? = null,
    val homeScreenOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
)

/**
 * Calculator for widget size and bitmap dimensions.
 */
object WidgetSizeCalculator {
    // Per-column width assumption (dp). Deliberately tighter than a launcher grid cell so the daily
    // view packs more days at every size — e.g. Pixel 7 Pro (~373dp) shows 7 days and the Fold
    // full-width (~574dp) shows 10. Lowering this densifies all widgets uniformly.
    private const val CELL_WIDTH_DP = 60
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130

    /**
     * Bias added to the reported widget width before dividing by [CELL_WIDTH_DP].
     *
     * Combined with round-to-nearest (not floor), this rounds UP a widget that is within ~(this
     * many) dp of the next column, fitting one more forecast day. With the current [CELL_WIDTH_DP]
     * the 5→6 boundary lands at width >= 300dp, 6→7 at >= 360dp, and 9→10 at >= 540dp.
     *
     * Density is governed mainly by [CELL_WIDTH_DP]; this bias just nudges borderline widgets up.
     * Representative results: Pixel 7 Pro (~373dp) → 7 days, Samsung Fold 4 full-width (~574dp) →
     * 10 days. Days shown == columns (see NavigationUtils.getDayOffsets).
     *
     * Data backing the rightmost columns: at >8 columns the narrow skip-yesterday rule is off
     * (NavigationUtils.NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD), so a 10-column window is
     * yesterday..+8. Every fetch requests ForecastHorizon.MAX_DAYS, so Open-Meteo covers
     * through today+15 while NWS reaches ~today+6; columns past a source's real coverage
     * render the climate-normal filler, which is the intended terminal state (no on-demand
     * extension exists — a gap a max-depth fetch didn't fill is unfillable).
     */
    private const val COLUMN_FIT_BIAS_DP = 30

    /**
     * Row-count analogue of [COLUMN_FIT_BIAS_DP]: biases height toward the next row so a
     * widget that is nearly tall enough is treated as the taller layout.
     */
    private const val ROW_FIT_BIAS_DP = 25
    private const val MAX_BITMAP_PIXELS = 225_000 // Limit bitmap to ~900KB (ARGB_8888 is 4 bytes/px)
    private const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"

    /**
     * Calculate widget dimensions based on AppWidgetOptions.
     */
    fun getWidgetSize(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ): WidgetDimensions {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

        // Android reports both min and max widget dimensions:
        //   Portrait host:  actual size ≈ minWidth × maxHeight
        //   Landscape host: actual size ≈ maxWidth × minHeight
        //
        // Do not blindly use the process configuration. A foreground camera app can rotate the
        // entire process to landscape while a fixed-orientation launcher (Pixel Launcher uses
        // SCREEN_ORIENTATION_NOSENSOR) remains portrait. A background widget update rendered with
        // the process orientation then pushes the short landscape bitmap into the portrait host.
        val deviceOrientation = context.resources.configuration.orientation
        val naturalOrientation = naturalOrientation(context, deviceOrientation)
        val homeActivity = resolveHomeActivity(context)
        val orientationDecision =
            resolveHostOrientation(
                deviceOrientation = deviceOrientation,
                naturalOrientation = naturalOrientation,
                homeScreenOrientation = homeActivity.screenOrientation,
                homePackageName = homeActivity.packageName,
            )
        val isPortrait = orientationDecision.orientation != Configuration.ORIENTATION_LANDSCAPE
        val width = if (isPortrait) minWidth else maxWidth
        val height = if (isPortrait) maxHeight else minHeight

        if (deviceOrientation != orientationDecision.orientation) {
            android.util.Log.v(
                "WidgetSizeCalculator",
                "widget=$appWidgetId deviceOrientation=${orientationName(deviceOrientation)} " +
                    "hostOrientation=${orientationName(orientationDecision.orientation)} " +
                    "source=${orientationDecision.source} homePackage=${homeActivity.packageName} " +
                    "homeScreenOrientation=${homeActivity.screenOrientation} " +
                    "selectedSizeDp=${width}x$height",
            )
        }

        val cols = columnsForWidthDp(width)
        val rows = rowsForHeightDp(height)
        val isIconWidth = width <= ICON_WIDTH_THRESHOLD_DP

        return WidgetDimensions(
            cols = cols,
            rows = rows,
            widthDp = width,
            heightDp = height,
            isIconWidth = isIconWidth,
            deviceOrientation = deviceOrientation,
            hostOrientation = orientationDecision.orientation,
            orientationSource = orientationDecision.source,
            homePackageName = homeActivity.packageName,
            homeScreenOrientation = homeActivity.screenOrientation,
        )
    }

    internal data class HostOrientationDecision(
        val orientation: Int,
        val source: String,
    )

    /** Resolve the widget host orientation independently of whichever app is in the foreground. */
    internal fun resolveHostOrientation(
        deviceOrientation: Int,
        naturalOrientation: Int,
        homeScreenOrientation: Int,
        homePackageName: String? = null,
    ): HostOrientationDecision {
        val current =
            normalizeOrientation(deviceOrientation)
                ?: normalizeOrientation(naturalOrientation)
                ?: Configuration.ORIENTATION_PORTRAIT
        val natural = normalizeOrientation(naturalOrientation) ?: current
        if (homePackageName == PIXEL_LAUNCHER_PACKAGE) {
            return HostOrientationDecision(natural, "pixel_launcher_natural")
        }
        return when (homeScreenOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            -> HostOrientationDecision(Configuration.ORIENTATION_PORTRAIT, "home_fixed_portrait")

            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
            -> HostOrientationDecision(Configuration.ORIENTATION_LANDSCAPE, "home_fixed_landscape")

            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR ->
                HostOrientationDecision(natural, "home_nosensor_natural")

            else -> HostOrientationDecision(current, "device_configuration")
        }
    }

    /** Recover the display's natural orientation from its current orientation and rotation. */
    internal fun naturalOrientationForRotation(
        currentOrientation: Int,
        rotation: Int,
    ): Int {
        val current = normalizeOrientation(currentOrientation) ?: Configuration.ORIENTATION_PORTRAIT
        return when (rotation) {
            Surface.ROTATION_90,
            Surface.ROTATION_270,
            -> if (current == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }

            else -> current
        }
    }

    internal fun orientationName(orientation: Int): String =
        when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "undefined"
        }

    private data class HomeActivity(
        val packageName: String?,
        val screenOrientation: Int,
    )

    @Suppress("DEPRECATION")
    private fun resolveHomeActivity(context: Context): HomeActivity {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val activityInfo =
            context.packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
        return HomeActivity(
            packageName = activityInfo?.packageName,
            screenOrientation =
                activityInfo?.screenOrientation
                    ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        )
    }

    private fun naturalOrientation(
        context: Context,
        currentOrientation: Int,
    ): Int {
        val rotation =
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.rotation
                ?: Surface.ROTATION_0
        return naturalOrientationForRotation(currentOrientation, rotation)
    }

    private fun normalizeOrientation(orientation: Int): Int? =
        orientation.takeIf {
            it == Configuration.ORIENTATION_PORTRAIT ||
                it == Configuration.ORIENTATION_LANDSCAPE
        }

    /**
     * Maps a widget width (dp) to the number of day columns the daily view shows.
     *
     * Pure (no Android dependencies) so the column math is directly unit-testable.
     * Round-to-nearest plus [COLUMN_FIT_BIAS_DP] deliberately fits the extra forecast day
     * for widgets near a column boundary (e.g. 6→7 columns at width >= 360dp).
     */
    fun columnsForWidthDp(widthDp: Int): Int =
        ((widthDp + COLUMN_FIT_BIAS_DP).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)

    /**
     * Maps a widget height (dp) to the number of rows, biased toward the taller layout.
     * Pure counterpart to [columnsForWidthDp]; see [ROW_FIT_BIAS_DP].
     */
    fun rowsForHeightDp(heightDp: Int): Int =
        ((heightDp + ROW_FIT_BIAS_DP).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

    /**
     * Get optimal bitmap size for the widget, applying downscaling if needed.
     */
    fun getOptimalBitmapSize(
        context: Context,
        widthDp: Int,
        heightDp: Int,
    ): Pair<Int, Int> {
        val rawWidth = dpToPx(context, widthDp)
        val rawHeight = dpToPx(context, heightDp)
        val rawPixels = rawWidth * rawHeight
        val rawMemoryKB = rawPixels * 4 / 1024

        return if (rawPixels > MAX_BITMAP_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toFloat() / rawPixels)
            val newWidth = (rawWidth * scale).roundToInt()
            val newHeight = (rawHeight * scale).roundToInt()
            val newPixels = newWidth * newHeight
            val newMemoryKB = newPixels * 4 / 1024
            android.util.Log.d(
                "WidgetSizeCalculator",
                "${widthDp}dp×${heightDp}dp → Downscaling from ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB) to ${newWidth}x${newHeight}px (${newMemoryKB}KB), scale=$scale, rawPixels=$rawPixels",
            )
            newWidth to newHeight
        } else {
            android.util.Log.d(
                "WidgetSizeCalculator",
                "${widthDp}dp×${heightDp}dp → No downscaling needed: ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB), rawPixels=$rawPixels",
            )
            rawWidth to rawHeight
        }
    }

    /**
     * Convert DP to pixels.
     */
    fun dpToPx(
        context: Context,
        dp: Int,
    ): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }

    /**
     * Convert DP float to pixels float.
     */
    fun dpToPx(
        context: Context,
        dp: Float,
    ): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    data class BitmapDimensions(
        val widthPx: Int,
        val heightPx: Int,
        val bitmapScale: Float,
    )

    fun computeBitmapDimensions(
        context: Context,
        widgetWidthDp: Int,
        widgetHeightDp: Int,
        widthPaddingDp: Int = 24,
        heightPaddingDp: Int = 16,
    ): BitmapDimensions {
        val widthDp = widgetWidthDp - widthPaddingDp
        val heightDp = widgetHeightDp - heightPaddingDp
        val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)
        val rawWidthPx = dpToPx(context, widthDp).coerceAtLeast(1)
        val rawHeightPx = dpToPx(context, heightDp).coerceAtLeast(1)
        val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())
        return BitmapDimensions(widthPx, heightPx, bitmapScale)
    }
}
