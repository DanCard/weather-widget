package com.weatherwidget.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.shared.stats.DailyRainAccuracy
import com.weatherwidget.shared.util.DailyRainLabels

class DailyRainAccuracyAdapter : RecyclerView.Adapter<DailyRainAccuracyAdapter.ViewHolder>() {
    private var items = listOf<DailyRainAccuracy>()

    fun setItems(newItems: List<DailyRainAccuracy>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_daily_rain_accuracy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.item_rain_date)
        private val sourceText: TextView = itemView.findViewById(R.id.item_rain_source)
        private val dayText: TextView = itemView.findViewById(R.id.item_rain_day)
        private val nightText: TextView = itemView.findViewById(R.id.item_rain_night)

        fun bind(item: DailyRainAccuracy) {
            dateText.text = item.date
            sourceText.text = item.source
            dayText.text = formatPair(item.predDayMm, item.actualDayMm)
            nightText.text = formatPair(item.predNightMm, item.actualNightMm)
        }

        private fun formatPair(predMm: Float?, actualMm: Float?): String =
            itemView.context.getString(R.string.rain_pred_act_format, formatAmount(predMm), formatAmount(actualMm))

        private fun formatAmount(amountMm: Float?): String =
            if (amountMm == null) "—" else DailyRainLabels.formatPrecipAmount(amountMm)
    }
}
