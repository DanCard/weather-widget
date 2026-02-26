package com.weatherwidget.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class AppLogsActivity : AppCompatActivity() {
    @Inject
    lateinit var appLogDao: AppLogDao

    private lateinit var adapter: AppLogAdapter
    private lateinit var statusText: TextView

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

        val recyclerView = findViewById<RecyclerView>(R.id.app_log_list)
        adapter = AppLogAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

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
            val logs = appLogDao.getRecentLogs(500)
            withContext(Dispatchers.Main) {
                adapter.setItems(logs)
                statusText.text =
                    if (logs.isEmpty()) {
                        getString(R.string.no_app_logs)
                    } else {
                        getString(R.string.app_logs_count, logs.size)
                    }
            }
        }
    }
}
