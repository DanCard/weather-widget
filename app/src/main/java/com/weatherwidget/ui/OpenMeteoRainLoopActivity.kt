package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.remote.weathernext.WeatherNextForecastClient
import com.weatherwidget.shared.rainmap.RainColorScale
import com.weatherwidget.shared.rainmap.RainGrid
import com.weatherwidget.shared.rainmap.RainGridRepository
import com.weatherwidget.shared.rainmap.RainGridSpec
import com.weatherwidget.shared.rainmap.RainLayer
import com.weatherwidget.util.FriendlyLocationName
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The precipitation pill's loop when the widget shows **Open-Meteo**: a rain map built from one
 * Open-Meteo grid request, so the value at the pin is the graph's own. Other sources keep
 * [RainForecastActivity] (WeatherNext). See `plans/261020-open-meteo-rain-map-loop.md`.
 */
@AndroidEntryPoint
class OpenMeteoRainLoopActivity : AppCompatActivity() {

    @Inject lateinit var httpClient: HttpClient

    private lateinit var map: RainMapView
    private lateinit var tvLocation: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvHere: TextView
    private lateinit var progress: ProgressBar
    private lateinit var legend: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnAmount: Button
    private lateinit var btnChance: Button
    private lateinit var btnSpeed: TextView
    private lateinit var btnZoom: Button
    private var location: Pair<Double, Double>? = null
    private var detail = false

