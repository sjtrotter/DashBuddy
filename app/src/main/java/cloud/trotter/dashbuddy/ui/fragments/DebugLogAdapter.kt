package cloud.trotter.dashbuddy.ui.fragments

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.log.debug.DebugLogItem

class DebugLogAdapter :
    ListAdapter<DebugLogItem, DebugLogAdapter.DebugLogViewHolder>(DebugLogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebugLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debug_log_message, parent, false)
        return DebugLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: DebugLogViewHolder, position: Int) {
        val logItem = getItem(position)
        holder.bind(logItem)
    }

    class DebugLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageTextView: TextView = itemView.findViewById(R.id.debug_log_text)
        private val context: Context = itemView.context

        fun bind(logItem: DebugLogItem) {
            val message = logItem.message
            val logString = message.toString()

            // Apply color based on the log level prefix
            val color = when {
                logString.contains("ERROR/") -> ContextCompat.getColor(
                    context,
                    R.color.log_level_error
                )

                logString.contains("WARN/") -> ContextCompat.getColor(
                    context,
                    R.color.log_level_warn
                )

                logString.contains("INFO/") -> ContextCompat.getColor(
                    context,
                    R.color.log_level_info
                )

                logString.contains("DEBUG/") -> ContextCompat.getColor(
                    context,
                    R.color.log_level_debug
                )

                logString.contains("VERBOSE/") -> ContextCompat.getColor(
                    context,
                    R.color.log_level_verbose
                )

                else -> messageTextView.currentTextColor // Default to current text color
            }

            // Create a new SpannableString to apply the color to the whole line
            val spannable = SpannableString(message)
            spannable.setSpan(
                ForegroundColorSpan(color),
                0,
                spannable.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            messageTextView.text = spannable
        }
    }

    class DebugLogDiffCallback : DiffUtil.ItemCallback<DebugLogItem>() {
        override fun areItemsTheSame(oldItem: DebugLogItem, newItem: DebugLogItem): Boolean {
            return oldItem === newItem
        }

        override fun areContentsTheSame(oldItem: DebugLogItem, newItem: DebugLogItem): Boolean {
            return oldItem == newItem
        }
    }
}
