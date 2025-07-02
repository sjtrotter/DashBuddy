package cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks

import androidx.recyclerview.widget.DiffUtil
import cloud.trotter.dashbuddy.data.models.dash.history.OfferSummary

class OfferSummaryDiffCallback : DiffUtil.ItemCallback<OfferSummary>() {
    override fun areItemsTheSame(oldItem: OfferSummary, newItem: OfferSummary): Boolean {
        return oldItem.offerId == newItem.offerId
    }

    override fun areContentsTheSame(oldItem: OfferSummary, newItem: OfferSummary): Boolean {
        return oldItem == newItem
    }
}