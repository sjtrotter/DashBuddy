package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryMonthlyContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class MonthlyAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val historyRepo: DashHistoryRepo, // Inject Repo
    private val onDayClicked: (Int) -> Unit
) : ListAdapter<HistoryPage.Monthly, MonthlyPageViewHolder>(MonthlyPageDiffCallback()) {

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthlyPageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryMonthlyContentBinding.inflate(inflater, parent, false)
        return MonthlyPageViewHolder(
            binding,
            fragment,
            stateViewModel,
            onDayClicked
        )
    }

    override fun onBindViewHolder(holder: MonthlyPageViewHolder, position: Int) {
        val monthOffset = position - START_POSITION
        // Calculate the specific date (Year + Month) for this page
        val pageDate = DashStateViewModel.REFERENCE_MONTH_DATE.plusMonths(monthOffset.toLong())

        Logger.v(
            "MonthlyAdapter",
            "Binding position $position to ${pageDate.month} ${pageDate.year}"
        )
        holder.bind(pageDate.year, pageDate.monthValue, historyRepo)
    }

    override fun onViewRecycled(holder: MonthlyPageViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }
}