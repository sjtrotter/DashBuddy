package cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.data.models.dash.history.ReceiptLine

class ReceiptLineDiffCallback : DiffUtil.ItemCallback<ReceiptLine>() {
    override fun areItemsTheSame(oldItem: ReceiptLine, newItem: ReceiptLine): Boolean {
        // Receipt lines don't have a unique ID, so we can compare the labels.
        return oldItem.label == newItem.label
    }

    override fun areContentsTheSame(oldItem: ReceiptLine, newItem: ReceiptLine): Boolean {
        return oldItem == newItem
    }
}