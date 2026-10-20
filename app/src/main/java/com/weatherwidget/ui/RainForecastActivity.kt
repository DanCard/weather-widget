package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.remote.weathernext.*
import com.weatherwidget.util.FriendlyLocationName
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class RainForecastActivity : AppCompatActivity() {

    @Inject lateinit var weatherNextClient: WeatherNextForecastClient

    private lateinit var ivMap: ImageView
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvLocation: TextView
    private lateinit var tvModelRun: TextView
    private lateinit var tvChartSubtitle: TextView
    private lateinit var tvHudTitle: TextView
    private lateinit var tvHudTime: TextView
    private lateinit var swatchesContainer: LinearLayout
    private lateinit var sbTimeline: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnSpeedToggle: TextView
    private lateinit var btnBack: ImageButton

    private lateinit var btnLayerQpf1: Button
    private lateinit var btnLayerPcp6: Button
    private lateinit var btnLayerQpfAcc: Button
    private lateinit var btnLayerIvt: Button

    private var domain = WeatherNextDomain.CA
    private var selectedLayer = WeatherNextLayer.QPF1_IMERG
    // No default location (CLAUDE.md): with none, the viewer says so and fetches nothing.
    private var lat: Double = Double.NaN
    private var lon: Double = Double.NaN
    private var locationLabel: String = ""

    private var manifest: WeatherNextManifest? = null
    private var framesForLayer: List<WeatherNextFrame> = emptyList()
    // Written by the prefetch coroutine (IO), read by the ticker (main).
    private val cachedBitmaps = ConcurrentHashMap<Int, Bitmap>()
    /** Two reusable output buffers, alternated so the ImageView never draws one mid-update. */
    private val outputBuffers = arrayOfNulls<Bitmap>(2)
    private var outputIndex = 0

    private var currentPos = 0.0
    // AUTO-PLAY: starts playing automatically
    private var isPlaying = true
    private var speedMultiplier = 1.0f
    private val speeds = listOf(0.5f, 1.0f, 1.5f, 2.5f)
    private var speedIndex = 1

    private val handler = Handler(Looper.getMainLooper())
    private val frameStepMs = 140L
    private val tickIntervalMs = 35L

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && framesForLayer.isNotEmpty()) {
                val advance = (tickIntervalMs * speedMultiplier) / frameStepMs.toFloat()
                val maxPos = (framesForLayer.size - 1).toDouble()
                var nextPos = currentPos + advance
                if (nextPos > maxPos) {
                    nextPos = 0.0
                }
                currentPos = nextPos
                sbTimeline.progress = nextPos.toInt()
                renderFrame(currentPos)
            }
            handler.postDelayed(this, tickIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rain_forecast)

        bindViews()
        loadLocation()
        setupListeners()
        updateLayerChipsUi()
        rebuildLegend()

        if (lat.isNaN()) {
            tvModelRun.text = getString(R.string.sim_loop_no_location)
            progressLoading.visibility = View.GONE
            return
        }
        fetchManifest()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickerRunnable)
        ivMap.setImageDrawable(null)
        cachedBitmaps.values.forEach { it.recycle() }
        cachedBitmaps.clear()
        outputBuffers.forEach { it?.recycle() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickerRunnable)
    }

    private fun bindViews() {
        ivMap = findViewById(R.id.iv_map)
        progressLoading = findViewById(R.id.progress_loading)
        tvLocation = findViewById(R.id.tv_location)
        tvModelRun = findViewById(R.id.tv_model_run)
        tvChartSubtitle = findViewById(R.id.tv_chart_subtitle)
        tvHudTitle = findViewById(R.id.tv_hud_title)
        tvHudTime = findViewById(R.id.tv_hud_time)
        swatchesContainer = findViewById(R.id.swatches_container)
        sbTimeline = findViewById(R.id.sb_timeline)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnSpeedToggle = findViewById(R.id.btn_speed_toggle)
        btnBack = findViewById(R.id.btn_back)

        btnLayerQpf1 = findViewById(R.id.btn_layer_qpf1)
        btnLayerPcp6 = findViewById(R.id.btn_layer_pcp6)
        btnLayerQpfAcc = findViewById(R.id.btn_layer_qpf_acc)
        btnLayerIvt = findViewById(R.id.btn_layer_ivt)
    }

    private fun loadLocation() {
        val loc = if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON)) {
            intent.getDoubleExtra(EXTRA_LAT, 0.0) to intent.getDoubleExtra(EXTRA_LON, 0.0)
        } else {
            ActiveLocationResolver.current(this)
                ?: run {
                    val appWidgetManager = AppWidgetManager.getInstance(this)
                    val componentName = ComponentName(this, WeatherWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    val stateManager = WidgetStateManager(this)
                    appWidgetIds.toList().firstNotNullOfOrNull { stateManager.getStoredWidgetLocation(it) }
                }
        }
        if (loc == null) {
            tvLocation.text = ""
            return
        }
        lat = loc.first
        lon = loc.second
        val label = ActiveLocationResolver.chosenLabel(this)
            ?: FriendlyLocationName.cached(this, lat, lon)
        locationLabel = label?.split(",")?.firstOrNull()?.trim() ?: "Current Location"
        domain = WeatherNextDomain.resolve(lat, lon)
        tvLocation.text = "📍 $locationLabel  ·  ${domain.title}"
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            btnPlayPause.text = if (isPlaying) "⏸ Pause" else "▶ Play"
        }

        btnPrev.setOnClickListener {
            if (framesForLayer.isNotEmpty()) {
                currentPos = max(0.0, currentPos.toInt() - 1.0)
                sbTimeline.progress = currentPos.toInt()
                renderFrame(currentPos)
            }
        }

        btnNext.setOnClickListener {
            if (framesForLayer.isNotEmpty()) {
                currentPos = min(framesForLayer.size - 1.0, currentPos.toInt() + 1.0)
                sbTimeline.progress = currentPos.toInt()
                renderFrame(currentPos)
            }
        }

        btnSpeedToggle.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            speedMultiplier = speeds[speedIndex]
            val desc = when {
                speedMultiplier <= 0.5f -> "0.5x (Smooth)"
                speedMultiplier == 1.0f -> "1.0x (Snappy)"
                speedMultiplier == 1.5f -> "1.5x (Fast)"
                else -> "2.5x (Blazing)"
            }
            btnSpeedToggle.text = desc
        }

        btnLayerQpf1.setOnClickListener { selectLayer(WeatherNextLayer.QPF1_IMERG) }
        btnLayerPcp6.setOnClickListener { selectLayer(WeatherNextLayer.PCP6) }
        btnLayerQpfAcc.setOnClickListener { selectLayer(WeatherNextLayer.QPF_ACC) }
        btnLayerIvt.setOnClickListener { selectLayer(WeatherNextLayer.IVT) }

        sbTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress < framesForLayer.size) {
                    currentPos = progress.toDouble()
                    renderFrame(currentPos)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun selectLayer(layer: WeatherNextLayer) {
        if (selectedLayer == layer) return
        selectedLayer = layer
        updateLayerChipsUi()
        rebuildLegend()
        filterFramesForLayer()
    }

    private fun updateLayerChipsUi() {
        val activeBg = 0xFF2563EB.toInt()
        val inactiveBg = 0xFF334155.toInt()

        btnLayerQpf1.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectedLayer == WeatherNextLayer.QPF1_IMERG) activeBg else inactiveBg)
        btnLayerPcp6.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectedLayer == WeatherNextLayer.PCP6) activeBg else inactiveBg)
        btnLayerQpfAcc.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectedLayer == WeatherNextLayer.QPF_ACC) activeBg else inactiveBg)
        btnLayerIvt.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectedLayer == WeatherNextLayer.IVT) activeBg else inactiveBg)

        tvChartSubtitle.text = selectedLayer.subtitle
    }

    private fun rebuildLegend() {
        swatchesContainer.removeAllViews()
        val density = resources.displayMetrics.density

        for (swatch in selectedLayer.swatches) {
            val tv = TextView(this).apply {
                text = " ${swatch.label} "
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                try {
                    val bg = Color.parseColor(swatch.hexColor)
                    setBackgroundColor(bg)
                    setTextColor(if (swatch.hexColor in listOf("#1f6fbf", "#e41800")) Color.WHITE else Color.BLACK)
                } catch (_: Exception) {}
                setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, (4 * density).toInt(), 0)
                layoutParams = lp
            }
            swatchesContainer.addView(tv)
        }

        val noteTv = TextView(this).apply {
            text = "• ${selectedLayer.legendNote}"
            textSize = 10f
            setTextColor(0xFF94A3B8.toInt())
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        swatchesContainer.addView(noteTv)
    }

    private fun fetchManifest() {
        progressLoading.visibility = View.VISIBLE
        tvModelRun.setOnClickListener(null)
        lifecycleScope.launch {
            weatherNextClient.fetchManifest(domain)
                .onSuccess { parsed ->
                    manifest = parsed
                    tvModelRun.text = "Model Run: ${WeatherNextForecastClient.formatUtcToLocal(parsed.init ?: parsed.run)}"
                    filterFramesForLayer()
                }
                .onFailure {
                    // Third-party host (GitHub Pages): it can vanish or change; say so and offer a retry.
                    progressLoading.visibility = View.GONE
                    tvModelRun.text = getString(R.string.sim_loop_load_failed)
                    tvModelRun.setOnClickListener { fetchManifest() }
                }
        }
    }

    private fun filterFramesForLayer() {
        val m = manifest ?: return
        val layerId = selectedLayer.id
        framesForLayer = m.frames.filter { it.files.containsKey(layerId) }
        cachedBitmaps.values.forEach { it.recycle() }
        cachedBitmaps.clear()
        currentPos = 0.0

        sbTimeline.max = max(0, framesForLayer.size - 1)
        sbTimeline.progress = 0

        if (framesForLayer.isEmpty()) {
            progressLoading.visibility = View.GONE
            return
        }

        prefetchFrames()
    }

    private fun prefetchFrames() {
        val m = manifest ?: return
        val layerId = selectedLayer.id
        val snapshot = ArrayList(framesForLayer)
        val loopDir = File(cacheDir, "forecast_loops").apply { mkdirs() }
        val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        lifecycleScope.launch(Dispatchers.IO) {
            WeatherNextFrameCache.pruneOtherRuns(loopDir, domain, layerId, m)
            for (idx in snapshot.indices) {
                if (selectedLayer.id != layerId) return@launch
                val file = weatherNextClient.cachedFrame(loopDir, domain, layerId, m, snapshot[idx]).getOrNull() ?: continue
                val bmp = decodeScaled(file, maxSide) ?: continue
                if (selectedLayer.id != layerId) {
                    bmp.recycle()
                    return@launch
                }
                cachedBitmaps.put(idx, bmp)?.recycle()
                if (idx == 0) {
                    withContext(Dispatchers.Main) {
                        progressLoading.visibility = View.GONE
                        renderFrame(0.0)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
            }
        }
    }

    /** Decodes [file] no larger than needed for a [maxSide]-pixel screen. */
    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun renderFrame(pos: Double) {
        if (framesForLayer.isEmpty()) return
        val idx1 = pos.toInt().coerceIn(0, framesForLayer.size - 1)
        val idx2 = (idx1 + 1).coerceAtMost(framesForLayer.size - 1)
        val alpha = (pos - idx1).toFloat().coerceIn(0f, 1f)

        val bmp1 = cachedBitmaps[idx1] ?: return
        val bmp2 = cachedBitmaps[idx2]

        // Create display bitmap with marker & HUD overlay
        val w = bmp1.width
        val h = bmp1.height
        outputIndex = 1 - outputIndex
        val output = outputBuffers[outputIndex]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                outputBuffers[outputIndex]?.recycle()
                outputBuffers[outputIndex] = it
            }
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw base frame
        canvas.drawBitmap(bmp1, 0f, 0f, paint)

        // Cross-fade frame 2 if smooth
        if (bmp2 != null && alpha > 0.01f && idx1 != idx2) {
            paint.alpha = (alpha * 255).toInt()
            canvas.drawBitmap(bmp2, 0f, 0f, paint)
            paint.alpha = 255
        }

        // Draw Pin Marker for location
        val (px, py) = WeatherNextCoordinateProjector.projectToPixel(
            lat = lat,
            lon = lon,
            domain = domain,
            figWidth = w.toFloat(),
            figHeight = h.toFloat(),
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, 11f, paint)
        paint.color = 0xFFEF4444.toInt()
        canvas.drawCircle(px, py, 8f, paint)

        paint.color = Color.BLACK
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(locationLabel, px + 15f, py + 7f, paint)
        paint.color = Color.WHITE
        canvas.drawText(locationLabel, px + 14f, py + 6f, paint)

        ivMap.setImageBitmap(output)

        // Update HUD
        val lead = framesForLayer[idx1].lead
        val validLocal = WeatherNextForecastClient.formatUtcToLocal(framesForLayer[idx1].valid)
        tvHudTitle.text = "${selectedLayer.shortTitle} · F+${String.format("%03d", lead)}h"
        tvHudTime.text = "Valid: $validLocal"
    }
}
