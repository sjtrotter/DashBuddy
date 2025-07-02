package cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.dash.history.DaySummary
import cloud.trotter.dashbuddy.databinding.ItemDaySummaryBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.callbacks.DaySummaryDiffCallback
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashHistoryAdapter :
    ListAdapter<DaySummary, DashHistoryAdapter.DaySummaryViewHolder>(DaySummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DaySummaryViewHolder {
        val binding = ItemDaySummaryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DaySummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DaySummaryViewHolder, position: Int) {
        val daySummary = getItem(position)
        holder.bind(daySummary)
    }

    inner class DaySummaryViewHolder(private val binding: ItemDaySummaryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val monthYearFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        private val dayOfMonthFormat = SimpleDateFormat("d", Locale.getDefault())

        fun bind(daySummary: DaySummary) {
            binding.dayMonthYear.text = monthYearFormat.format(daySummary.date).uppercase()
            binding.dayOfMonth.text = dayOfMonthFormat.format(daySummary.date)

            binding.dayEarnings.text =
                String.format(Locale.getDefault(), "$%.2f", daySummary.totalEarnings)

            val hours = TimeUnit.MILLISECONDS.toHours(daySummary.totalTimeInMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(daySummary.totalTimeInMillis) % 60
            binding.dayTimeAndDashes.text = String.format(
                Locale.getDefault(),
                "%dh %dm active • %d Dashes",
                hours,
                minutes,
                daySummary.dashCount
            )
            binding.dayStats.text = String.format(
                Locale.getDefault(),
                "%d Orders • %.2f Miles",
                daySummary.orderCount,
                daySummary.totalMiles
            )

            binding.dayHeaderClickable.setOnClickListener {
                daySummary.isExpanded = !daySummary.isExpanded
                updateVisibility(daySummary)
            }

            updateVisibility(daySummary)
        }

        private fun updateVisibility(daySummary: DaySummary) {
            binding.dashesRecyclerView.visibility =
                if (daySummary.isExpanded) View.VISIBLE else View.GONE
            if (daySummary.isExpanded) {
                setupNestedRecyclerView(daySummary)
            }
        }

        private fun setupNestedRecyclerView(daySummary: DaySummary) {
            val dashesAdapter = DashesAdapter()
            binding.dashesRecyclerView.apply {
                layoutManager = LinearLayoutManager(binding.root.context)
                adapter = dashesAdapter
                isNestedScrollingEnabled = false
            }
            dashesAdapter.submitList(daySummary.dashes)
        }
    }
}