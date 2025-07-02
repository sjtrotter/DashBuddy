package cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.dash.history.OrderSummary
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryOrderDetailsBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks.OrderSummaryDiffCallback

class OrdersAdapter :
    ListAdapter<OrderSummary, OrdersAdapter.OrderViewHolder>(OrderSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemDashSummaryOrderDetailsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OrderViewHolder(private val binding: ItemDashSummaryOrderDetailsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(orderSummary: OrderSummary) {
            binding.orderSummary.text = orderSummary.summaryLine

            // This is the last level, so we just display the tip lines.
            setupNestedRecyclerView(orderSummary)
        }

        private fun setupNestedRecyclerView(orderSummary: OrderSummary) {
            val tipLinesAdapter = ReceiptLineAdapter() // Re-using our simple adapter
            binding.tipLinesRecyclerView.apply {
                layoutManager = LinearLayoutManager(binding.root.context)
                adapter = tipLinesAdapter
                isNestedScrollingEnabled = false
            }
            tipLinesAdapter.submitList(orderSummary.tipLines)
        }
    }
}