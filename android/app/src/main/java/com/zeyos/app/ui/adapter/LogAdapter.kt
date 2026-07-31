package com.zeyos.app.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zeyos.app.R
import com.zeyos.app.util.LogEntry
import com.zeyos.app.util.LogLevel

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private var items: List<LogEntry> = emptyList()

    fun submitList(newItems: List<LogEntry>) {
        items = newItems
        notifyDataSetChanged() // Fine for a bounded 500-entry buffer; not hot-path UI.
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvLogLine)

        fun bind(entry: LogEntry) {
            textView.text = entry.formatted()
            textView.setTextColor(
                when (entry.level) {
                    LogLevel.ERROR -> Color.parseColor("#FF5252")
                    LogLevel.WARN -> Color.parseColor("#FFC107")
                    LogLevel.INFO -> Color.parseColor("#FFFFFF")
                    LogLevel.DEBUG -> Color.parseColor("#9E9E9E")
                }
            )
        }
    }
}
