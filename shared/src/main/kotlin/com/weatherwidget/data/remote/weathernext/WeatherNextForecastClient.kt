package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import com.weatherwidget.shared.util.DailyRainLabels
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

            /**
             * "Tue, Oct 20, 10:00 AM" / "Di., 20. Okt., 08:00" / "10月20日火 8:00" — weekday, day and
             * month in the locale's own order, then its short time. The first version used the fixed
             * English pattern `EEE MMM d, h:mm a z`, which in German printed "Di. Okt. 20, 8:00 AM
             * GMT-07:00". The zone is gone: the viewer always shows the device's own time.
             *
             * [use24HourClock] follows the device setting on Android (`DateFormat.is24HourFormat`), as
             * the daily peak-hour label does.
             */
            fun formatUtcToLocal(
                isoUtcStr: String?,
                zoneId: ZoneId = ZoneId.systemDefault(),
                locale: Locale = Locale.getDefault(),
                use24HourClock: Boolean = DailyRainLabels.localeUses24HourClock(locale),
            ): String {
                if (isoUtcStr.isNullOrBlank()) return "--"
                return try {
                    Instant.parse(isoUtcStr).atZone(zoneId).format(formatterFor(locale, use24HourClock))
                } catch (_: Exception) {
                    isoUtcStr
                }
            }

            // The viewer formats on every 35 ms frame tick; build each locale's pattern once.
            private val formatters = java.util.concurrent.ConcurrentHashMap<Pair<Locale, Boolean>, DateTimeFormatter>()

            private fun formatterFor(locale: Locale, use24HourClock: Boolean): DateTimeFormatter =
                formatters.getOrPut(locale to use24HourClock) {
                    DateTimeFormatter.ofPattern(localPattern(locale, use24HourClock), locale)
                }

            /**
             * The locale's FULL date pattern without its year, short names, plus its SHORT time.
             * Year forms seen in the JDK's CLDR data: "…, y" (en, vi, bn), "… 'de' y" (es, pt),
             * "… y 'г'." (ru, uk), "y年…" / "y년 …" (ja, zh, ko), "… G y" (th), "…، y" (ar, ur).
             */
            internal fun localPattern(locale: Locale, use24HourClock: Boolean): String {
                val full = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    FormatStyle.FULL, null, IsoChronology.INSTANCE, locale,
                )
                // CLDR separates "y 'г'." with a no-break space, which `\s` does not match.
                val ws = """[\s\u00A0\u202F]"""
                val date = full
                    .replace(Regex("""G+$ws*"""), "")
                    .replace(Regex("""y+[年년]$ws*"""), "")
                    .replace(Regex("""[,،]?$ws*('[^']*'$ws*)?y+($ws*'[^']*'\.?)?"""), "")
                    .trim()
                    .replace("EEEE", "EEE")
                    .replace("MMMM", "MMM")
                val short = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale,
                )
                val is12 = 'h' in short || 'K' in short
                val time = when {
                    use24HourClock && is12 -> "HH:mm"
                    !use24HourClock && !is12 -> "h:mm a"
                    else -> short
                }
                val joiner = when (locale.language) {
                    "ja", "zh", "ko" -> " "
                    "ar", "ur" -> "، "
                    else -> ", "
                }
                return date + joiner + time
            }
        }

        suspend fun fetchManifest(domain: WeatherNextDomain): Result<WeatherNextManifest> =
            runCatching {
                val url = "$BASE_URL/${domain.id}/manifest.json"
                val response = httpClient.get(url)
                val status = response.status.value
                if (status == 404 || status == 410) throw WeatherNextGoneException(status)
                if (status !in 200..299) throw WeatherNextHttpException(status)
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
                val response = httpClient.get(url)
                // Without this an error page (GitHub's 404 HTML) was written to disk as the frame.
                if (response.status.value !in 200..299) throw WeatherNextHttpException(response.status.value)
                val bytes = response.body<ByteArray>()
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
