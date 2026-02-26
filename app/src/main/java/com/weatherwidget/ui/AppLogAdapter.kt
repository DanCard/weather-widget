package com.weatherwidget.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity

class AppLogAdapter : RecyclerView.Adapter<AppLogAdapter.ViewHolder>() {
    private val items = mutableListOf<AppLogEntity>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val metaText: TextView = view.findViewById(R.id.log_meta_text)
        val messageText: TextView = view.findViewById(R.id.log_message_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.metaText.text = "${item.getFormattedTime()}  ${item.level}  ${item.tag}"
        holder.messageText.text = item.message
    }

    override fun getItemCount(): Int = items.size

    fun setItems(logs: List<AppLogEntity>) {
        items.clear()
        items.addAll(logs)
        notifyDataSetChanged()
    }
}
