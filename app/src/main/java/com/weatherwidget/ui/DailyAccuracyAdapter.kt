package com.weatherwidget.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.shared.stats.AccuracyBreakdown.DailyResult
import kotlin.math.abs
import kotlin.math.roundToInt

class DailyAccuracyAdapter(private val useCelsius: Boolean) : RecyclerView.Adapter<DailyAccuracyAdapter.ViewHolder>() {
    private var items = listOf<DailyResult>()

    fun setItems(newItems: List<DailyResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_daily_accuracy, parent, false)
        return ViewHolder(view, useCelsius)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View, private val useCelsius: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.item_date)
        private val sourceText: TextView = itemView.findViewById(R.id.item_source)
        private val actualTempsText: TextView = itemView.findViewById(R.id.item_actual_temps)
        private val forecastTempsText: TextView = itemView.findViewById(R.id.item_forecast_temps)
        private val errorText: TextView = itemView.findViewById(R.id.item_error)

        fun bind(item: DailyResult) {
            dateText.text = item.date
            // Provenance: an error figure that doesn't say what it was measured against is
            // misleading, especially when the graded source has no actuals of its own and
            // borrowed another source's.
            sourceText.text = buildString {
                append(item.source)
                item.baselineSourceId?.let {
                    append(" ")
                    append(itemView.context.getString(R.string.stats_baseline_vs_source, it))
                }
                item.baselineStationId?.let { append(" · ").append(it) }
                if (item.baselineFellBackToBlend) {
                    append(" · ").append(itemView.context.getString(R.string.stats_baseline_blend_fallback))
                }
            }

            val dispActualHigh = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.computedHighTemp.toFloat()).roundToInt() else item.computedHighTemp
            val dispActualLow = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.computedLowTemp.toFloat()).roundToInt() else item.computedLowTemp
            val dispForecastHigh = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.forecastHigh.toFloat()).roundToInt() else item.forecastHigh
            val dispForecastLow = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.forecastLow.toFloat()).roundToInt() else item.forecastLow

            val dispHighError = if (useCelsius) (item.highError.toFloat() / 1.8f).roundToInt() else item.highError
            val dispLowError = if (useCelsius) (item.lowError.toFloat() / 1.8f).roundToInt() else item.lowError

            actualTempsText.text = "$dispActualHigh° / $dispActualLow°"
            forecastTempsText.text = "$dispForecastHigh° / $dispForecastLow°"

            val highErrorStr = if (dispHighError >= 0) "+$dispHighError°" else "$dispHighError°"
            val lowErrorStr = if (dispLowError >= 0) "+$dispLowError°" else "$dispLowError°"
            errorText.text = "$highErrorStr / $lowErrorStr"

            // Color code the error based on magnitude
            val maxError = maxOf(abs(item.highError), abs(item.lowError))
            errorText.setTextColor(
                when {
                    maxError <= 2 -> Color.parseColor("#34C759") // Green
                    maxError <= 5 -> Color.parseColor("#FFCC00") // Yellow
                    else -> Color.parseColor("#FF3B30") // Red
                },
            )
        }
    }
}
