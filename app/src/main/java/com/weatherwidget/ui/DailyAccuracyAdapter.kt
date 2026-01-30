package com.weatherwidget.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.stats.DailyAccuracy
import kotlin.math.abs

class DailyAccuracyAdapter : RecyclerView.Adapter<DailyAccuracyAdapter.ViewHolder>() {

    private var items = listOf<DailyAccuracy>()

    fun setItems(newItems: List<DailyAccuracy>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_accuracy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.item_date)
        private val sourceText: TextView = itemView.findViewById(R.id.item_source)
        private val actualTempsText: TextView = itemView.findViewById(R.id.item_actual_temps)
        private val forecastTempsText: TextView = itemView.findViewById(R.id.item_forecast_temps)
        private val errorText: TextView = itemView.findViewById(R.id.item_error)

        fun bind(item: DailyAccuracy) {
            dateText.text = item.date
            sourceText.text = item.source
            actualTempsText.text = "${item.actualHigh}° / ${item.actualLow}°"
            forecastTempsText.text = "${item.forecastHigh}° / ${item.forecastLow}°"

            val highErrorStr = if (item.highError >= 0) "+${item.highError}°" else "${item.highError}°"
            val lowErrorStr = if (item.lowError >= 0) "+${item.lowError}°" else "${item.lowError}°"
            errorText.text = "$highErrorStr / $lowErrorStr"

            // Color code the error based on magnitude
            val maxError = maxOf(abs(item.highError), abs(item.lowError))
            errorText.setTextColor(when {
                maxError <= 2 -> Color.parseColor("#34C759")  // Green
                maxError <= 5 -> Color.parseColor("#FFCC00")  // Yellow
                else -> Color.parseColor("#FF3B30")           // Red
            })
        }
    }
}
