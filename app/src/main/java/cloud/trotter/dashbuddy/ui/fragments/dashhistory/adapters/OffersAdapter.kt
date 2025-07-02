package cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.dash.history.OfferSummary
import cloud.trotter.dashbuddy.databinding.ItemOfferDisplayBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks.OfferSummaryDiffCallback

class OffersAdapter :
    ListAdapter<OfferSummary, OffersAdapter.OfferViewHolder>(OfferSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferViewHolder {
        val binding = ItemOfferDisplayBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OfferViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OfferViewHolder(private val binding: ItemOfferDisplayBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(offerSummary: OfferSummary) {
            binding.offerSummary.text = offerSummary.summaryLine

            // Click listener to expand/collapse the details
            binding.root.setOnClickListener {
                offerSummary.isExpanded = !offerSummary.isExpanded
                updateVisibility(offerSummary)
            }

            updateVisibility(offerSummary)
        }

        private fun updateVisibility(offerSummary: OfferSummary) {
            binding.offerDetailsContainer.visibility =
                if (offerSummary.isExpanded) View.VISIBLE else View.GONE

            if (offerSummary.isExpanded) {
                setupNestedRecyclerViews(offerSummary)
            }
        }

        private fun setupNestedRecyclerViews(offerSummary: OfferSummary) {
            // Setup the DoorDash pay lines
            val payLinesAdapter = ReceiptLineAdapter()
            binding.payLinesRecyclerView.apply {
                layoutManager = LinearLayoutManager(binding.root.context)
                adapter = payLinesAdapter
                isNestedScrollingEnabled = false
            }
            payLinesAdapter.submitList(offerSummary.doordashPayLines)

            // Setup the nested orders
            val ordersAdapter = OrdersAdapter() // Our final adapter
            binding.ordersRecyclerView.apply {
                layoutManager = LinearLayoutManager(binding.root.context)
                adapter = ordersAdapter
                isNestedScrollingEnabled = false
            }
            ordersAdapter.submitList(offerSummary.orders)

            // Bind the total line
            binding.totalLine.label.text = offerSummary.totalLine.label
            binding.totalLine.amount.text = offerSummary.totalLine.amount
        }
    }
}