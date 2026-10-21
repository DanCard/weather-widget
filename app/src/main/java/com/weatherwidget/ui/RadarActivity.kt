package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.weatherwidget.shared.radar.ArgbImage
import com.weatherwidget.shared.radar.NoRadarFramesException
import com.weatherwidget.shared.radar.RadarBox
import com.weatherwidget.shared.radar.RadarFeed
import com.weatherwidget.shared.radar.RadarImageDecoder
import com.weatherwidget.shared.radar.RadarLegend
import com.weatherwidget.shared.radar.RadarPlayback
import com.weatherwidget.shared.radar.RadarRepository
import com.weatherwidget.shared.rainmap.RainLoopLoadError
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

/**
 * The precipitation graph's "Radar" pill: the last ~2 h of observed radar around the widget's
 * location (NWS mosaic inside NWS coverage, RainViewer elsewhere — `:shared` `RadarFeed`), looped
 * over the offline base map. Fetched when opened, never in the background.
 * See `plans/261021-radar-button-on-precip-graph.md`.
 */
@AndroidEntryPoint
class RadarActivity : AppCompatActivity() {

    @Inject lateinit var httpClient: HttpClient

    private lateinit var map: RadarMapView
    private lateinit var tvLocation: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var legend: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button

    private var frames: List<Pair<Long, Bitmap>> = emptyList()
    private var elapsedMs = 0L
    private var playing = true

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (playing && frames.size > 1) {
                elapsedMs += TICK_MS
                show(RadarPlayback.frameAt(elapsedMs, frames.size))
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar)
        map = findViewById(R.id.radar_map)
        tvLocation = findViewById(R.id.tv_location)
        tvSource = findViewById(R.id.tv_source)
        tvTime = findViewById(R.id.tv_time)
        tvEmpty = findViewById(R.id.tv_no_echoes)
        progress = findViewById(R.id.progress_loading)
        legend = findViewById(R.id.legend)
        seek = findViewById(R.id.sb_timeline)
        btnPlay = findViewById(R.id.btn_play_pause)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnPlay.setOnClickListener {
            playing = !playing
            btnPlay.setText(if (playing) R.string.rain_loop_pause else R.string.rain_loop_play)
        }
        findViewById<View>(R.id.btn_latest).setOnClickListener {
            if (frames.isEmpty()) return@setOnClickListener
            playing = false
            btnPlay.setText(R.string.rain_loop_play)
            elapsedMs = RadarPlayback.startOf(frames.size - 1)
            show(frames.size - 1)
        }
        // Scrubbing pauses on the chosen frame; Play resumes from it.
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && frames.isNotEmpty()) {
                    playing = false
                    btnPlay.setText(R.string.rain_loop_play)
                    elapsedMs = RadarPlayback.startOf(progress)
                    show(progress)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

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
        map.box = RadarBox.around(loc.first, loc.second)
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
        tvSource.setText(R.string.rain_loop_loading)
        tvSource.setOnClickListener(null)
        val feed = RadarFeed.forLocation(loc.first, loc.second)
        val repo = RadarRepository(httpClient, File(cacheDir, "radar"), BitmapDecoder)
        lifecycleScope.launch {
            repo.load(loc.first, loc.second)
                .onSuccess { loop ->
                    progress.visibility = View.GONE
                    frames = loop.frames.map { it.timeMs to it.image.toBitmap() }
                    map.box = loop.box
                    tvSource.text = getString(R.string.radar_attribution, loop.feed.attribution)
                    tvEmpty.visibility = if (loop.hasEchoes) View.GONE else View.VISIBLE
                    seek.max = (frames.size - 1).coerceAtLeast(0)
                    rebuildLegend(loop.feed)
                    // Open on the newest frame, then loop from the oldest.
                    elapsedMs = RadarPlayback.startOf(frames.size - 1)
                    show(frames.size - 1)
                }
                .onFailure {
                    progress.visibility = View.GONE
                    val host = RadarRepository.host(feed)
                    val error = if (it is NoRadarFramesException) RainLoopLoadError.noFrames(host, it.asked) else RainLoopLoadError.of(it, host)
                    tvSource.text = getString(R.string.sim_loop_error_tap_retry, rainLoopErrorText(this@RadarActivity, error))
                    tvSource.setOnClickListener { load(loc) }
                }
        }
    }

    private fun show(index: Int) {
        val (timeMs, bitmap) = frames.getOrNull(index) ?: return
        map.frame = bitmap
        seek.progress = index
        val clock = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault()))
        tvTime.text = getString(R.string.radar_frame_time, clock, RadarPlayback.minutesAgo(timeMs, System.currentTimeMillis()).toInt())
    }

    private fun rebuildLegend(feed: RadarFeed) {
        legend.removeAllViews()
        val stops = RadarLegend.stops(feed)
        legend.visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        for (stop in stops) {
            legend.addView(
                TextView(this).apply {
                    text = " ${stop.dbz} "
                    textSize = 11f
                    setTextColor(0xFF0F172A.toInt())
                    setBackgroundColor(stop.argb)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = (2 * density).toInt() }
                },
            )
        }
        if (stops.isNotEmpty()) {
            legend.addView(
                TextView(this).apply {
                    text = getString(R.string.radar_legend_unit)
                    textSize = 11f
                    setTextColor(0xFF94A3B8.toInt())
                },
            )
        }
    }

    /** `BitmapFactory` behind the shared repository's decoder seam. */
    private object BitmapDecoder : RadarImageDecoder {
        override fun decode(bytes: ByteArray): ArgbImage? {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return ArgbImage(bmp.width, bmp.height, pixels).also { bmp.recycle() }
        }
    }

    private fun ArgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    companion object {
        /** Same keys the pill's intent sets for every graph viewer (`SimLoopTouchTarget`). */
        const val EXTRA_LAT = RainForecastActivity.EXTRA_LAT
        const val EXTRA_LON = RainForecastActivity.EXTRA_LON
        private const val TICK_MS = 100L
    }
}
