package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.awt.Desktop
import java.net.URI

private const val TAG = "UrlOpener"

/**
 * Opens [url] in the user's default web browser. Runs on a short-lived daemon thread because
 * [Desktop.browse] can block while the browser launches, and we must never stall the Compose UI
 * thread. Safe to call only from the UI process — the daemon runs headless (see [DaemonProcess]),
 * where [Desktop.isDesktopSupported] is false and we fall back to `xdg-open`.
 */
fun openInBrowser(url: String) {
    Thread {
        try {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (e: Exception) {
            // AWT browse can throw on some Linux desktops; xdg-open is the portable fallback.
            try {
                ProcessBuilder("xdg-open", url).start()
            } catch (fallback: Exception) {
                Log.w(TAG, "Failed to open $url: ${fallback.message}")
            }
        }
    }.apply { isDaemon = true }.start()
}