    private var grid: RainGrid? = null
    private var nowPos = 0.0
    private var pos = 0.0
    private var playing = true
    private var layer = RainLayer.AMOUNT
    private val speeds = listOf(0.5f, 1.0f, 1.5f, 2.5f)
    private var speedIndex = 1
    private val useInches = Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "GB")

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val g = grid
            if (playing && g != null && g.times.size > 1) {
                pos += TICK_MS * speeds[speedIndex] / FRAME_MS
                // Loop from now to the end, then back to now: hours already past are not news.
                if (pos > g.times.size - 1) pos = nowPos
                show()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_meteo_rain_loop)
        map = findViewById(R.id.rain_map)
        tvLocation = findViewById(R.id.tv_location)
        tvSource = findViewById(R.id.tv_source)
        tvTime = findViewById(R.id.tv_time)
        tvHere = findViewById(R.id.tv_here)
        progress = findViewById(R.id.progress_loading)
        legend = findViewById(R.id.legend)
        seek = findViewById(R.id.sb_timeline)
        btnPlay = findViewById(R.id.btn_play_pause)
        btnAmount = findViewById(R.id.btn_layer_amount)
        btnChance = findViewById(R.id.btn_layer_chance)
        btnSpeed = findViewById(R.id.btn_speed_toggle)
        btnZoom = findViewById(R.id.btn_zoom)
        btnZoom.backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
        btnZoom.setOnClickListener {
            val loc = location ?: return@setOnClickListener
            detail = !detail
            btnZoom.setText(if (detail) R.string.rain_map_zoom_out else R.string.rain_map_zoom_in)
            load(loc)
        }

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnPlay.setOnClickListener {
            playing = !playing
            btnPlay.setText(if (playing) R.string.rain_loop_pause else R.string.rain_loop_play)
        }
        findViewById<View>(R.id.btn_now).setOnClickListener {
            pos = nowPos
            show()
        }
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            btnSpeed.setText(
                listOf(R.string.rain_loop_speed_0_5, R.string.rain_loop_speed_1_0, R.string.rain_loop_speed_1_5, R.string.rain_loop_speed_2_5)[speedIndex],
            )
        }
        btnAmount.setOnClickListener { selectLayer(RainLayer.AMOUNT) }
        btnChance.setOnClickListener { selectLayer(RainLayer.CHANCE) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    pos = progress / SEEK_STEPS_PER_HOUR.toDouble()
                    show()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        selectLayer(RainLayer.AMOUNT)

        val loc = resolveLocation()
        if (loc == null) {
            progress.visibility = View.GONE
            tvSource.text = getString(R.string.sim_loop_no_location)
            return
        }
        val label = ActiveLocationResolver.chosenLabel(this) ?: FriendlyLocationName.cached(this, loc.first, loc.second)
        val place = label?.split(",")?.firstOrNull()?.trim() ?: getString(R.string.rain_loop_current_location)
        tvLocation.text = "📍 $place"
        map.pinLabel = place
        location = loc
        load(loc)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun resolveLocation(): Pair<Double, Double>? =
        if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON)) {
            intent.getDoubleExtra(EXTRA_LAT, 0.0) to intent.getDoubleExtra(EXTRA_LON, 0.0)
        } else {
            ActiveLocationResolver.current(this) ?: run {
                val ids = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java))
                val state = WidgetStateManager(this)
                ids.toList().firstNotNullOfOrNull { state.getStoredWidgetLocation(it) }
            }
        }

    private fun load(loc: Pair<Double, Double>) {
        progress.visibility = View.VISIBLE
        tvSource.setOnClickListener(null)
        val repo = RainGridRepository(httpClient, File(cacheDir, "rain_grid"))
        lifecycleScope.launch {
            val firstLoad = grid == null
            repo.load(loc.first, loc.second, if (detail) RainGridSpec.DETAIL else RainGridSpec.WIDE)
                .onSuccess { g ->
                    progress.visibility = View.GONE
                    grid = g
                    map.grid = g
                    nowPos = g.hourPosition(System.currentTimeMillis())
                    // A zoom switch keeps the moment being watched; only the first load starts at now.
                    if (firstLoad) pos = nowPos else pos = pos.coerceIn(0.0, (g.times.size - 1).toDouble())
                    seek.max = (g.times.size - 1) * SEEK_STEPS_PER_HOUR
                    tvSource.text = getString(R.string.rain_map_source_fetched, shortTime(g.fetchedAtMs))
                    show()
                }
                .onFailure {
                    progress.visibility = View.GONE
                    tvSource.text = getString(R.string.sim_loop_load_failed)
                    if (!firstLoad) {
                        // A failed zoom switch keeps the map on screen; put the button back to match it.
                        detail = !detail
                        btnZoom.setText(if (detail) R.string.rain_map_zoom_out else R.string.rain_map_zoom_in)
                    }
                    tvSource.setOnClickListener { load(loc) }
                }
        }
    }

    private fun selectLayer(l: RainLayer) {
        layer = l
        map.layer = l
        val active = ColorStateList.valueOf(0xFF2563EB.toInt())
        val idle = ColorStateList.valueOf(0xFF334155.toInt())
        btnAmount.backgroundTintList = if (l == RainLayer.AMOUNT) active else idle
        btnChance.backgroundTintList = if (l == RainLayer.CHANCE) active else idle
        rebuildLegend()
        show()
    }

    private fun show() {
        val g = grid ?: return
        map.hourPos = pos
        seek.progress = (pos * SEEK_STEPS_PER_HOUR).roundToInt()
        val hourMs = g.times[0] + (pos * RainGrid.HOUR_MS).toLong()
        tvTime.text = getString(
            R.string.rain_loop_valid,
            WeatherNextForecastClient.formatUtcToLocal(Instant.ofEpochMilli(hourMs).toString(), use24HourClock = DateFormat.is24HourFormat(this)),
        )
        val mm = g.valueAt(RainLayer.AMOUNT, g.centerRow, g.centerCol, pos)
        val pct = g.valueAt(RainLayer.CHANCE, g.centerRow, g.centerCol, pos)
        tvHere.text = getString(R.string.rain_map_here, rate(mm), "${pct.roundToInt()}%")
    }

    private fun rate(mm: Float): String =
        if (useInches) {
            val v = String.format(Locale.US, "%.2f", mm / 25.4f).trimEnd('0').trimEnd('.').removePrefix("0")
            "${v.ifEmpty { "0" }} ${getString(R.string.rain_map_unit_in)}"
        } else {
            "${String.format(Locale.US, "%.1f", mm).trimEnd('0').trimEnd('.')} ${getString(R.string.rain_map_unit_mm)}"
        }

    private fun shortTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault()))

    private fun rebuildLegend() {
        legend.removeAllViews()
        val density = resources.displayMetrics.density
        for (stop in RainColorScale.stops(layer)) {
            val text = if (layer == RainLayer.AMOUNT) RainColorScale.amountLabel(stop.value, useInches) else "${stop.value.roundToInt()}%"
            legend.addView(
                TextView(this).apply {
                    this.text = " $text "
                    textSize = 11f
                    setTextColor(0xFF0F172A.toInt())
                    setBackgroundColor(stop.argb or 0xFF000000.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = (4 * density).toInt() }
                },
            )
        }
        if (layer == RainLayer.AMOUNT) {
            legend.addView(
                TextView(this).apply {
                    this.text = getString(if (useInches) R.string.rain_map_unit_in else R.string.rain_map_unit_mm)
                    textSize = 11f
                    setTextColor(0xFF94A3B8.toInt())
                },
            )
        }
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
        private const val TICK_MS = 35L

        /** One hour of map per this long at 1×. */
        private const val FRAME_MS = 400f
        private const val SEEK_STEPS_PER_HOUR = 10
    }
}
