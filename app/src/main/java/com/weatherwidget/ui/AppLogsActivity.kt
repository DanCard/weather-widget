package com.weatherwidget.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CurrentTempUpdateScheduler

@AndroidEntryPoint
class AppLogsActivity : AppCompatActivity() {
    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var forecastDao: ForecastDao

    private lateinit var adapter: AppLogAdapter
    private lateinit var statusText: TextView
    private lateinit var filterInput: EditText
    private var allLogs: List<AppLogEntity> = emptyList()
    private var totalLogCount: Int = 0
    private var snapshotCount: Int = 0
    private var dbSizeMb: Double = 0.0
    private var filterQuery: String = ""
    private var showVerbose: Boolean = false
    /** The selected provider chip; null = All. */
    private var sourceFilter: WeatherSource? = null
    private val sourceChips = mutableListOf<Pair<WeatherSource?, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_logs)

        setupViews()
        loadLogs()
    }

    private fun setupViews() {
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        statusText = findViewById(R.id.app_log_status)
        filterInput = findViewById(R.id.app_log_filter_input)
        filterInput.addTextChangedListener { editable ->
            filterQuery = editable?.toString()?.trim().orEmpty()
            applyFilter()
        }

        setupSourceChips()

        val recyclerView = findViewById<RecyclerView>(R.id.app_log_list)
        adapter = AppLogAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        val forceCheckbox = findViewById<android.widget.CheckBox>(R.id.force_refresh_checkbox)

        findViewById<Button>(R.id.refresh_current_temp_button).setOnClickListener {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = this,
                reason = "manual_logs_refresh",
                opportunistic = false,
                force = forceCheckbox.isChecked,
            )
            Toast.makeText(this, getString(R.string.app_logs_refreshing_toast), Toast.LENGTH_SHORT).show()
            // Reload logs after a brief delay to see the start event
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                loadLogs()
            }
        }

        val toggleVerboseBtn = findViewById<Button>(R.id.toggle_debug_button)
        toggleVerboseBtn.setText(R.string.app_logs_verbose)
        toggleVerboseBtn.setOnClickListener {
            showVerbose = !showVerbose
            if (showVerbose) {
                toggleVerboseBtn.setBackgroundResource(R.drawable.rounded_button_blue)
                toggleVerboseBtn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                toggleVerboseBtn.setBackgroundResource(R.drawable.rounded_button_gray)
                toggleVerboseBtn.setTextColor(0xFFAAAAAA.toInt())
            }
            applyFilter()
        }

        findViewById<Button>(R.id.share_app_logs_button).setOnClickListener {
            shareLogs()
        }

        findViewById<Button>(R.id.clear_app_logs_button).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                appLogDao.clearAllLogs()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AppLogsActivity, getString(R.string.app_logs_cleared), Toast.LENGTH_SHORT).show()
                    loadLogs()
                }
            }
        }
    }

    /** All + one chip per forecast provider, styled like the Verbose toggle; one selected at a time. */
    private fun setupSourceChips() {
        val row = findViewById<android.widget.LinearLayout>(R.id.app_log_source_chips)
        val density = resources.displayMetrics.density
        val options: List<Pair<WeatherSource?, String>> =
            listOf(null to getString(R.string.app_logs_filter_all)) +
                AppLogSourceFilter.sources.map { it to it.shortDisplayName }
        options.forEach { (source, label) ->
            val chip = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    sourceFilter = source
                    styleSourceChips()
                    loadLogs()
                }
            }
            row.addView(
                chip,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (4 * density).toInt() },
            )
            sourceChips += source to chip
        }
        styleSourceChips()
    }

    private fun styleSourceChips() {
        sourceChips.forEach { (source, chip) ->
            val selected = source == sourceFilter
            chip.setBackgroundResource(if (selected) R.drawable.rounded_button_blue else R.drawable.rounded_button_gray)
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
        }
    }

    /**
     * Dumps recent logs to text and fires an ACTION_SEND chooser so the user can email crash/diagnostic
     * logs to the developer — the recovery path for a CRASH row captured by the global handler. The dump
     * is capped because ACTION_SEND extras cross a Binder boundary (~1MB limit); an oversized EXTRA_TEXT
     * would throw TransactionTooLargeException in the receiving app.
     */
    private fun shareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logs = appLogDao.getRecentLogs(MAX_SHARE_LOG_ROWS)
            val dump = buildLogDump(logs)
            withContext(Dispatchers.Main) {
                if (dump.isBlank()) {
                    Toast.makeText(this@AppLogsActivity, getString(R.string.app_logs_share_empty), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_logs_share_subject))
                    putExtra(Intent.EXTRA_TEXT, dump)
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.app_logs_share_chooser)))
            }
        }
    }

    /** Builds the shareable text, newest first, truncated to stay under the Binder transaction limit. */
    private fun buildLogDump(logs: List<AppLogEntity>): String {
        val full = logs.joinToString("\n") { "${it.getFormattedTime()} ${it.level}/${it.tag}: ${it.message}" }
        return if (full.length <= MAX_SHARE_CHARS) {
            full
        } else {
            full.substring(0, MAX_SHARE_CHARS) + "\n…(truncated)"
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val source = sourceFilter
            val logs = if (source == null) {
                appLogDao.getRecentLogs(MAX_LOAD_ROWS)
            } else {
                // Whole table, not just the newest rows, so a failure from hours ago is still found.
                val (a, b, c) = AppLogSourceFilter.tokens(source)
                appLogDao.getLogsContainingAny(a, b, c, MAX_LOAD_ROWS)
            }
            totalLogCount = appLogDao.getCount()
            snapshotCount = forecastDao.getCount()
            dbSizeMb = getDatabaseSizeMb()

            withContext(Dispatchers.Main) {
                if (source != sourceFilter) return@withContext // a newer chip tap is loading
                allLogs = logs
                applyFilter()
            }
        }
    }

    private fun getDatabaseSizeMb(): Double {
        return try {
            val dbFile = getDatabasePath("weather_database")
            if (dbFile.exists()) {
                dbFile.length() / (1024.0 * 1024.0)
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private fun applyFilter() {
        val query = filterQuery.lowercase()
        val filteredLogs = allLogs.filter { log ->
            // Level Filter: Show VERBOSE only if showVerbose is true.
            // Hide only VERBOSE by default (show DEBUG, INFO, WARN, ERROR).
            val levelPass = showVerbose || log.level != "VERBOSE"
            
            // Search Filter
            val searchPass = if (query.isBlank()) {
                true
            } else {
                log.tag.lowercase().contains(query) ||
                    log.level.lowercase().contains(query) ||
                    log.message.lowercase().contains(query)
            }
            
            levelPass && searchPass && AppLogSourceFilter.matches(log, sourceFilter)
        }

        adapter.setItems(filteredLogs)

        val dbStats = getString(R.string.app_logs_db_stats, dbSizeMb, totalLogCount, snapshotCount)
        val levelStatus = getString(if (showVerbose) R.string.app_logs_all_levels else R.string.app_logs_debug_plus)
        // The provider chip reads as part of the filter text, e.g. `"Meteo timeout"`.
        val filterLabel = listOfNotNull(sourceFilter?.shortDisplayName, filterQuery.takeIf { it.isNotBlank() }).joinToString(" ")
        val filterStatus = if (filterLabel.isBlank()) {
            getString(R.string.app_logs_showing, filteredLogs.size, levelStatus)
        } else {
            getString(R.string.app_logs_showing_filtered, filteredLogs.size, filterLabel, levelStatus)
        }

        statusText.text = "$dbStats\n$filterStatus"
    }

    companion object {
        private const val MAX_LOAD_ROWS = 3000
        private const val MAX_SHARE_LOG_ROWS = 2000
        // Keep well under the ~1MB Binder transaction limit shared by all ACTION_SEND extras.
        private const val MAX_SHARE_CHARS = 450_000
    }
}
