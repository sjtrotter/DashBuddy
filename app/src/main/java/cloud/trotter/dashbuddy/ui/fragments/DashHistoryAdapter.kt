package cloud.trotter.dashbuddy.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.DashItem
import cloud.trotter.dashbuddy.data.models.DaySummaryItem
import cloud.trotter.dashbuddy.data.models.HistoryListItem
import cloud.trotter.dashbuddy.data.models.MonthHeaderItem
import cloud.trotter.dashbuddy.data.models.OfferItem
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.databinding.ItemDashHistoryDashBinding
import cloud.trotter.dashbuddy.databinding.ItemDashHistoryOfferBinding
import cloud.trotter.dashbuddy.databinding.ItemDayGroupBinding
import cloud.trotter.dashbuddy.databinding.ItemMonthHeaderBinding
import cloud.trotter.dashbuddy.log.Logger as Log

class DashHistoryAdapter(
    private val onMonthClicked: (Long) -> Unit,
    private val onDayClicked: (Long) -> Unit,
    private val onDashClicked: (Long) -> Unit
) : ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(HistoryDiffCallback()) {

    private val vtMonth = 0
    private val vtDay = 1

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is MonthHeaderItem -> vtMonth
            is DaySummaryItem -> vtDay
            else -> throw IllegalStateException("Unexpected item type at position $position")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            vtMonth -> MonthViewHolder(
                ItemMonthHeaderBinding.inflate(inflater, parent, false),
                onMonthClicked
            )

            vtDay -> DayViewHolder(
                ItemDayGroupBinding.inflate(inflater, parent, false),
                onDayClicked,
                onDashClicked
            )

            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is MonthHeaderItem -> (holder as MonthViewHolder).bind(item)
            is DaySummaryItem -> (holder as DayViewHolder).bind(item)
            else -> Log.d("DashHistoryAdapter", "Unexpected item type: $item")
        }
    }

    class MonthViewHolder(
        private val binding: ItemMonthHeaderBinding,
        private val onMonthClicked: (Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MonthHeaderItem) {
            binding.monthHeaderText.text = item.monthYear
            binding.root.setOnClickListener { onMonthClicked(item.timestamp) }
        }
    }

    class DayViewHolder(
        private val binding: ItemDayGroupBinding,
        private val onDayClicked: (Long) -> Unit,
        private val onDashClicked: (Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DaySummaryItem) {
            binding.dayOfMonthText.text = item.dayOfMonth
            binding.dayOfWeekText.text = item.dayOfWeek
            binding.dayTotalEarningsText.text = item.totalEarnings
            binding.dayStatsLine1Text.text = item.statsLine1
            binding.dayStatsLine2Text.text = item.statsLine2
            binding.daySummaryHeader.setOnClickListener { onDayClicked(item.dateTimestamp) }

            binding.dashesRecyclerView.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            if (item.isExpanded) {
                val dashAdapter = InnerDashAdapter(onDashClicked)
                binding.dashesRecyclerView.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = dashAdapter
                }
                dashAdapter.submitList(item.dashes)
            }
        }
    }

    class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryListItem>() {
        override fun areItemsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem) =
            oldItem == newItem
    }
}

private class InnerDashAdapter(private val onDashClicked: (Long) -> Unit) :
    ListAdapter<DashItem, InnerDashAdapter.DashViewHolder>(DashDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DashViewHolder(
        ItemDashHistoryDashBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ), onDashClicked
    )

    override fun onBindViewHolder(holder: DashViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DashViewHolder(
        private val binding: ItemDashHistoryDashBinding,
        private val onDashClicked: (Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashItem) {
            binding.dashTimeRangeText.text = item.timeRange
            binding.dashDurationText.text = item.duration
            binding.dashHeader.setOnClickListener { onDashClicked(item.dashId) }
            binding.offersRecyclerView.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            if (item.isExpanded) {
                val offerAdapter = InnerOfferAdapter()
                binding.offersRecyclerView.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = offerAdapter
                }
                offerAdapter.submitList(item.offers)
            }
        }
    }

    class DashDiffCallback : DiffUtil.ItemCallback<DashItem>() {
        override fun areItemsTheSame(oldItem: DashItem, newItem: DashItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DashItem, newItem: DashItem) = oldItem == newItem
    }
}

private class InnerOfferAdapter :
    ListAdapter<OfferItem, InnerOfferAdapter.OfferViewHolder>(OfferDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OfferViewHolder(
        ItemDashHistoryOfferBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) =
        holder.bind(getItem(position))

    class OfferViewHolder(private val binding: ItemDashHistoryOfferBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: OfferItem) {
            if (item.status == OfferStatus.DECLINED_USER) {
                binding.offerSummaryText.text = "${item.summaryText} (Declined)"
                binding.offerPayMilesText.visibility = View.GONE
                binding.offerBadgeContainer.visibility = View.GONE
            } else {
                binding.offerSummaryText.text = item.summaryText
                binding.offerPayMilesText.text = item.payAndMiles
                binding.offerPayMilesText.visibility = View.VISIBLE
                binding.offerBadgeContainer.visibility = View.VISIBLE
                addIconsToContainer(binding.offerBadgeContainer, item.aggregatedBadges)
            }
        }

        private fun addIconsToContainer(container: LinearLayout, iconResIds: Set<Int>) {
            container.removeAllViews()
            val context = container.context
            iconResIds.forEach { resId ->
                container.addView(ImageView(context).apply {
                    setImageResource(resId)
                    layoutParams = LinearLayout.LayoutParams(48, 48).apply { marginEnd = 8 }
                })
            }
        }
    }

    class OfferDiffCallback : DiffUtil.ItemCallback<OfferItem>() {
        override fun areItemsTheSame(oldItem: OfferItem, newItem: OfferItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OfferItem, newItem: OfferItem) = oldItem == newItem
    }
}