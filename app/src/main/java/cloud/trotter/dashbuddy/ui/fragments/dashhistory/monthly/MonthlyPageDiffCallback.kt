package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class MonthlyPageDiffCallback : DiffUtil.ItemCallback<HistoryPage.Monthly>() {
    override fun areItemsTheSame(oldItem: HistoryPage.Monthly, newItem: HistoryPage.Monthly): Boolean {
        // For placeholder pages, identity can be tricky.
        // A simple equality check on the data class works well.
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: HistoryPage.Monthly, newItem: HistoryPage.Monthly): Boolean {
        return oldItem == newItem
    }
}