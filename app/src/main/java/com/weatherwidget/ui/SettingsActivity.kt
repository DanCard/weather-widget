package com.weatherwidget.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.flexbox.FlexboxLayout
import dagger.hilt.android.AndroidEntryPoint

import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.util.ApiKeySignupUrls
import com.weatherwidget.shared.util.WeatherSourceOrdering
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.weatherwidget.widget.DominantTempWatchPreferences
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import java.util.UUID

import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var sharedLocationResolver: com.weatherwidget.data.repository.SharedLocationResolver

    private lateinit var notifyDominantTempSwitch: androidx.appcompat.widget.SwitchCompat

    /**
     * Keeps the switch honest while Settings is open: the watch is armed here but *disarmed* by the
     * sync worker when it fires, and without this the screen would keep showing a checked box for a
     * watch that is already spent.
     */
    private val watchPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != DominantTempWatchPreferences.KEY_ARMED) return@OnSharedPreferenceChangeListener
            val armed = widgetStateManager.notifyOnDominantTempChange()
            if (notifyDominantTempSwitch.isChecked != armed) {
                // setChecked would re-enter the listener below and re-arm what just fired.
                notifyDominantTempSwitch.setOnCheckedChangeListener(null)
                notifyDominantTempSwitch.isChecked = armed
                notifyDominantTempSwitch.setOnCheckedChangeListener(notifyDominantTempCheckedListener)
            }
        }

    private val notifyDominantTempCheckedListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setNotifyOnDominantTempChange(isChecked)
            if (isChecked) requestNotificationPermissionIfNeeded()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // The watch stays armed on purpose — the user can grant the permission later from
                // system settings and it will still be waiting. Only say that nothing will arrive.
                Toast.makeText(
                    this,
                    getString(R.string.notify_dominant_temp_permission_denied),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    /**
     * ACTION_REFRESH repaints every widget directly from cache in the broadcast handler.
     * The old triggerUiOnlyUpdate() went through WorkManager, whose "expedited" request
     * silently degrades to deferred work under quota/Doze — the repaint then took minutes.
     */
    private fun repaintWidgets() {
        sendBroadcast(
            Intent(this, WidgetActionReceiver::class.java).apply {
                action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
            },
        )
    }

    private fun setupViews() {
        // API Sources ordered checkable list
        setupApiSourcesList()

        // API Keys section
        setupApiKeysList()

        // Hourly graph zoom span slider
        setupHourlyZoomSpan()

        // Personal weather station discount slider
        setupPersonalStationDiscount()

        // Location Settings
        setupLocationSettings()

        // Refresh data button
        val refreshDataButton = findViewById<Button>(R.id.refresh_data_button)
        refreshDataButton.setOnClickListener {
            val forecastRefreshWork =
                WidgetWorkScheduler.enqueueRequiredImmediateSync(
                    context = this,
                    reason = "settings_manual_refresh",
                )
            val currentRefreshWork =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "settings_manual_refresh")
                            .build(),
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()

            val workManager = WorkManager.getInstance(this)
            workManager.enqueueUniqueWork(
                WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                currentRefreshWork,
            )

            Toast.makeText(this, getString(R.string.refresh_now_enqueued_toast), Toast.LENGTH_SHORT).show()
            refreshDataButton.isEnabled = false

            observeRefreshCompletion(
                workManager = workManager,
                refreshButton = refreshDataButton,
                forecastWorkId = forecastRefreshWork.id,
                currentWorkId = currentRefreshWork.id,
            )
        }

        val viewAppLogsButton = findViewById<Button>(R.id.view_app_logs_button)
        viewAppLogsButton.setOnClickListener {
            val intent = Intent(this, AppLogsActivity::class.java)
            startActivity(intent)
        }

        val submitBugReportButton = findViewById<Button>(R.id.submit_bug_report_button)
        submitBugReportButton.setOnClickListener {
            val intent = Intent(this, BugReportActivity::class.java)
            startActivity(intent)
        }

        val viewIconGalleryButton = findViewById<Button>(R.id.view_icon_gallery_button)
        viewIconGalleryButton.setOnClickListener {
            val intent = Intent(this, IconGalleryActivity::class.java)
            startActivity(intent)
        }

        val supportDevelopmentButton = findViewById<Button>(R.id.support_development_button)
        supportDevelopmentButton.setOnClickListener {
            val url = "https://paypal.me/DannyCarde"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.support_development_no_browser, url), Toast.LENGTH_LONG).show()
            }
        }

        // Use Celsius Switch
        val useCelsiusSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.use_celsius_switch)
        useCelsiusSwitch.isChecked = widgetStateManager.useCelsius()
        useCelsiusSwitch.setOnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setUseCelsius(isChecked)
            repaintWidgets()
        }

        // Daily View — Today Column overlay switches
        val overlayDeltaSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.today_overlay_delta_switch)
        overlayDeltaSwitch.isChecked = widgetStateManager.showTodayOverlayDelta()
        overlayDeltaSwitch.setOnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setShowTodayOverlayDelta(isChecked)
            repaintWidgets()
        }

        val overlayDominantTempSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.today_overlay_dominant_temp_switch)
        overlayDominantTempSwitch.isChecked = widgetStateManager.showTodayOverlayDominantTemp()
        overlayDominantTempSwitch.setOnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setShowTodayOverlayDominantTemp(isChecked)
            repaintWidgets()
        }

        val overlayDominantAgeSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.today_overlay_dominant_age_switch)
        overlayDominantAgeSwitch.isChecked = widgetStateManager.showTodayOverlayDominantAge()
        overlayDominantAgeSwitch.setOnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setShowTodayOverlayDominantAge(isChecked)
            repaintWidgets()
        }

        // Notifications — one-shot dominant-station temperature watch.
        notifyDominantTempSwitch = findViewById(R.id.notify_dominant_temp_change_switch)
        notifyDominantTempSwitch.isChecked = widgetStateManager.notifyOnDominantTempChange()
        notifyDominantTempSwitch.setOnCheckedChangeListener(notifyDominantTempCheckedListener)

        // App language: ACTION_APP_LOCALE_SETTINGS exists only on API 33+; older versions
        // have no per-app locale override, so the section stays gone there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findViewById<LinearLayout>(R.id.language_settings_section).visibility = View.VISIBLE
            findViewById<Button>(R.id.app_language_button).setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APP_LOCALE_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        ),
                    )
                } catch (e: ActivityNotFoundException) {
                    // Some OEM builds ship API 33+ without the per-app locale screen.
                    Toast.makeText(
                        this,
                        getString(R.string.app_language_settings_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.settings_title).setOnClickListener {
            finish()
        }
    }

    private val sourcesRequiringKeys = ApiKeySignupUrls.sourcesRequiringKeys

    private fun setupApiKeysList() {
        val container = findViewById<LinearLayout>(R.id.api_keys_container)
        container.removeAllViews()

        for (source in sourcesRequiringKeys) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_api_key, container, false)
            val nameView = row.findViewById<TextView>(R.id.source_name)
            val inputView = row.findViewById<EditText>(R.id.api_key_input)
            val getKeyButton = row.findViewById<Button>(R.id.get_api_key_button)

            nameView.text = source.displayName
            inputView.setText(widgetStateManager.getApiKey(source))

            val signupUrl = ApiKeySignupUrls.signupUrl(source)
            getKeyButton.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(signupUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.get_api_key_no_browser, signupUrl), Toast.LENGTH_LONG).show()
                }
            }

            inputView.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val key = s?.toString()?.trim()
                    widgetStateManager.setApiKey(source, if (key.isNullOrBlank()) null else key)
                }
            })

            container.addView(row)
        }
    }

    private fun sourceDescription(source: WeatherSource): String = when (source) {
        WeatherSource.SILURIAN -> getString(R.string.api_source_silurian_desc)
        WeatherSource.NWS -> getString(R.string.api_source_nws_desc)
        WeatherSource.TOMORROW_IO -> getString(R.string.api_source_tomorrowio_desc)
        WeatherSource.VISUAL_CROSSING -> getString(R.string.api_source_visualcrossing_desc)
        WeatherSource.OPEN_METEO -> getString(R.string.api_source_openmeteo_desc)
        WeatherSource.WEATHER_API -> getString(R.string.api_source_weatherapi_desc)
        WeatherSource.OPEN_WEATHER_MAP -> getString(R.string.api_source_openweathermap_desc)
        else -> ""
    }

    /**
     * Builds the ordered, checkable API source list in the container.
     * Each row has a checkbox (enable/disable), source name + description, and up/down arrows.
     */
    private fun setupApiSourcesList() {
        val container = findViewById<LinearLayout>(R.id.api_sources_container)
        rebuildSourceRows(container)
    }

    private fun rebuildSourceRows(container: LinearLayout) {
        container.removeAllViews()
        val visibleSources = widgetStateManager.getVisibleSourcesOrder()

        // Build full ordered list: visible sources first (in order), then hidden sources
        val availableSources = WeatherSourceOrdering.ALL_CONFIGURABLE
        val hiddenSources = availableSources.filter { it !in visibleSources }
        val orderedSources = visibleSources + hiddenSources

        for ((index, source) in orderedSources.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_api_source, container, false)

            val checkbox = row.findViewById<CheckBox>(R.id.source_checkbox)
            val nameView = row.findViewById<TextView>(R.id.source_name)
            val descView = row.findViewById<TextView>(R.id.source_description)
            val upButton = row.findViewById<ImageButton>(R.id.move_up_button)
            val downButton = row.findViewById<ImageButton>(R.id.move_down_button)

            val isVisible = source in visibleSources
            checkbox.isChecked = isVisible
            nameView.text = source.displayName
            descView.text = sourceDescription(source)

            // Dim hidden sources
            row.alpha = if (isVisible) 1.0f else 0.5f

            // Allow clicking the text container to toggle the checkbox
            val textContainer = nameView.parent as? View
            textContainer?.setOnClickListener {
                checkbox.toggle()
            }

            // Up/down only meaningful for visible sources
            upButton.visibility = if (isVisible && visibleSources.indexOf(source) > 0) View.VISIBLE else View.INVISIBLE
            downButton.visibility = if (isVisible && visibleSources.indexOf(source) < visibleSources.size - 1) View.VISIBLE else View.INVISIBLE

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && ApiKeySignupUrls.requiresUserKey(source)) {
                    val key = widgetStateManager.getApiKey(source)?.trim()
                    if (key.isNullOrBlank()) {
                        checkbox.isChecked = false
                        Toast.makeText(
                            this,
                            getString(R.string.api_key_required_to_enable, source.displayName),
                            Toast.LENGTH_LONG,
                        ).show()
                        val scrollView = findViewById<ScrollView>(R.id.settings_scroll_view)
                        val keysContainer = findViewById<View>(R.id.api_keys_container)
                        scrollView?.post {
                            if (keysContainer != null) {
                                scrollView.smoothScrollTo(0, keysContainer.top)
                            }
                        }
                        return@setOnCheckedChangeListener
                    }
                }
                val currentIds = widgetStateManager.getVisibleSourcesOrder().map { it.id }
                val updatedIds = WeatherSourceOrdering.toggle(currentIds, source, isChecked)
                if (updatedIds == null) {
                    checkbox.isChecked = true
                    Toast.makeText(this, getString(R.string.must_keep_one_source), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                Log.d("SOURCE_ORDER", "Checkbox: ${if (isChecked) "enabled" else "disabled"} ${source.name}, new list=$updatedIds")
                widgetStateManager.setVisibleSourcesOrder(updatedIds.map(WeatherSource::fromId))
                rebuildSourceRows(container)
            }

            upButton.setOnClickListener {
                val currentIds = widgetStateManager.getVisibleSourcesOrder().map { it.id }
                val updatedIds = WeatherSourceOrdering.moveUp(currentIds, source)
                if (updatedIds != currentIds) {
                    Log.d("SOURCE_ORDER", "Move up: ${source.name}, new list=$updatedIds")
                    widgetStateManager.setVisibleSourcesOrder(updatedIds.map(WeatherSource::fromId))
                    rebuildSourceRows(container)
                }
            }

            downButton.setOnClickListener {
                val currentIds = widgetStateManager.getVisibleSourcesOrder().map { it.id }
                val updatedIds = WeatherSourceOrdering.moveDown(currentIds, source)
                if (updatedIds != currentIds) {
                    Log.d("SOURCE_ORDER", "Move down: ${source.name}, new list=$updatedIds")
                    widgetStateManager.setVisibleSourcesOrder(updatedIds.map(WeatherSource::fromId))
                    rebuildSourceRows(container)
                }
            }

            container.addView(row)
        }
    }

    private fun observeRefreshCompletion(
        workManager: WorkManager,
        refreshButton: Button,
        forecastWorkId: UUID,
        currentWorkId: UUID,
    ) {
        var forecastFinished = false
        var currentFinished = false
        var refreshSucceeded = false
        var handled = false

        fun onFinished(workInfo: WorkInfo?, isForecastWork: Boolean) {
            if (workInfo == null || !workInfo.state.isFinished || handled) return

            if (isForecastWork) {
                forecastFinished = true
            } else {
                currentFinished = true
            }
            refreshSucceeded = refreshSucceeded || workInfo.state == WorkInfo.State.SUCCEEDED

            if (forecastFinished && currentFinished) {
                handled = true
                refreshButton.isEnabled = true
            }
        }

        workManager.getWorkInfoByIdLiveData(forecastWorkId).observe(this) { workInfo ->
            onFinished(workInfo, isForecastWork = true)
        }
        workManager.getWorkInfoByIdLiveData(currentWorkId).observe(this) { workInfo ->
            onFinished(workInfo, isForecastWork = false)
        }
    }

    /**
     * Span of the tight (NARROW) hourly zoom, 4–8h. The SeekBar is 0..4 and offset by
     * [HourlyZoomRules.MIN_NARROW_SPAN_HOURS], since SeekBar has no min before API 26.
     */
    private fun setupHourlyZoomSpan() {
        val seekBar = findViewById<SeekBar>(R.id.hourly_zoom_seekbar)
        val valueLabel = findViewById<TextView>(R.id.hourly_zoom_value)

        fun labelFor(spanHours: Int): String = getString(R.string.hourly_zoom_value, spanHours)

        val initial = widgetStateManager.getNarrowZoomSpanHours()
        seekBar.progress = initial - HourlyZoomRules.MIN_NARROW_SPAN_HOURS
        valueLabel.text = labelFor(initial)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = labelFor(progress + HourlyZoomRules.MIN_NARROW_SPAN_HOURS)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val progress = sb?.progress ?: return
                val spanHours = progress + HourlyZoomRules.MIN_NARROW_SPAN_HOURS
                widgetStateManager.setNarrowZoomSpanHours(spanHours)
                Log.d(
                    "SETTINGS",
                    "Hourly narrow zoom span set to ${spanHours}h " +
                        "(navJump=${HourlyZoomRules.navJumpHours(spanHours)}h)",
                )
                repaintWidgets()
            }
        })

        setupTwoDayZoomToggle()
    }

    /**
     * The optional multi-day stop in the tap cycle. Grouped with the span slider because both shape
     * the same cycle; see the layout comment in activity_settings.xml.
     *
     * Turning it off can strand a widget on a stage the cycle can no longer reach, so the repaint
     * matters: it drives the read path through [ZoomStage.resolve], which coerces those widgets back
     * to WIDE.
     */
    private fun setupTwoDayZoomToggle() {
        val toggle = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.hourly_zoom_two_day_switch,
        )
        toggle.isChecked = widgetStateManager.isMultiDayZoomEnabled()
        toggle.setOnCheckedChangeListener { _, isChecked ->
            widgetStateManager.setMultiDayZoomEnabled(isChecked)
            Log.d("SETTINGS", "Hourly 2-day zoom stage enabled=$isChecked")
            repaintWidgets()
        }
    }

    private fun setupPersonalStationDiscount() {
        val seekBar = findViewById<SeekBar>(R.id.personal_station_discount_seekbar)
        val valueLabel = findViewById<TextView>(R.id.personal_station_discount_value)

        fun labelFor(percent: Int): String = when (percent) {
            0 -> "0% — no discount (counts the same as official)"
            100 -> "100% — personal stations ignored"
            else -> "$percent% discount"
        }

        val initial = widgetStateManager.getPersonalStationDiscountPercent()
        seekBar.progress = initial
        valueLabel.text = labelFor(initial)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = labelFor(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val percent = sb?.progress ?: return
                widgetStateManager.setPersonalStationDiscountPercent(percent)
                Log.d("SETTINGS", "Personal station discount set to $percent%")
            }
        })
    }

    private fun setupLocationSettings() {
        findViewById<Button>(R.id.set_location_button).setOnClickListener {
            startActivity(
                Intent(this, ConfigActivity::class.java)
                    .putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
            )
        }
        refreshLocationLabel()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from the location setup screen.
        refreshLocationLabel()
        widgetStateManager.registerPreferenceListener(watchPrefsListener)
        // Catch a fire that landed while this screen was in the background — the listener only
        // covers writes that happen while it is registered.
        notifyDominantTempSwitch.setOnCheckedChangeListener(null)
        notifyDominantTempSwitch.isChecked = widgetStateManager.notifyOnDominantTempChange()
        notifyDominantTempSwitch.setOnCheckedChangeListener(notifyDominantTempCheckedListener)
    }

    override fun onPause() {
        super.onPause()
        widgetStateManager.unregisterPreferenceListener(watchPrefsListener)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshLocationLabel() {
        findViewById<TextView>(R.id.current_location_label).text =
            LocationUpdater.describeCurrentLocation(this)
        // Enrich with a reverse-geocoded place name once resolved (instant when cached).
        lifecycleScope.launch {
            val resolved = LocationUpdater.describeCurrentLocationResolved(this@SettingsActivity, sharedLocationResolver)
            findViewById<TextView>(R.id.current_location_label).text = resolved
        }
    }
}
