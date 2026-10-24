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
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.shared.rainmap.WeatherNextLoop
import com.weatherwidget.data.remote.weathernext.*
import com.weatherwidget.util.FriendlyLocationName
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.WeatherNextGoneStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class RainForecastActivity : AppCompatActivity() {

    @Inject lateinit var weatherNextClient: WeatherNextForecastClient
    @Inject lateinit var appLogDao: AppLogDao

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
    private lateinit var errorPanel: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnErrorRetry: Button
    private lateinit var btnUseOpenMeteo: Button

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
    private var speedIndex = 1

    private val handler = Handler(Looper.getMainLooper())

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && framesForLayer.isNotEmpty()) {
                currentPos = WeatherNextLoop.advance(currentPos, framesForLayer.size, speedMultiplier)
                sbTimeline.progress = currentPos.toInt()
                renderFrame(currentPos)
            }
            handler.postDelayed(this, WeatherNextLoop.TICK_MS)
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
        errorPanel = findViewById(R.id.error_panel)
        tvErrorMessage = findViewById(R.id.tv_error_message)
        btnErrorRetry = findViewById(R.id.btn_error_retry)
        btnUseOpenMeteo = findViewById(R.id.btn_use_open_meteo)

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
        locationLabel = label?.split(",")?.firstOrNull()?.trim() ?: getString(R.string.rain_loop_current_location)
        domain = WeatherNextDomain.resolve(lat, lon)
        tvLocation.text = "📍 $locationLabel  ·  ${getString(domain.titleRes())}"
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            btnPlayPause.setText(if (isPlaying) R.string.rain_loop_pause else R.string.rain_loop_play)
        }

        btnPrev.setOnClickListener {
            if (framesForLayer.isNotEmpty()) {
                currentPos = WeatherNextLoop.step(currentPos, framesForLayer.size, -1)
                sbTimeline.progress = currentPos.toInt()
                renderFrame(currentPos)
            }
        }

        btnNext.setOnClickListener {
            if (framesForLayer.isNotEmpty()) {
                currentPos = WeatherNextLoop.step(currentPos, framesForLayer.size, +1)
                sbTimeline.progress = currentPos.toInt()
                renderFrame(currentPos)
            }
        }

        btnSpeedToggle.setOnClickListener {
            speedIndex = (speedIndex + 1) % WeatherNextLoop.SPEEDS.size
            speedMultiplier = WeatherNextLoop.SPEEDS[speedIndex]
            btnSpeedToggle.setText(
                when {
                    speedMultiplier <= 0.5f -> R.string.rain_loop_speed_0_5
                    speedMultiplier == 1.0f -> R.string.rain_loop_speed_1_0
                    speedMultiplier == 1.5f -> R.string.rain_loop_speed_1_5
                    else -> R.string.rain_loop_speed_2_5
                },
            )
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

        tvChartSubtitle.setText(selectedLayer.subtitleRes())
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
            text = "• ${getString(selectedLayer.legendRes())}"
            textSize = 10f
            setTextColor(0xFF94A3B8.toInt())
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        swatchesContainer.addView(noteTv)
    }

    private fun fetchManifest() {
        hideLoadError()
        lifecycleScope.launch {
            val store = WeatherNextGoneStore.of(this@RainForecastActivity)
            // A host known gone answers at once; only a real request shows the spinner.
            if (!WeatherNextAvailability.knownGone(store.goneAtMs, System.currentTimeMillis())) progressLoading.visibility = View.VISIBLE
            when (val outcome = WeatherNextLoop.loadManifest(weatherNextClient, domain, store, System.currentTimeMillis())) {
                is WeatherNextLoop.ManifestOutcome.Loaded -> {
                    val parsed = outcome.manifest
                    manifest = parsed
                    tvModelRun.text = getString(R.string.rain_loop_model_run, WeatherNextForecastClient.formatUtcToLocal(parsed.init ?: parsed.run, use24HourClock = android.text.format.DateFormat.is24HourFormat(this@RainForecastActivity)))
                    filterFramesForLayer()
                }
                is WeatherNextLoop.ManifestOutcome.Failed -> showLoadError(outcome.error)
            }
        }
    }

    private fun filterFramesForLayer() {
        val m = manifest ?: return
        framesForLayer = WeatherNextLoop.framesFor(m, selectedLayer.id)
        cachedBitmaps.values.forEach { it.recycle() }
        cachedBitmaps.clear()
        currentPos = 0.0

        sbTimeline.max = max(0, framesForLayer.size - 1)
        sbTimeline.progress = 0

        hideLoadError()
        if (framesForLayer.isEmpty()) {
            showLoadError(RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, 0))
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
            val error = WeatherNextLoop.prefetch(
                client = weatherNextClient,
                cacheDir = loopDir,
                domain = domain,
                layerId = layerId,
                manifest = m,
                frames = snapshot,
                stillWanted = { selectedLayer.id == layerId },
                decode = { decodeScaled(it, maxSide) },
                onFrame = { idx, bmp ->
                    cachedBitmaps.put(idx, bmp)?.recycle()
                    if (idx == 0) {
                        withContext(Dispatchers.Main) {
                            progressLoading.visibility = View.GONE
                            renderFrame(0.0)
                        }
                    }
                },
                onDiscard = { it.recycle() },
            )
            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
                error?.let { showLoadError(it) }
            }
        }
    }

    /** Says what failed, and offers Retry (unless the host is gone) and the Open-Meteo rain map. */
    internal fun showLoadError(error: RainLoopLoadError) {
        progressLoading.visibility = View.GONE
        tvModelRun.text = getString(R.string.sim_loop_model_run_unavailable)
        tvErrorMessage.text = rainLoopErrorText(this, error)
        btnErrorRetry.visibility = if (error.retryable) View.VISIBLE else View.GONE
        btnErrorRetry.setOnClickListener { fetchManifest() }
        btnUseOpenMeteo.setOnClickListener { openOpenMeteo(error) }
        errorPanel.visibility = View.VISIBLE
        logAsync(WeatherNextLoop.ERROR_TAG, WeatherNextLoop.errorLine(error, domain), "WARN")
    }

    private fun hideLoadError() {
        errorPanel.visibility = View.GONE
    }


    /** Same coordinates, Open-Meteo's own rain map; this viewer closes. */
    private fun openOpenMeteo(error: RainLoopLoadError) {
        logAsync(WeatherNextLoop.FALLBACK_TAG, WeatherNextLoop.fallbackLine(error), "INFO")
        startActivity(
            android.content.Intent(this, OpenMeteoRainLoopActivity::class.java).apply {
                putExtra(OpenMeteoRainLoopActivity.EXTRA_LAT, lat)
                putExtra(OpenMeteoRainLoopActivity.EXTRA_LON, lon)
            },
        )
        finish()
    }

    private fun logAsync(tag: String, message: String, level: String) {
        // NonCancellable: the fallback logs and then finishes, which would cancel a lifecycle child.
        lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { appLogDao.log(tag, message, level) } }
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
        val validLocal = WeatherNextForecastClient.formatUtcToLocal(framesForLayer[idx1].valid, use24HourClock = android.text.format.DateFormat.is24HourFormat(this))
        tvHudTitle.text = "${getString(selectedLayer.titleRes())} · F+${String.format("%03d", lead)}h"
        tvHudTime.text = getString(R.string.rain_loop_valid, validLocal)
    }
}

