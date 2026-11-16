package com.weatherwidget.shared.observations

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.radar.RadarFeed
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The radar mosaic's pixel at a site when it was sampled ([RainNow]'s radar input).
 *
 * NOAA serves colours, not dBZ: GetLegendGraphic is blank, the style has no ColorMap and the
 * ImageServer legend lists only R/G/B bands (checked 2026-11-15). So [level] reads the colour
 * family, which survives NOAA retouching a shade (Claude proposal in the plan):
 *  - alpha below half: no echo (the same cut as `RadarLoop.hasEchoes`);
 *  - blue/cyan (blue the largest channel): weak echo, often rain aloft that does not reach the ground
 *    (2026-11-15 17:04–17:55 `86,149,193` while KNUQ measured dry) → sprinkling;
 *  - red 150 or more (yellow, orange, red, magenta): heavy;
 *  - otherwise green: raining (2026-11-15 21:12 `14,214,20` with KNUQ `-RA`).
 */
data class RadarNowSample(
    val latitude: Double,
    val longitude: Double,
    val sampledAtMs: Long,
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
) {
    fun level(): RainNow.Level? = when {
        alpha < ECHO_MIN_ALPHA -> null
        red >= HEAVY_MIN_RED -> RainNow.Level.HEAVY
        blue >= green && blue >= red -> RainNow.Level.SPRINKLING
        else -> RainNow.Level.RAINING
    }

    fun rgbaText(): String = "$red,$green,$blue,$alpha"

    /** Store form: `lat,lon,ms,r,g,b,a`. */
    fun encode(): String = "$latitude,$longitude,$sampledAtMs,$red,$green,$blue,$alpha"

    companion object {
        const val ECHO_MIN_ALPHA = 0x80
        const val HEAVY_MIN_RED = 150

        /** [sample] when it was taken at this weather site, else null: a move must not carry rain along. */
        fun atSite(sample: RadarNowSample?, latitude: Double, longitude: Double): RadarNowSample? =
            sample?.takeIf { LocationMatch.sameWeatherSite(it.latitude, it.longitude, latitude, longitude) }

        fun decode(encoded: String?): RadarNowSample? {
            val p = encoded?.split(',')?.takeIf { it.size == 7 } ?: return null
            return runCatching {
                RadarNowSample(p[0].toDouble(), p[1].toDouble(), p[2].toLong(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt())
            }.getOrNull()
        }
    }
}

/**
 * Samples the NWS radar mosaic at a site once per current-conditions run ([CurrentConditionsRun]),
 * the same GeoServer the Radar view animates ([RadarFeed.Nws], `{workspace}_bref_qcd`): one
 * `GetFeatureInfo` on a 3×3-pixel box ±[HALF_BOX_DEG] around the site. Outside NWS coverage
 * (RainViewer serves only tiles) there is no sample. A failure keeps the stored sample; it ages out
 * of [RainNow] after [RainNow.RADAR_MAX_AGE_MS].
 */
class RadarNowSampler(
    private val httpClient: HttpClient,
    private val store: Store,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The last sample; Android keeps it in prefs (the renderer is not the worker), desktop in memory. */
    interface Store {
        fun read(): RadarNowSample?
        fun write(sample: RadarNowSample)
    }

    /** The stored sample when it was taken at this site, else null. */
    fun current(latitude: Double, longitude: Double): RadarNowSample? =
        RadarNowSample.atSite(store.read(), latitude, longitude)

    /** Fetch and store a sample; null outside NWS coverage or on failure. Never throws except on cancellation. */
    suspend fun sample(latitude: Double, longitude: Double, reason: String): RadarNowSample? {
        val feed = RadarFeed.forLocation(latitude, longitude) as? RadarFeed.Nws ?: return null
        val started = nowMs()
        return try {
            val response = httpClient.get(feed.owsUrl) {
                parameter("service", "WMS")
                parameter("version", "1.3.0")
                parameter("request", "GetFeatureInfo")
                parameter("layers", feed.layer)
                parameter("query_layers", feed.layer)
                parameter("crs", "EPSG:4326")
                // WMS 1.3.0 EPSG:4326 axis order is lat,lon.
                parameter("bbox", bbox(latitude, longitude))
                parameter("width", "3")
                parameter("height", "3")
                parameter("i", "1")
                parameter("j", "1")
                parameter("info_format", "application/json")
                parameter("feature_count", "1")
            }
            if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
            val sample = parse(response.bodyAsText(), latitude, longitude, nowMs())
                ?: error("no pixel in response")
            store.write(sample)
            Log.v(TAG, "reason=$reason feed=${feed.logName} rgba=${sample.rgbaText()} level=${sample.level() ?: "none"} ms=${nowMs() - started}")
            sample
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FAIL_TAG, "reason=$reason feed=${feed.logName} error=${e.javaClass.simpleName}:${e.message} ms=${nowMs() - started}")
            null
        }
    }

    companion object {
        const val TAG = "RADAR_NOW"
        const val FAIL_TAG = "RADAR_NOW_FAIL"

        /** ±0.01° ≈ ±1.1 km: the 3×3 box's centre pixel is about the site's own ~1 km radar cell. */
        const val HALF_BOX_DEG = 0.01

        private val json = Json { ignoreUnknownKeys = true }

        internal fun bbox(latitude: Double, longitude: Double): String =
            "${latitude - HALF_BOX_DEG},${longitude - HALF_BOX_DEG},${latitude + HALF_BOX_DEG},${longitude + HALF_BOX_DEG}"

        /** `{"features":[{"properties":{"RED_BAND":14,"GREEN_BAND":214,"BLUE_BAND":20,"ALPHA_BAND":255}}]}`. */
        internal fun parse(body: String, latitude: Double, longitude: Double, nowMs: Long): RadarNowSample? {
            val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: return null
            // A no-echo pixel still comes back as a feature with ALPHA_BAND 0 (measured 2026-11-15); no
            // feature at all is an answer we cannot read, not "dry".
            val props = features.firstOrNull()?.jsonObject?.get("properties")?.jsonObject ?: return null
            fun band(name: String) = props[name]?.jsonPrimitive?.int ?: 0
            return RadarNowSample(latitude, longitude, nowMs, band("RED_BAND"), band("GREEN_BAND"), band("BLUE_BAND"), band("ALPHA_BAND"))
        }
    }
}
