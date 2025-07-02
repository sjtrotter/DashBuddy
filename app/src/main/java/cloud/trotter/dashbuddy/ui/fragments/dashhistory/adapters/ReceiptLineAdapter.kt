package cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.dash.history.ReceiptLine
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryReceiptLineBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks.ReceiptLineDiffCallback

class ReceiptLineAdapter :
    ListAdapter<ReceiptLine, ReceiptLineAdapter.ReceiptLineViewHolder>(ReceiptLineDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptLineViewHolder {
        val binding = ItemDashSummaryReceiptLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ReceiptLineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReceiptLineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ReceiptLineViewHolder(private val binding: ItemDashSummaryReceiptLineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(receiptLine: ReceiptLine) {
            binding.label.text = receiptLine.label
            binding.amount.text = receiptLine.amount
        }
    }
}