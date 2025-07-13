package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryDailyContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class DailyAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val dailyViewModel: DailyViewModel
) : ListAdapter<HistoryPage.Daily, DailyPageViewHolder>(DailyPageDiffCallback()) {

    private val tag = "DailyAdapter"

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DailyPageViewHolder {
        Logger.d(tag, "onCreateViewHolder for DailyPage")
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryDailyContentBinding.inflate(inflater, parent, false)
        return DailyPageViewHolder(binding, fragment, stateViewModel, dailyViewModel)
    }

    override fun onBindViewHolder(holder: DailyPageViewHolder, position: Int) {
        Logger.d(tag, "onBindViewHolder for position: $position")
        // The ViewHolder handles all of its own logic from this point.
        holder.bind()
    }
}