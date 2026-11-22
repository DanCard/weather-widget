package com.weatherwidget.ui

import android.Manifest
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.SizeF
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import java.util.Locale

private const val TAG = "BugReportDeviceDiagnostics"

/**
 * Device facts a bug report needs to reproduce a layout or scheduling report on an emulator:
 * the display (pixels, density, dp, font scale), the home-screen app that hosts the widget, and
 * the size each widget was actually given. The timezone line is shared: [com.weatherwidget.shared.util.BugReportFormat].
 *
 * Widget layout decisions (header buttons, columns, icon sizes) depend on dp, so density and the
 * launcher's reported widget size are what an emulator must match; the device model alone is not.
 */
object BugReportDeviceDiagnostics {

    /** `--- DISPLAY ---` body: physical pixels, density, dp, font scale, orientation, theme. */
    fun displayLines(context: Context): List<String> {
        val res = context.resources
        val metrics = res.displayMetrics
        val config = res.configuration
        val (widthPx, heightPx) = screenPixels(context)
        return listOf(
            "Screen: ${widthPx}x$heightPx px",
            "Density: ${metrics.densityDpi} dpi (scale ${fmt(metrics.density)}) " +
                "stableDefault=${android.util.DisplayMetrics.DENSITY_DEVICE_STABLE} dpi",
            "Screen dp: ${config.screenWidthDp}x${config.screenHeightDp} (smallestWidth ${config.smallestScreenWidthDp})",
            "Font scale: ${fmt(config.fontScale)}",
            "Orientation: ${orientationName(config.orientation)}",
            "Night mode: ${if ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "on" else "off"}",
        )
    }

    /** Home-screen app package; it decides widget cell sizes (Samsung One UI vs Pixel Launcher differ). */
    fun launcherLine(context: Context): String {
        val pkg = runCatching { WidgetSizeCalculator.resolveHomePackage(context) }
            .getOrElse { Log.w(TAG, "Could not resolve home package", it); null }
        return "Launcher: ${pkg ?: "unknown"}"
    }

    /** Power/permission state that gates background refresh and location following. */
    fun powerAndPermissionLines(context: Context): List<String> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        val bucket = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            standbyBucketName(usm.appStandbyBucket)
        }.getOrElse { Log.w(TAG, "Could not read standby bucket", it); "unknown" }
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return listOf(
            "Battery optimization exempt: $batteryExempt",
            "Standby bucket: $bucket",
            "Power saver: ${pm.isPowerSaveMode}",
            "Location permission: fine=${granted(Manifest.permission.ACCESS_FINE_LOCATION)} " +
                "background=${granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}",
        )
    }

    /**
     * One widget's size as the renderer resolved it and as the launcher reported it, as two lines
     * (kept short for mail clients): `size=384x220dp cols=8 rows=3 host=portrait(source)` and
     * `options=min:384x150,max:420x220 sizes=[384x220]`.
     */
    fun widgetSizeLine(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): String =
        runCatching {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    ?.joinToString(",", prefix = " sizes=[", postfix = "]") { "${it.width.toInt()}x${it.height.toInt()}" }
                    .orEmpty()
            } else {
                ""
            }
            val dims = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
            "size=${dims.widthDp}x${dims.heightDp}dp cols=${dims.cols} rows=${dims.rows} " +
                "host=${orientationName(dims.hostOrientation)}(${dims.orientationSource})\n" +
                "options=min:${minW}x$minH,max:${maxW}x$maxH$sizes"
        }.getOrElse {
            Log.w(TAG, "Could not read size for widget $appWidgetId", it)
            "size=unavailable (${it.javaClass.simpleName})"
        }

    private fun screenPixels(context: Context): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val m = context.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private fun standbyBucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "OTHER($bucket)"
    }

    private fun orientationName(orientation: Int): String = when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        else -> "undefined"
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)
}
