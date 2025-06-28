package cloud.trotter.dashbuddy.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.models.DashSummary
import cloud.trotter.dashbuddy.data.models.OfferDisplay
import cloud.trotter.dashbuddy.data.models.OrderDisplay
import cloud.trotter.dashbuddy.data.models.ReceiptLineItem
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryBinding
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryOfferDetailsBinding
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryOrderDetailsBinding
import cloud.trotter.dashbuddy.databinding.ItemDashSummaryReceiptLineBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashHistoryAdapter(
    private val onDashClicked: (Long) -> Unit,
    private val onOfferClicked: (Long, String) -> Unit
) : ListAdapter<DashSummary, DashHistoryAdapter.DashSummaryViewHolder>(DashSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashSummaryViewHolder {
        val binding =
            ItemDashSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DashSummaryViewHolder(binding, onDashClicked, onOfferClicked)
    }

    override fun onBindViewHolder(holder: DashSummaryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DashSummaryViewHolder(
        private val binding: ItemDashSummaryBinding,
        private val onDashClicked: (Long) -> Unit,
        private val onOfferClicked: (Long, String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(dashSummary: DashSummary) {
            val dateFormat = SimpleDateFormat("MMM", Locale.getDefault())
            val dayFormat = SimpleDateFormat("d", Locale.getDefault())
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

            binding.dashMonth.text =
                dateFormat.format(Date(dashSummary.startTime)).uppercase(Locale.getDefault())
            binding.dashDay.text = dayFormat.format(Date(dashSummary.startTime))

            binding.dashEarnings.text =
                String.format(Locale.getDefault(), "$%.2f", dashSummary.totalEarned)
            val startTime = timeFormat.format(Date(dashSummary.startTime))
            val endTime = dashSummary.endTime?.let { timeFormat.format(Date(it)) } ?: "In Progress"
            binding.dashTime.text =
                DashBuddyApplication.context.getString(
                    R.string.start_time_stop_time,
                    startTime,
                    endTime
                )

            binding.dashStats.text = DashBuddyApplication.context.getString(
                R.string.orders_miles, dashSummary.deliveryCount, String.format(
                    Locale.getDefault(),
                    "%.2f",
                    dashSummary.totalMiles
                )
            )

            if (dashSummary.isExpanded) {
                binding.offersRecyclerView.visibility = View.VISIBLE
                val offerAdapter = OfferAdapter(dashSummary.offerDisplays) { offerSummary ->
                    onOfferClicked(dashSummary.dashId, offerSummary)
                }
                binding.offersRecyclerView.apply {
                    layoutManager = LinearLayoutManager(binding.root.context)
                    adapter = offerAdapter
                }
            } else {
                binding.offersRecyclerView.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onDashClicked(dashSummary.dashId)
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
    offers: List<OfferDisplay>,
    private val onOfferClicked: (String) -> Unit
) : ListAdapter<OfferDisplay, OfferAdapter.OfferViewHolder>(OfferDiffCallback()) {

    init {
        submitList(offers)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferViewHolder {
        val binding = ItemDashSummaryOfferDetailsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OfferViewHolder(binding, onOfferClicked)
    }

    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class OfferViewHolder(
        private val binding: ItemDashSummaryOfferDetailsBinding,
        private val onOfferClicked: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(offer: OfferDisplay) {
            binding.offerSummary.text =
                DashBuddyApplication.context.getString(
                    R.string.offer_summary_status,
                    offer.summaryText,
                    offer.status
                )
            binding.root.setOnClickListener { onOfferClicked(offer.summaryText) }

            if (offer.isExpanded) {
                binding.offerDetailsContainer.visibility = View.VISIBLE
                binding.payLinesRecyclerView.adapter = ReceiptLineAdapter(offer.payLines)
                binding.ordersRecyclerView.adapter = OrderAdapter(offer.orders)
                binding.totalLine.label.text =
                    DashBuddyApplication.context.getString(R.string.total).padEnd(25, '.')
                binding.totalLine.amount.text = offer.total
            } else {
                binding.offerDetailsContainer.visibility = View.GONE
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
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemDashSummaryOrderDetailsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position])
    }

    override fun getItemCount(): Int = orders.size

    class OrderViewHolder(private val binding: ItemDashSummaryOrderDetailsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(order: OrderDisplay) {
            binding.orderSummary.text =
                DashBuddyApplication.context.getString(
                    R.string.order_at_store_and_status,
                    order.storeName,
                    order.status
                )
            binding.tipLinesRecyclerView.adapter = ReceiptLineAdapter(order.tipLines)
        }
    }
}

class ReceiptLineAdapter(private val items: List<ReceiptLineItem>) :
    RecyclerView.Adapter<ReceiptLineAdapter.ReceiptLineViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptLineViewHolder {
        val binding =
            ItemDashSummaryReceiptLineBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return ReceiptLineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReceiptLineViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ReceiptLineViewHolder(private val binding: ItemDashSummaryReceiptLineBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ReceiptLineItem) {
            // No more padding logic here. The XML handles alignment.
            binding.label.text = item.label
            binding.amount.text = item.amount
        }
    }
}