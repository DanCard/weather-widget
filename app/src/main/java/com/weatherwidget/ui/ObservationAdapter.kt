package com.weatherwidget.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.observations.ObservationOrigin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ObservationAdapter(
    private val useCelsius: Boolean,
    @get:VisibleForTesting internal val onItemClick: (ObservationEntity) -> Unit,
) : RecyclerView.Adapter<ObservationAdapter.ViewHolder>() {
    internal var items: List<ObservationEntity> = emptyList()
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

    fun submitList(newList: List<ObservationEntity>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_weather_observation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.stationName.text = stationDisplayName(context, item.stationId, item.stationName)
        val distanceStr = if (item.distanceKm > 0) String.format(" • %.1f mi", item.distanceKm * 0.621371f) else ""
        holder.stationIdTime.text = "${item.stationId}$distanceStr • "

        val origin = ObservationOrigin.of(
            timestampMs = item.timestamp,
            qcFailed = item.qcFailed,
            isWebFallback = item.isWebFallback,
            nowMs = System.currentTimeMillis(),
        )
        val originStr = context.getString(
            when (origin) {
                ObservationOrigin.Kind.QC_FAILED -> R.string.station_origin_qc_failed
                ObservationOrigin.Kind.STALE -> R.string.station_origin_stale
                ObservationOrigin.Kind.WEB -> R.string.station_origin_web
                ObservationOrigin.Kind.API -> R.string.station_origin_api
            }
        )
        holder.stationTypeBadge.text = context.getString(R.string.station_type_origin_format, item.stationType, originStr)
        holder.stationTypeBadge.setTextColor(
            when {
                // Both error states mean "this reading is not in the blend" — say so in red.
                origin == ObservationOrigin.Kind.QC_FAILED || origin == ObservationOrigin.Kind.STALE -> COLOR_ERROR
                item.stationType == "OFFICIAL" -> COLOR_TYPE_OFFICIAL
                else -> COLOR_TYPE_PERSONAL
            }
        )

        holder.observationFetchTimes.text = buildTimesLine(
            context,
            timeFormatter.format(Instant.ofEpochMilli(item.timestamp)),
            timeFormatter.format(Instant.ofEpochMilli(item.fetchedAt))
        )

        if (origin == ObservationOrigin.Kind.QC_FAILED || origin == ObservationOrigin.Kind.STALE) {
            // Rejected by upstream QC, or too old to carry weight — either way the value is not
            // part of the blend, so showing it invites comparing it against a temp it never fed.
            holder.temperature.text = "—"
            holder.temperature.setTextColor(COLOR_TEXT_SECONDARY)
        } else {
            val displayTemp = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.temperature) else item.temperature
            holder.temperature.text = String.format("%.1f°", displayTemp)
            holder.temperature.setTextColor(obsTempToColor(item.temperature))
        }
        holder.condition.text = item.condition

        if (!item.rawMetar.isNullOrBlank()) {
            holder.rawMetar.text = item.rawMetar
            holder.rawMetar.visibility = View.VISIBLE
        } else {
            holder.rawMetar.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    // "Reported"/"Fetched" captions stay small and grey; the time values are the scan
    // target, so they render half again larger with the amber/blue staleness hues.
    private fun buildTimesLine(context: Context, reported: String, fetched: String): CharSequence {
        val builder = SpannableStringBuilder()
        fun appendSpan(text: String, color: Int, sizeSp: Int?) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (sizeSp != null) {
                builder.setSpan(AbsoluteSizeSpan(sizeSp, true), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        appendSpan(context.getString(R.string.obs_reported_prefix), COLOR_TEXT_SECONDARY, null)
        appendSpan(reported, COLOR_TIME_REPORTED, TIME_VALUE_SP)
        appendSpan(context.getString(R.string.obs_fetched_separator), COLOR_TEXT_SECONDARY, null)
        appendSpan(fetched, COLOR_TIME_FETCHED, TIME_VALUE_SP)
        return builder
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stationName: TextView = view.findViewById(R.id.station_name)
        val stationIdTime: TextView = view.findViewById(R.id.station_id_time)
        val stationTypeBadge: TextView = view.findViewById(R.id.station_type_badge)
        val observationFetchTimes: TextView = view.findViewById(R.id.observation_fetch_times)
        val temperature: TextView = view.findViewById(R.id.temperature)
        val condition: TextView = view.findViewById(R.id.condition)
        val rawMetar: TextView = view.findViewById(R.id.raw_metar)
    }

    companion object {
        @VisibleForTesting
        internal fun stationDisplayName(
            context: Context,
            stationId: String,
            fallback: String,
        ): String =
            if (stationId == TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID) {
                context.getString(R.string.station_name_tomorrow_five_minute_history)
            } else {
                fallback
            }

        private const val TIME_VALUE_SP = 21
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#AAAAAA")
        private val COLOR_TIME_REPORTED = Color.parseColor("#E8A24E")
        private val COLOR_TIME_FETCHED = Color.parseColor("#4FC3F7")
        private val COLOR_TYPE_OFFICIAL = Color.parseColor("#2BFF88")
        private val COLOR_TYPE_PERSONAL = Color.parseColor("#B0B0B8")
        // Matches the desktop staleness/error accent (#FF3366): QC-rejected and stale readings.
        private val COLOR_ERROR = Color.parseColor("#FF3366")

        private val COLOR_TEMP_COLD = Color.parseColor("#007AFF")
        private val COLOR_TEMP_MILD = Color.parseColor("#E8A24E")
        private val COLOR_TEMP_HOT = Color.parseColor("#FF3B30")

        /**
         * Temp→color tuned for text on near-black cards — mirrors the desktop app's
         * trayTempToColor (deeper blue than TemperatureGraphStyle's gradient, which
         * washes out at text sizes on dark backgrounds).
         */
        internal fun obsTempToColor(temp: Float): Int {
            fun blend(c1: Int, c2: Int, fraction: Float): Int {
                val f = fraction.coerceIn(0f, 1f)
                return Color.rgb(
                    (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt(),
                    (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt(),
                    (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
                )
            }
            return when {
                temp <= 50f -> COLOR_TEMP_COLD
                temp >= 90f -> COLOR_TEMP_HOT
                temp <= 70f -> blend(COLOR_TEMP_COLD, COLOR_TEMP_MILD, (temp - 50f) / 20f)
                else -> blend(COLOR_TEMP_MILD, COLOR_TEMP_HOT, (temp - 70f) / 20f)
            }
        }
    }
}