// The shared enums carry English for desktop; Android shows the translated resources.
private fun WeatherNextLayer.titleRes(): Int = when (this) {
    WeatherNextLayer.QPF1_IMERG -> R.string.rain_loop_title_rate
    WeatherNextLayer.PCP6 -> R.string.rain_loop_title_fronts
    WeatherNextLayer.QPF_ACC -> R.string.rain_loop_title_total
    WeatherNextLayer.IVT -> R.string.rain_loop_title_vapor
}

private fun WeatherNextLayer.subtitleRes(): Int = when (this) {
    WeatherNextLayer.QPF1_IMERG -> R.string.rain_loop_subtitle_rate
    WeatherNextLayer.PCP6 -> R.string.rain_loop_subtitle_fronts
    WeatherNextLayer.QPF_ACC -> R.string.rain_loop_subtitle_total
    WeatherNextLayer.IVT -> R.string.rain_loop_subtitle_vapor
}

private fun WeatherNextLayer.legendRes(): Int = when (this) {
    WeatherNextLayer.QPF1_IMERG -> R.string.rain_loop_legend_rate
    WeatherNextLayer.PCP6 -> R.string.rain_loop_legend_fronts
    WeatherNextLayer.QPF_ACC -> R.string.rain_loop_legend_total
    WeatherNextLayer.IVT -> R.string.rain_loop_legend_vapor
}

private fun WeatherNextDomain.titleRes(): Int = when (this) {
    WeatherNextDomain.CA -> R.string.rain_loop_domain_ca
    WeatherNextDomain.PNW -> R.string.rain_loop_domain_pnw
    WeatherNextDomain.WIDE -> R.string.rain_loop_domain_wide
}
