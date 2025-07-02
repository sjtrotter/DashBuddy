package cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.models.dash.history.DashSummary
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks.DashSummaryDiffCallback
import java.util.Locale

class DashesAdapter :
    ListAdapter<DashSummary, DashesAdapter.DashSummaryViewHolder>(DashSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashSummaryViewHolder {
        val binding = ItemDashSummaryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DashSummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DashSummaryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DashSummaryViewHolder(private val binding: ItemDashSummaryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(dashSummary: DashSummary) {
            binding.dashEarnings.text = String.format(Locale.US, "$%.2f", dashSummary.earnings)
            binding.dashTime.text = dashSummary.time
            binding.dashStats.text = dashSummary.stats

            // Click listener to expand/collapse the offers list
            binding.dashHeaderClickable.setOnClickListener {
                dashSummary.isExpanded = !dashSummary.isExpanded
                updateVisibility(dashSummary)
            }

            updateVisibility(dashSummary)
        }

        private fun updateVisibility(dashSummary: DashSummary) {
            binding.offersRecyclerView.visibility =
                if (dashSummary.isExpanded) View.VISIBLE else View.GONE

            // Rotate the expansion icon
            val iconRes =
                if (dashSummary.isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.expandIcon.setImageDrawable(
                ContextCompat.getDrawable(
                    binding.root.context,
                    iconRes
                )
            )

            if (dashSummary.isExpanded) {
                setupNestedRecyclerView(dashSummary)
            }
        }

        private fun setupNestedRecyclerView(dashSummary: DashSummary) {
            // Create the next adapter in the chain: OffersAdapter
            val offersAdapter = OffersAdapter()
            binding.offersRecyclerView.apply {
                layoutManager = LinearLayoutManager(binding.root.context)
                adapter = offersAdapter
                isNestedScrollingEnabled = false
            }
            offersAdapter.submitList(dashSummary.offers)
        }
    }
}