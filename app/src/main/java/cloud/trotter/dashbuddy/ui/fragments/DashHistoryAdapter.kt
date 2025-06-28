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
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.models.DashSummary
import cloud.trotter.dashbuddy.data.models.OfferDisplay
import cloud.trotter.dashbuddy.data.models.OrderDisplay
import cloud.trotter.dashbuddy.data.models.ReceiptLineItem
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryBinding
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryReceiptLineBinding
import cloud.trotter.dashbuddy.databinding.ItemOfferDisplayBinding
import cloud.trotter.dashbuddy.databinding.ItemOrderDetailsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashHistoryAdapter(
    private val onDashClicked: (Long) -> Unit,
    private val onOfferClicked: (Long, String) -> Unit,
    private val onOfferInfoClicked: (String) -> Unit
) : ListAdapter<DashSummary, DashHistoryAdapter.DashSummaryViewHolder>(DashSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashSummaryViewHolder {
        val binding =
            ItemDashSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DashSummaryViewHolder(binding, onDashClicked, onOfferClicked, onOfferInfoClicked)
    }

    override fun onBindViewHolder(holder: DashSummaryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DashSummaryViewHolder(
        private val binding: ItemDashSummaryBinding,
        private val onDashClicked: (Long) -> Unit,
        private val onOfferClicked: (Long, String) -> Unit,
        private val onOfferInfoClicked: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(dashSummary: DashSummary) {
            val context = binding.root.context
            val dateFormat = SimpleDateFormat("MMM", Locale.getDefault())
            val dayFormat = SimpleDateFormat("d", Locale.getDefault())
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

            binding.dashMonth.text =
                dateFormat.format(Date(dashSummary.startTime)).uppercase(Locale.getDefault())
            binding.dashDay.text = dayFormat.format(Date(dashSummary.startTime))

            binding.dashEarnings.text = String.format(Locale.US, "$%.2f", dashSummary.totalEarned)
            val startTime = timeFormat.format(Date(dashSummary.startTime))
            val endTime = dashSummary.endTime?.let { timeFormat.format(Date(it)) } ?: "In Progress"
            binding.dashTime.text =
                context.getString(R.string.start_time_stop_time, startTime, endTime)

            binding.dashStats.text = context.getString(
                R.string.orders_miles,
                dashSummary.deliveryCount,
                String.format(Locale.US, "%.2f", dashSummary.totalMiles)
            )

            binding.dashHeaderClickable.setOnClickListener { onDashClicked(dashSummary.dashId) }

            if (dashSummary.isExpanded) {
                binding.offersRecyclerView.visibility = View.VISIBLE
                val offerAdapter = OfferAdapter(
                    onOfferClicked = { offerSummary ->
                        onOfferClicked(
                            dashSummary.dashId,
                            offerSummary
                        )
                    },
                    onOfferInfoClicked = onOfferInfoClicked
                )
                binding.offersRecyclerView.adapter = offerAdapter
                offerAdapter.submitList(dashSummary.offerDisplays)
            } else {
                binding.offersRecyclerView.visibility = View.GONE
            }
        }
    }

    class DashSummaryDiffCallback : DiffUtil.ItemCallback<DashSummary>() {
        override fun areItemsTheSame(oldItem: DashSummary, newItem: DashSummary): Boolean =
            oldItem.dashId == newItem.dashId

        override fun areContentsTheSame(oldItem: DashSummary, newItem: DashSummary): Boolean =
            oldItem == newItem
    }
}

class OfferAdapter(
    private val onOfferClicked: (String) -> Unit,
    private val onOfferInfoClicked: (String) -> Unit
) : ListAdapter<OfferDisplay, OfferAdapter.OfferViewHolder>(OfferDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferViewHolder {
        val binding =
            ItemOfferDisplayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OfferViewHolder(binding, onOfferClicked, onOfferInfoClicked)
    }

    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class OfferViewHolder(
        private val binding: ItemOfferDisplayBinding,
        private val onOfferClicked: (String) -> Unit,
        private val onOfferInfoClicked: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(offer: OfferDisplay) {
            val context = binding.root.context
            binding.offerSummaryText.text =
                context.getString(R.string.offer_summary_status, offer.summaryText, offer.status)
            binding.totalMilesText.text =
                context.getString(R.string.total_miles_format, offer.totalMiles)
            binding.totalAmountText.text = offer.totalAmount

            binding.offerHeaderSection.setOnClickListener { onOfferClicked(offer.summaryText) }
            binding.offerInfoButton.setOnClickListener { onOfferInfoClicked(offer.summaryText) }

            addIconsToContainer(binding.offerBadgeContainer, offer.offerBadges)

            if (offer.isExpanded) {
                binding.offerDetailsContainer.visibility = View.VISIBLE

                binding.doordashPayHeader.receiptHeaderTitle.text =
                    context.getString(R.string.doordash_pays)

                binding.payLinesRecyclerView.layoutManager = LinearLayoutManager(context)
                binding.payLinesRecyclerView.adapter = ReceiptLineAdapter(offer.payLines)

                binding.ordersRecyclerView.layoutManager = LinearLayoutManager(context)
                binding.ordersRecyclerView.adapter = OrderAdapter(offer.orders)

                // ****************** CORRECTED STATS BINDING ******************
                offer.actualStats?.let { stats ->
                    binding.actualStatsTime.text =
                        context.getString(R.string.actual_stats_time, stats.time)
                    binding.actualStatsDistance.text =
                        context.getString(R.string.actual_stats_distance, stats.distance)
                    binding.actualStatsPerMile.text =
                        context.getString(R.string.actual_stats_per_mile, stats.dollarsPerMile)
                    binding.actualStatsPerHour.text =
                        context.getString(R.string.actual_stats_per_hour, stats.dollarsPerHour)
                }
                // **********************************************************

            } else {
                binding.offerDetailsContainer.visibility = View.GONE
            }
        }

        private fun addIconsToContainer(container: LinearLayout, iconResIds: Set<Int>) {
            container.removeAllViews()
            val context = container.context
            iconResIds.forEach { resId ->
                val imageView = ImageView(context).apply {
                    setImageResource(resId)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        height = 60
                        width = 60
                        marginEnd = 8
                    }
                }
                container.addView(imageView)
            }
        }
    }

    class OfferDiffCallback : DiffUtil.ItemCallback<OfferDisplay>() {
        override fun areItemsTheSame(oldItem: OfferDisplay, newItem: OfferDisplay): Boolean =
            oldItem.summaryText == newItem.summaryText

        override fun areContentsTheSame(oldItem: OfferDisplay, newItem: OfferDisplay): Boolean =
            oldItem == newItem
    }
}

class OrderAdapter(private val orders: List<OrderDisplay>) :
    RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    class OrderViewHolder(val binding: ItemOrderDetailsBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding =
            ItemOrderDetailsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        val context = holder.itemView.context

        holder.binding.orderHeader.receiptHeaderTitle.text = order.summaryText

        holder.binding.tipLinesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ReceiptLineAdapter(order.tipLines)
            isNestedScrollingEnabled = false
        }
    }

    override fun getItemCount(): Int = orders.size
}

class ReceiptLineAdapter(private val items: List<ReceiptLineItem>) :
    RecyclerView.Adapter<ReceiptLineAdapter.ReceiptLineViewHolder>() {

    class ReceiptLineViewHolder(val binding: ItemDashSummaryReceiptLineBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptLineViewHolder {
        val binding = ItemDashSummaryReceiptLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ReceiptLineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReceiptLineViewHolder, position: Int) {
        val item = items[position]
        holder.binding.label.text = item.label
        holder.binding.amount.text = item.amount
    }

    override fun getItemCount(): Int = items.size
}