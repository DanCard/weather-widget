package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WeatherNextClient"

@Singleton
class WeatherNextForecastClient
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        companion object {
            const val BASE_URL = "https://ajoros.github.io/ai-weather-guy"

            private val UTC_ISO_PARSER = DateTimeFormatter.ISO_DATE_TIME
            private val LOCAL_FORMATTER = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a z")

            fun formatUtcToLocal(isoUtcStr: String?, zoneId: ZoneId = ZoneId.systemDefault()): String {
                if (isoUtcStr.isNullOrBlank()) return "--"
                return try {
                    val instant = Instant.parse(isoUtcStr)
                    val local = instant.atZone(zoneId)
                    LOCAL_FORMATTER.format(local)
                } catch (_: Exception) {
                    isoUtcStr
                }
            }
        }

        suspend fun fetchManifest(domain: WeatherNextDomain): Result<WeatherNextManifest> =
            runCatching {
                val url = "$BASE_URL/${domain.id}/manifest.json"
                val responseText = httpClient.get(url).bodyAsText()
                json.decodeFromString<WeatherNextManifest>(responseText)
            }

        fun getFrameUrl(domain: WeatherNextDomain, relativePath: String): String {
            val clean = relativePath.removePrefix("/")
            return "$BASE_URL/${domain.id}/$clean"
        }

        suspend fun downloadFrame(
            domain: WeatherNextDomain,
            relativePath: String,
            destinationFile: File,
        ): Result<File> =
            runCatching {
                if (destinationFile.exists() && destinationFile.length() > 1000) {
                    return Result.success(destinationFile)
                }
                val url = getFrameUrl(domain, relativePath)
                val bytes = httpClient.get(url).body<ByteArray>()
                destinationFile.parentFile?.mkdirs()
                destinationFile.writeBytes(bytes)
                destinationFile
            }
    }
