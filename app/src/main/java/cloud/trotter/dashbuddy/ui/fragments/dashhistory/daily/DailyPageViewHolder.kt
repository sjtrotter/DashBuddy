//package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily
//
//import android.animation.ObjectAnimator
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.fragment.app.Fragment
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.DiffUtil
//import androidx.recyclerview.widget.ListAdapter
//import androidx.recyclerview.widget.RecyclerView
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryDailyContentBinding
//import cloud.trotter.dashbuddy.databinding.ItemDashHistoryDailyDashBinding
//import cloud.trotter.dashbuddy.databinding.ItemDashHistoryDailyOfferBinding
//import cloud.trotter.dashbuddy.databinding.ItemDashHistoryDailyReceiptLineBinding
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.StatDisplayMode
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
//import cloud.trotter.dashbuddy.util.UtilityFunctions
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.flow.collect
//import kotlinx.coroutines.flow.combine
//import kotlinx.coroutines.launch
//import java.time.LocalDate
//import java.util.Locale
//
//class DailyPageViewHolder(
//    private val binding: FragmentDashHistoryDailyContentBinding,
//    private val fragment: Fragment,
//    private val stateViewModel: DashStateViewModel
//) : RecyclerView.ViewHolder(binding.root) {
//
//    private val dashAdapter = DashAdapter()
//
//    // We keep track of the job so we can cancel it if the user swipes fast
//    private var dataJob: Job? = null
//
//    init {
//        binding.summaryCard.chipStatMode.setOnClickListener {
//            stateViewModel.toggleStatMode()
//        }
//        binding.dashesRecyclerView.adapter = dashAdapter
//    }
//
//    /**
//     * Binds this specific page to a specific Date.
//     * It fetches its OWN data, completely ignoring the currently selected global date.
//     */
//    fun bind(date: LocalDate, repo: DashHistoryRepo) {
//        // 1. Cancel any previous data fetching for this view (important for recycling)
//        unbind()
//
//        // 2. Start a new job for this specific date
//        dataJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
//            combine(
//                repo.getDailyDisplayFlow(date), // Fetch data for THIS date
//                stateViewModel.statDisplayMode  // This is still global (Active/Total toggle)
//            ) { display, mode ->
//                bindData(display, mode)
//            }.collect()
//        }
//    }
//
//    fun unbind() {
//        dataJob?.cancel()
//    }
//
//    private fun bindData(display: DailyDisplay, mode: StatDisplayMode) {
//        updateSummaryCard(display.stats, mode)
//        dashAdapter.submitList(display.dashSummaries)
//    }
//
//    // ... (Keep the rest of the file: updateSummaryCard, DashAdapter, OfferAdapter, etc.) ...
//    // ... (Ensure you copy the rest of the existing file here, no changes needed below this point) ...
//
//    private fun updateSummaryCard(stats: SummaryStats, mode: StatDisplayMode) {
//        val cardBinding = binding.summaryCard
//        cardBinding.textDashesAndZones.text =
//            "${stats.totalDashes} Dashes in ${stats.uniqueZoneCount} Zones"
//        cardBinding.textTotalEarnings.text = UtilityFunctions.formatCurrency(stats.totalEarnings)
//
//        when (mode) {
//            StatDisplayMode.ACTIVE -> {
//                cardBinding.chipStatMode.text = "ACTIVE"
//                cardBinding.chipStatMode.setChipIconResource(R.drawable.ic_shopping_bag_speed)
//                cardBinding.textHoursSummary.text = "${
//                    String.Companion.format(
//                        Locale.getDefault(),
//                        "%.1f",
//                        stats.activeHours
//                    )
//                } hrs • ${UtilityFunctions.formatCurrency(stats.activeDollarsPerHour)}/hr"
//                cardBinding.textMilesSummary.text = "${
//                    String.Companion.format(
//                        Locale.getDefault(),
//                        "%.1f",
//                        stats.activeMiles
//                    )
//                } mi • ${UtilityFunctions.formatCurrency(stats.activeDollarsPerMile)}/mi"
//            }
//
//            StatDisplayMode.TOTAL -> {
//                cardBinding.chipStatMode.text = "TOTAL"
//                cardBinding.chipStatMode.setChipIconResource(R.drawable.ic_shopping_bag)
//                cardBinding.textHoursSummary.text = "${
//                    String.Companion.format(
//                        Locale.getDefault(),
//                        "%.1f",
//                        stats.totalHours
//                    )
//                } hrs • ${UtilityFunctions.formatCurrency(stats.totalDollarsPerHour)}/hr"
//                cardBinding.textMilesSummary.text = "${
//                    String.Companion.format(
//                        Locale.getDefault(),
//                        "%.1f",
//                        stats.totalMiles
//                    )
//                } mi • ${UtilityFunctions.formatCurrency(stats.totalDollarsPerMile)}/mi"
//            }
//        }
//    }
//
//    inner class DashAdapter :
//        ListAdapter<DailyDisplay.DashSummary, DashItemViewHolder>(DashSummaryDiffCallback()) {
//        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashItemViewHolder {
//            val inflater = LayoutInflater.from(parent.context)
//            val itemBinding = ItemDashHistoryDailyDashBinding.inflate(inflater, parent, false)
//            return DashItemViewHolder(itemBinding)
//        }
//
//        override fun onBindViewHolder(holder: DashItemViewHolder, position: Int) {
//            holder.bind(getItem(position))
//        }
//    }
//
//    inner class DashItemViewHolder(private val binding: ItemDashHistoryDailyDashBinding) :
//        RecyclerView.ViewHolder(binding.root) {
//        private val offerAdapter = OfferAdapter()
//        private var isExpanded = false
//
//        init {
//            binding.offersRecyclerView.adapter = offerAdapter
//            binding.dashHeaderLayout.setOnClickListener {
//                isExpanded = !isExpanded
//                toggleExpansion()
//            }
//        }
//
//        fun bind(dashSummary: DailyDisplay.DashSummary) {
//            binding.textDashTime.text = "${dashSummary.startTime} - ${dashSummary.stopTime}"
//            binding.textDashZone.text = dashSummary.zoneName
//            binding.textDashEarnings.text =
//                UtilityFunctions.formatCurrency(dashSummary.totalEarnings)
//            offerAdapter.submitList(dashSummary.offerSummaries)
//        }
//
//        private fun toggleExpansion() {
//            binding.offersRecyclerView.visibility = if (isExpanded) View.VISIBLE else View.GONE
//            ObjectAnimator.ofFloat(binding.expandIcon, "rotation", if (isExpanded) 180f else 0f)
//                .setDuration(300).start()
//        }
//    }
//
//    inner class OfferAdapter :
//        ListAdapter<DailyDisplay.OfferSummary, OfferItemViewHolder>(OfferSummaryDiffCallback()) {
//        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferItemViewHolder {
//            val inflater = LayoutInflater.from(parent.context)
//            val itemBinding = ItemDashHistoryDailyOfferBinding.inflate(inflater, parent, false)
//            return OfferItemViewHolder(itemBinding)
//        }
//
//        override fun onBindViewHolder(holder: OfferItemViewHolder, position: Int) {
//            holder.bind(getItem(position))
//        }
//    }
//
//    inner class OfferItemViewHolder(private val binding: ItemDashHistoryDailyOfferBinding) :
//        RecyclerView.ViewHolder(binding.root) {
//        private val receiptAdapter = ReceiptLineAdapter()
//        private var isExpanded = false
//
//        init {
//            binding.payBreakdownRecyclerView.adapter = receiptAdapter
//        }
//
//        fun bind(offerSummary: DailyDisplay.OfferSummary) {
//            binding.textOfferSummary.text = offerSummary.summaryLine
//            binding.textOfferPay.text = UtilityFunctions.formatCurrency(offerSummary.actualPay)
//            receiptAdapter.submitList(offerSummary.payBreakdown)
//
//            if (offerSummary.status != OfferStatus.ACCEPTED) {
//                // Gray out declined/missed offers
//                binding.offerLayoutRoot.alpha = 0.5f
//                binding.offerHeader.isClickable = false
//                binding.offerExpandIcon.visibility = View.INVISIBLE
//            } else {
//                binding.offerLayoutRoot.alpha = 1.0f
//                binding.offerHeader.isClickable = true
//                binding.offerExpandIcon.visibility = View.VISIBLE
//                binding.offerHeader.setOnClickListener {
//                    isExpanded = !isExpanded
//                    toggleExpansion()
//                }
//            }
//        }
//
//        private fun toggleExpansion() {
//            binding.offerDetailsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
//            ObjectAnimator.ofFloat(
//                binding.offerExpandIcon,
//                "rotation",
//                if (isExpanded) 180f else 0f
//            ).setDuration(300).start()
//        }
//    }
//
//    inner class ReceiptLineAdapter :
//        ListAdapter<DailyDisplay.ReceiptLine, ReceiptLineViewHolder>(ReceiptLineDiffCallback()) {
//        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptLineViewHolder {
//            val inflater = LayoutInflater.from(parent.context)
//            val itemBinding =
//                ItemDashHistoryDailyReceiptLineBinding.inflate(inflater, parent, false)
//            return ReceiptLineViewHolder(itemBinding)
//        }
//
//        override fun onBindViewHolder(holder: ReceiptLineViewHolder, position: Int) {
//            holder.bind(getItem(position))
//        }
//    }
//
//    inner class ReceiptLineViewHolder(private val binding: ItemDashHistoryDailyReceiptLineBinding) :
//        RecyclerView.ViewHolder(binding.root) {
//        fun bind(receiptLine: DailyDisplay.ReceiptLine) {
//            binding.label.text = receiptLine.label
//            binding.amount.text = receiptLine.amount
//        }
//    }
//
//    class DashSummaryDiffCallback : DiffUtil.ItemCallback<DailyDisplay.DashSummary>() {
//        override fun areItemsTheSame(
//            oldItem: DailyDisplay.DashSummary,
//            newItem: DailyDisplay.DashSummary
//        ) = oldItem.dashId == newItem.dashId
//
//        override fun areContentsTheSame(
//            oldItem: DailyDisplay.DashSummary,
//            newItem: DailyDisplay.DashSummary
//        ) = oldItem == newItem
//    }
//
//    class OfferSummaryDiffCallback : DiffUtil.ItemCallback<DailyDisplay.OfferSummary>() {
//        override fun areItemsTheSame(
//            oldItem: DailyDisplay.OfferSummary,
//            newItem: DailyDisplay.OfferSummary
//        ) = oldItem.offerId == newItem.offerId
//
//        override fun areContentsTheSame(
//            oldItem: DailyDisplay.OfferSummary,
//            newItem: DailyDisplay.OfferSummary
//        ) = oldItem == newItem
//    }
//
//    class ReceiptLineDiffCallback : DiffUtil.ItemCallback<DailyDisplay.ReceiptLine>() {
//        override fun areItemsTheSame(
//            oldItem: DailyDisplay.ReceiptLine,
//            newItem: DailyDisplay.ReceiptLine
//        ) = oldItem.label == newItem.label && oldItem.amount == newItem.amount
//
//        override fun areContentsTheSame(
//            oldItem: DailyDisplay.ReceiptLine,
//            newItem: DailyDisplay.ReceiptLine
//        ) = oldItem == newItem
//    }
//}