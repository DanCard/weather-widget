package com.weatherwidget.testutil

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Replays the Google Weather responses recorded 2026-10-06 17:xx UTC (`shared/src/test/resources/
 * google-weather/`, on this module's test classpath via app/build.gradle.kts), shifted so the
 * recording's current hour is [now]'s hour: instants move by whole hours, `displayDate`s by the
 * local-day difference. The repository reads the real clock, so unshifted fixtures would age into
 * "the past" and the elapsed/future split would depend on when the test runs.
 * Mirrors the desktop copy; the routing is the same as `GoogleWeatherApiTest` in :shared.
 */
object GoogleWeatherFixtures {
    private val RECORDED_ON: LocalDate = LocalDate.of(2026, 10, 6)
    /** Start of the first `forecastHours` interval in the recording — the recorded "current hour". */
    val RECORDED_HOUR: Instant = Instant.parse("2026-10-06T17:00:00Z")
    private val ISO_INSTANT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")

    fun engine(requests: MutableList<HttpRequestData>, now: Instant = Instant.now()): MockEngine {
        val shift = shiftFor(now)
        return MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val name = when {
                path.endsWith("currentConditions:lookup") -> "current"
                path.endsWith("forecast/days:lookup") -> "days"
                path.endsWith("history/hours:lookup") -> "history"
                path.endsWith("forecast/hours:lookup") ->
                    "hours" + ((requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") } - 1) % 3 + 1)
                else -> error("unexpected Google Weather path $path")
            }
            respond(load(name, shift), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    data class Shift(val hours: Long, val days: Long)

    fun shiftFor(now: Instant): Shift = Shift(
        hours = ChronoUnit.HOURS.between(RECORDED_HOUR, now.truncatedTo(ChronoUnit.HOURS)),
        days = ChronoUnit.DAYS.between(RECORDED_ON, now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
    )

    fun load(name: String, shift: Shift): String {
        val raw = requireNotNull(javaClass.classLoader.getResource("google-weather/$name.json")) { name }.readText()
        return Json.encodeToString(JsonElement.serializer(), shift(Json.parseToJsonElement(raw), shift))
    }

    private fun shift(element: JsonElement, by: Shift): JsonElement = when (element) {
        is JsonObject -> {
            val isDate = element.keys.containsAll(listOf("year", "month", "day"))
            val shifted = element.mapValues { (_, v) -> shift(v, by) }.toMutableMap()
            if (isDate) {
                val d = LocalDate.of(
                    element.getValue("year").jsonPrimitive.int,
                    element.getValue("month").jsonPrimitive.int,
                    element.getValue("day").jsonPrimitive.int,
                ).plusDays(by.days)
                shifted["year"] = JsonPrimitive(d.year)
                shifted["month"] = JsonPrimitive(d.monthValue)
                shifted["day"] = JsonPrimitive(d.dayOfMonth)
            }
            JsonObject(shifted)
        }
        is JsonArray -> JsonArray(element.map { shift(it, by) })
        is JsonPrimitive ->
            if (element.isString && ISO_INSTANT.matches(element.content)) {
                JsonPrimitive(Instant.parse(element.content).plus(by.hours, ChronoUnit.HOURS).toString())
            } else {
                element
            }
    }
}
