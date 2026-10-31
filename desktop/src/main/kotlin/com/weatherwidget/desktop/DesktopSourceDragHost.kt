package com.weatherwidget.desktop

import com.weatherwidget.shared.sourceview.SourceDragRun

/** Desktop's [SourceDragRun] host: Settings saves the order, the app-log queue stores the line. */
internal class DesktopSourceDragHost(private val onSave: (List<String>) -> Unit) : SourceDragRun.Host {
    override fun save(order: List<String>) = onSave(order)

    override fun persistLog(tag: String, message: String) = DesktopAppLogQueue.log(tag, message)
}
