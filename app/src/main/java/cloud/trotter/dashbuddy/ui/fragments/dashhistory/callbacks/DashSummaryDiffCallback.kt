package cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.data.models.dash.history.DashSummary

class DashSummaryDiffCallback : DiffUtil.ItemCallback<DashSummary>() {
    override fun areItemsTheSame(oldItem: DashSummary, newItem: DashSummary): Boolean {
        // Use the unique dashId to see if the items are the same.
        return oldItem.dashId == newItem.dashId
    }

    override fun areContentsTheSame(oldItem: DashSummary, newItem: DashSummary): Boolean {
        // Let the data class handle content comparison.
        return oldItem == newItem
    }
}