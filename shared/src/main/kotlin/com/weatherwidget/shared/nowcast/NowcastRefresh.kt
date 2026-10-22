package com.weatherwidget.shared.nowcast

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * When the nowcast is refetched. It rides paths that already run — Android's current-temp work,
 * desktop's observation loop — and adds no wakeup or job of its own (phase 1: no battery-policy
 * change). Every gate must pass; dry hours cost nothing beyond the normal forecast fetch, which
 * refreshes the series anyway.
 */
object NowcastRefreshPolicy {
    const val MIN_REFETCH_MS = 15L * 60 * 1000
    const val PLAUSIBLE_POP_PERCENT = 30
    const val PLAUSIBLE_WINDOW_MS = 3L * 60 * 60 * 1000

    enum class Skip(val logName: String) {
        NOT_DISPLAYED("not_displayed"),
        INTERPOLATED("interpolated"),
        FRESH("fresh"),
        DRY("dry"),
    }

    /** Null = fetch. [nativeCoverage] null = never probed (the fetch probes first). */
    fun skipReason(
        openMeteoDisplayed: Boolean,
        nativeCoverage: Boolean?,
        cached: NowcastSeries?,
        maxPopNext3h: Int?,
        nowMs: Long,
    ): Skip? = when {
        !openMeteoDisplayed -> Skip.NOT_DISPLAYED
        nativeCoverage == false -> Skip.INTERPOLATED
        cached != null && nowMs - cached.fetchedAtMs in 0 until MIN_REFETCH_MS -> Skip.FRESH
        (maxPopNext3h ?: 0) < PLAUSIBLE_POP_PERCENT && cached?.hasRainAfter(nowMs) != true -> Skip.DRY
        else -> null
    }

    /** The highest hourly chance in the next 3 h across [hourly] (every source: if any sees rain, look). */
    fun maxPopNext3h(hourly: List<HourlyForecast>, nowMs: Long): Int? =
        hourly.asSequence()
            .filter { it.dateTime + HOUR_MS > nowMs && it.dateTime < nowMs + PLAUSIBLE_WINDOW_MS }
            .mapNotNull { it.precipProbability }
            .maxOrNull()

    private const val HOUR_MS = 60L * 60 * 1000
}

/**
 * The two Open-Meteo requests: a coverage probe (once per site per 30 days) and the ~1 KB nowcast.
 * The nowcast asks no `models=` — Open-Meteo's automatic choice, the same model as the forecast
 * graph and the rain map — at the same coordinate the forecast fetch uses.
 */
