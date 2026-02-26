package com.weatherwidget.ui

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
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.widget.CurrentTempUpdateScheduler

@AndroidEntryPoint
class AppLogsActivity : AppCompatActivity() {
    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var forecastSnapshotDao: ForecastSnapshotDao

    private lateinit var adapter: AppLogAdapter
    private lateinit var statusText: TextView
    private lateinit var filterInput: EditText
    private var allLogs: List<AppLogEntity> = emptyList()
    private var totalLogCount: Int = 0
    private var snapshotCount: Int = 0
    private var dbSizeMb: Double = 0.0
    private var filterQuery: String = ""

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

        val recyclerView = findViewById<RecyclerView>(R.id.app_log_list)
        adapter = AppLogAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.refresh_current_temp_button).setOnClickListener {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = this,
                reason = "manual_logs_refresh",
                opportunistic = false,
            )
            Toast.makeText(this, getString(R.string.app_logs_refreshing_toast), Toast.LENGTH_SHORT).show()
            // Reload logs after a brief delay to see the start event
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                loadLogs()
            }
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

    private fun loadLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logs = appLogDao.getRecentLogs(3000)
            totalLogCount = appLogDao.getCount()
            snapshotCount = forecastSnapshotDao.getCount()
            dbSizeMb = getDatabaseSizeMb()

            withContext(Dispatchers.Main) {
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
        val filteredLogs = if (query.isBlank()) {
            allLogs
        } else {
            allLogs.filter { log ->
                log.tag.lowercase().contains(query) ||
                    log.level.lowercase().contains(query) ||
                    log.message.lowercase().contains(query)
            }
        }

        adapter.setItems(filteredLogs)

        val dbStats = "DB: %.1f MB (%d logs, %d snaps)".format(dbSizeMb, totalLogCount, snapshotCount)
        val filterStatus = if (query.isBlank()) {
            "Showing ${filteredLogs.size} recent"
        } else {
            "Showing ${filteredLogs.size} matching \"$filterQuery\""
        }

        statusText.text = "$dbStats\n$filterStatus"
    }
}
