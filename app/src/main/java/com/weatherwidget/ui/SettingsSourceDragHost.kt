package com.weatherwidget.ui

import com.weatherwidget.shared.sourceview.SourceDragRun
import com.weatherwidget.widget.WidgetStateEventLogger

/** Android's [SourceDragRun] host: Settings saves the order, the event logger stores the line. */
internal class SettingsSourceDragHost(
    private val eventLogger: WidgetStateEventLogger,
    private val onSave: (List<String>) -> Unit,
) : SourceDragRun.Host {
    override fun save(order: List<String>) = onSave(order)

    override fun persistLog(tag: String, message: String) = eventLogger.log(tag, message)
}
