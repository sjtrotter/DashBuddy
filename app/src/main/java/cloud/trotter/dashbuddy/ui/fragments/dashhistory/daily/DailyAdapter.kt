package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryDailyContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class DailyAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val historyRepo: DashHistoryRepo // Inject Repo directly
) : ListAdapter<HistoryPage.Daily, DailyPageViewHolder>(DailyPageDiffCallback()) {

    private val tag = "DailyAdapter"

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DailyPageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryDailyContentBinding.inflate(inflater, parent, false)
        // Note: We removed DailyViewModel from the constructor
        return DailyPageViewHolder(binding, fragment, stateViewModel)
    }

    override fun onBindViewHolder(holder: DailyPageViewHolder, position: Int) {
        // 1. Calculate the specific date for THIS page position
        val dayOffset = position - START_POSITION
        val pageDate = DashStateViewModel.REFERENCE_DAY_DATE.plusDays(dayOffset.toLong())

        Logger.v(tag, "Binding position $position to date $pageDate")

        // 2. Pass that specific date to the ViewHolder
        holder.bind(pageDate, historyRepo)
    }

    // Safety check: Cancel jobs when views are recycled to prevent memory leaks/crashes
    override fun onViewRecycled(holder: DailyPageViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }
}