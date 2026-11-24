package com.weatherwidget.shared.contract

import com.weatherwidget.data.remote.OpenWeatherMapApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos

/**
 * OpenWeatherMap's free `/2.5/forecast` as the real API shapes it: [slots] three-hour slots on the
 * UTC 00/03/…/21 grid, starting at the first grid point after [fetchedAt], for a city at
 * [offsetSeconds]. Served to the real [OpenWeatherMapApi] through a Ktor [MockEngine] (no network).
 * plans/261124-partial-high-days-shared-row-choice-claude.md
 */
class OwmForecastFixture(
    val fetchedAt: Instant,
    val offsetSeconds: Int,
    val slots: Int = 40,
    /** °F at a local time; [diurnal] by default. */
    val temp: (LocalDateTime) -> Double = ::diurnal,
    /** Chance of rain 0..1 at a local time. */
    val pop: (LocalDateTime) -> Double = { 0.0 },
) {
    /** The slot instants, on the 3 h UTC grid. */
    val slotTimes: List<Instant> = run {
        val epochHour = fetchedAt.truncatedTo(ChronoUnit.HOURS).epochSecond / 3600
        val first = Instant.ofEpochSecond((epochHour / 3 + 1) * 3 * 3600)
        (0 until slots).map { first.plusSeconds(it * 3L * 3600) }
    }

    fun local(t: Instant): LocalDateTime = LocalDateTime.ofInstant(t, ZoneOffset.ofTotalSeconds(offsetSeconds))

    fun forecastJson(): String = buildString {
        append("""{"cod":"200","message":0,"cnt":$slots,""")
        append(""""city":{"id":1,"name":"Fixture","coord":{"lat":0,"lon":0},"country":"US","timezone":$offsetSeconds},""")
        append(""""list":[""")
        slotTimes.forEachIndexed { i, t ->
            if (i > 0) append(',')
            val at = local(t)
            val p = pop(at)
            append("""{"dt":${t.epochSecond},"main":{"temp":${"%.2f".format(java.util.Locale.ROOT, temp(at))}},""")
            append(""""weather":[{"id":803,"main":"Clouds","description":"broken clouds","icon":"04d"}],""")
            append(""""clouds":{"all":60},"pop":${"%.2f".format(java.util.Locale.ROOT, p)}""")
            if (p >= 0.5) append(""","rain":{"3h":1.0}""")
            append("}")
        }
        append("]}")
    }

    fun currentJson(): String {
        val at = local(fetchedAt)
        return """{"weather":[{"id":803,"main":"Clouds","icon":"04d"}],"main":{"temp":${"%.2f".format(java.util.Locale.ROOT, temp(at))}},""" +
            """"dt":${fetchedAt.epochSecond},"timezone":$offsetSeconds,"cod":200}"""
    }

    /** The real API wired to a MockEngine answering `/weather` and `/forecast` from this fixture. */
    fun api(): OpenWeatherMapApi {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val body = if (request.url.encodedPath.endsWith("/forecast")) forecastJson() else currentJson()
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        return OpenWeatherMapApi(client, Json { ignoreUnknownKeys = true; isLenient = true }) { "fixture-key" }
    }

    companion object {
        /** 62 ± 12 °F: coldest at 03:00 local, warmest at 15:00. */
        fun diurnal(at: LocalDateTime): Double {
            val hour = at.hour + at.minute / 60.0
            return 62.0 - 12.0 * cos(2 * PI * (hour - 3.0) / 24.0)
        }
    }
}
