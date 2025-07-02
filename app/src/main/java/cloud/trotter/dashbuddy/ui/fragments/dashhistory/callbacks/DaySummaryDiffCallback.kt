package cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.data.models.dash.history.DaySummary

class DaySummaryDiffCallback : DiffUtil.ItemCallback<DaySummary>() {
    override fun areItemsTheSame(oldItem: DaySummary, newItem: DaySummary): Boolean {
        // A unique, stable ID is best. The date works well here since it's one item per day.
        return oldItem.date == newItem.date
    }

    override fun areContentsTheSame(oldItem: DaySummary, newItem: DaySummary): Boolean {
        // This checks if the item's content has changed, to see if it needs a redraw.
        // The data class 'equals' method handles this comparison for us.
        return oldItem == newItem
    }
}