package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.ViewMode

// Re-export enums from WidgetStateManager for use in handlers
// This provides a cleaner import path for handler classes
typealias HandlerAccuracyDisplayMode = AccuracyDisplayMode
typealias HandlerApiPreference = ApiPreference
typealias HandlerViewMode = ViewMode
