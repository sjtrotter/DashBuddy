package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryMonthlyContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class MonthlyAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val monthlyViewModel: MonthlyViewModel,
    private val onDayClicked: (Int) -> Unit
) : ListAdapter<HistoryPage.Monthly, MonthlyPageViewHolder>(MonthlyPageDiffCallback()) {

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthlyPageViewHolder {
        Logger.d("MonthlyAdapter", "onCreateViewHolder for MonthlyPage")
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryMonthlyContentBinding.inflate(inflater, parent, false)
        return MonthlyPageViewHolder(
            binding,
            fragment,
            stateViewModel,
            monthlyViewModel,
            onDayClicked
        )
    }

    override fun onBindViewHolder(holder: MonthlyPageViewHolder, position: Int) {
        Logger.d("MonthlyAdapter", "onBindViewHolder for position: $position")
        holder.bind()
    }
}