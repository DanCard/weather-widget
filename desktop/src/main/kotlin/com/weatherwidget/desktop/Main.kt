package com.weatherwidget.desktop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.weatherwidget.shared.util.Log


/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
fun main(args: Array<String>) {
    // Surface shared-module diagnostics on the console (default JulSink drops DEBUG). First thing so
    // even startup logging from :shared is visible.
    Log.install(DesktopLogSink)

    // On Linux, prefer the system truststore over the bundled JRE's truststore if present.
    // The bundled JRE's cacerts is often outdated or incomplete compared to the system-wide store.
    val systemTrustStore = java.io.File("/etc/ssl/certs/java/cacerts")
    if (systemTrustStore.exists() && System.getProperty("javax.net.ssl.trustStore") == null) {
        System.setProperty("javax.net.ssl.trustStore", systemTrustStore.absolutePath)
        Log.i("Main", "Configured system SSL truststore: ${systemTrustStore.absolutePath}")
    }

    val isUiMode = args.contains("--ui") || args.contains("ui") || args.contains("--show") || args.contains("show")
    if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") {
        if (isUiMode) {
            runDesktopUiApplication()
        }
        return
    }
    if (isUiMode) {
        Thread.currentThread().name = "WeatherUI"
        Log.i("Main", "Starting WeatherUI process...")
        if (args.contains("--show")) {
            System.setProperty("weatherwidget.desktop.show", "true")
        }
        runDesktopUiApplication()
    } else {
        runDaemon()
    }
}


internal fun createTrayTextMeasurer(): TextMeasurer =
    TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultLayoutDirection = LayoutDirection.Ltr,
        defaultDensity = Density(1f),
    )
