package cloud.trotter.dashbuddy.data.log.dash // Or wherever you want to keep your adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashLogAdapter :
    ListAdapter<DashLogItem, DashLogAdapter.LogMessageViewHolder>(DashLogDiffCallback()) {

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogMessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dash_log_message, parent, false)
        return LogMessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogMessageViewHolder, position: Int) {
        val logItem = getItem(position)
        holder.bind(logItem, timeFormatter)
    }

    class LogMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageTextView: TextView = itemView.findViewById(R.id.log_message_text)
        private val timestampTextView: TextView = itemView.findViewById(R.id.log_message_timestamp) // Optional

        fun bind(logItem: DashLogItem, formatter: SimpleDateFormat) {
            messageTextView.text = logItem.message

            // Handle timestamp visibility and formatting
            if (logItem.timestamp != null) {
                timestampTextView.text = formatter.format(Date(logItem.timestamp))
                timestampTextView.visibility = View.VISIBLE
            } else {
                timestampTextView.visibility = View.GONE
            }
        }
    }

    // DiffUtil helps RecyclerView efficiently update items
    class DashLogDiffCallback : DiffUtil.ItemCallback<DashLogItem>() {
        override fun areItemsTheSame(oldItem: DashLogItem, newItem: DashLogItem): Boolean {
            // If your items have unique IDs, compare them here.
            // For now, we'll assume message and timestamp make them unique enough for this example.
            // Or if they are always new, they are never "the same item".
            return oldItem === newItem // Simplest check, relies on object identity
        }

        override fun areContentsTheSame(oldItem: DashLogItem, newItem: DashLogItem): Boolean {
            return oldItem == newItem // Relies on DashLogItem being a data class
        }
    }
}
