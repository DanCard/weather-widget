package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

open class ApiAccessException(
    val source: WeatherSource,
    val statusCode: Int? = null,
    val detail: String,
    override val message: String,
) : IllegalStateException(message)

/**
 * Validates that an [HttpResponse] has a 2xx status code, throwing [ApiAccessException]
 * with captured response details if not.
 */
suspend fun HttpResponse.require2xx(source: WeatherSource, label: String) {
    if (status.value !in 200..299) {
        val errorBody = runCatching { bodyAsText() }.getOrDefault("No error body")
        throw ApiAccessException(
            source = source,
            statusCode = status.value,
            detail = errorBody,
            message = "$label: status ${status.value}. Detail: $errorBody",
        )
    }
}
