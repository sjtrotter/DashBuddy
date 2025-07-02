package cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.data.models.dash.history.OrderSummary

class OrderSummaryDiffCallback : DiffUtil.ItemCallback<OrderSummary>() {
    override fun areItemsTheSame(oldItem: OrderSummary, newItem: OrderSummary): Boolean {
        // Use the unique orderId to see if the items are the same.
        return oldItem.orderId == newItem.orderId
    }

    override fun areContentsTheSame(oldItem: OrderSummary, newItem: OrderSummary): Boolean {
        // Let the data class handle content comparison.
        return oldItem == newItem
    }
}