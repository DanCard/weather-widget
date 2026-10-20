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
                val response = httpClient.get(url)
                val status = response.status.value
                if (status == 404 || status == 410) throw WeatherNextGoneException(status)
                if (status !in 200..299) throw java.io.IOException("WeatherNext manifest HTTP $status")
                json.decodeFromString<WeatherNextManifest>(response.bodyAsText())
            }.onSuccess {
                Log.i(TAG, "WEATHERNEXT_FETCH outcome=manifest domain=${domain.id} run=${it.run ?: it.init} frames=${it.frames.size}")
            }.onFailure {
                val outcome = if (it is WeatherNextGoneException) "gone" else "manifest_failed"
                Log.w(TAG, "WEATHERNEXT_FETCH outcome=$outcome domain=${domain.id} error=${it.message}")
            }

        fun getFrameUrl(domain: WeatherNextDomain, relativePath: String): String {
            val clean = relativePath.removePrefix("/")
            return "$BASE_URL/${domain.id}/$clean"
        }

        /**
         * The cached file for [frame] of [layerId], downloading it when this run's copy is not on disk.
         * Old runs are never reused — see [WeatherNextFrameCache].
         */
        suspend fun cachedFrame(
            cacheDir: File,
            domain: WeatherNextDomain,
            layerId: String,
            manifest: WeatherNextManifest,
            frame: WeatherNextFrame,
        ): Result<File> {
            val relativePath = frame.files[layerId]
                ?: return Result.failure(IllegalArgumentException("frame ${frame.lead} has no $layerId"))
            val file = File(cacheDir, WeatherNextFrameCache.fileName(domain, layerId, manifest, frame))
            return downloadFrame(domain, relativePath, file)
        }

        suspend fun downloadFrame(
            domain: WeatherNextDomain,
            relativePath: String,
            destinationFile: File,
        ): Result<File> =
            runCatching {
                // Safe to reuse: the name carries the model run (WeatherNextFrameCache).
                if (destinationFile.exists() && destinationFile.length() > 1000) {
                    return Result.success(destinationFile)
                }
                val url = getFrameUrl(domain, relativePath)
                val bytes = httpClient.get(url).body<ByteArray>()
                destinationFile.parentFile?.mkdirs()
                // Write then rename so a cancelled download never leaves a truncated frame behind.
                val partial = File(destinationFile.parentFile, destinationFile.name + ".part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(destinationFile)) {
                    partial.delete()
                    error("could not move ${partial.name} into place")
                }
                destinationFile
            }.onFailure { Log.w(TAG, "WEATHERNEXT_FETCH outcome=frame_failed domain=${domain.id} path=$relativePath error=${it.message}") }
    }
