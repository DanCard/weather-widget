package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource

open class ApiAccessException(
    val source: WeatherSource,
    val statusCode: Int? = null,
    val detail: String,
    override val message: String,
) : IllegalStateException(message)