class NowcastFetcher(
    private val httpClient: HttpClient,
    private val store: NowcastStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        val logLine: String

        data class Fetched(val slots: Int, val probed: Boolean) : Outcome {
            override val logLine get() = "outcome=fetched slots=$slots probed=$probed"
        }
        data class Interpolated(val probed: Boolean) : Outcome {
            override val logLine get() = "outcome=interpolated probed=$probed"
        }
        data class Failed(val error: String) : Outcome {
            override val logLine get() = "outcome=failed error=$error"
        }
    }

    suspend fun refresh(lat: Double, lon: Double): Outcome = try {
        var probed = false
        val native = store.loadCoverage(lat, lon, nowMs()) ?: probe(lat, lon).also { probed = true }
        when (native) {
            null -> Outcome.Failed("probe_unreadable")
            false -> Outcome.Interpolated(probed)
            true -> {
                val fetchedAt = nowMs()
                val series = NowcastParser.parseSeries(fetchNowcast(lat, lon), fetchedAt, fetchedAt)
                if (series == null) {
                    Outcome.Failed("no_slots")
                } else {
                    store.saveSeries(lat, lon, series)
                    Outcome.Fetched(series.slots.size, probed)
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed("${e::class.simpleName}: ${e.message?.substringBefore(" [url=")?.take(120)}")
    }

    /** Open-Meteo answers a site outside every listed model with HTTP 400 and the error body. */
    private suspend fun probe(lat: Double, lon: Double): Boolean? {
        val body = httpClient.get(URL) {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("minutely_15", "precipitation")
            parameter("forecast_minutely_15", PROBE_SLOTS)
            parameter("models", NowcastCoverage.NATIVE_MODELS)
            parameter("timeformat", "unixtime")
        }.bodyAsText()
        val native = NowcastCoverage.parseProbe(body) ?: return null
        store.saveCoverage(lat, lon, native, nowMs())
        return native
    }

    private suspend fun fetchNowcast(lat: Double, lon: Double): String {
        val response = httpClient.get(URL) {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("minutely_15", "precipitation,precipitation_probability")
            parameter("forecast_minutely_15", NOWCAST_SLOTS)
            parameter("past_minutely_15", 1)
            parameter("timeformat", "unixtime")
            parameter("timezone", "GMT")
        }
        val status = response.status.value
        if (status !in 200..299) throw IllegalStateException("HTTP $status")
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://api.open-meteo.com/v1/forecast"
        const val NOWCAST_SLOTS = 12
        const val PROBE_SLOTS = 4
    }
}

/** What the views draw: only for Open-Meteo displayed at a site with native 15-minute data. */
object NowcastDisplay {
    fun series(
        store: NowcastStore,
        lat: Double,
        lon: Double,
        displaySourceId: String,
        nowMs: Long,
    ): NowcastSeries? {
        if (displaySourceId != WeatherSource.OPEN_METEO.id) return null
        if (store.loadCoverage(lat, lon, nowMs) != true) return null
        return store.loadSeries(lat, lon, nowMs)
    }

    fun summary(series: NowcastSeries?, nowMs: Long): NowcastSummary.Result? =
        series?.let { NowcastSummary.summarize(it, nowMs) }

    /** The slots the hourly precip graph draws as its 15-minute band: from now's slot, 2 h ahead. */
    fun bandSlots(series: NowcastSeries?, nowMs: Long): List<NowcastSlot> =
        series?.slots.orEmpty().filter { it.endMs > nowMs && it.startMs < nowMs + NowcastSummary.HORIZON_MS }
}

/**
 * The hourly precip graph's 15-minute band: one bar per wet slot along the plot's bottom, its
 * height the slot's intensity. Shared geometry; each platform only paints.
 */
object NowcastBand {
    /** mm/h that fills the band: moderate-to-heavy rain (4 mm/h) and above draw full height. */
    const val FULL_SCALE_MM_PER_HOUR = 4f

    /** Lightest wet slot still reads as a bar. */
    const val MIN_HEIGHT_FRACTION = 0.15f

    /** Band height as a fraction of the plot's: low enough to sit under the chance curve's labels. */
    const val BAND_HEIGHT_FRACTION = 0.22f

    data class Bar(val leftX: Float, val rightX: Float, val heightFraction: Float)

    /**
     * [firstHourMs] is the graph's first point (x = 0), one [hourWidthPx] per hour after it — the
     * same mapping as the curve's points and the now line.
     */
    fun bars(slots: List<NowcastSlot>, firstHourMs: Long, hourWidthPx: Float): List<Bar> =
        slots.filter { it.mm >= NowcastSummary.RAIN_SLOT_MM }.map { slot ->
            val mmPerHour = slot.mm * (HOUR_MS / SLOT_MS)
            Bar(
                leftX = (slot.startMs - firstHourMs).toFloat() / HOUR_MS * hourWidthPx,
                rightX = (slot.endMs - firstHourMs).toFloat() / HOUR_MS * hourWidthPx,
                heightFraction = (mmPerHour / FULL_SCALE_MM_PER_HOUR).coerceIn(MIN_HEIGHT_FRACTION, 1f),
            )
        }

    private const val HOUR_MS = 60L * 60 * 1000
}
