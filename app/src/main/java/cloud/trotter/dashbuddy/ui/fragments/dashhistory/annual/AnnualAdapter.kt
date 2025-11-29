package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryAnnualContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class AnnualAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val historyRepo: DashHistoryRepo, // Inject Repo
    private val onMonthClicked: (Int) -> Unit
) : ListAdapter<HistoryPage.Annual, AnnualPageViewHolder>(AnnualPageDiffCallback()) {

    private val tag = "AnnualAdapter"

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnnualPageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryAnnualContentBinding.inflate(inflater, parent, false)
        return AnnualPageViewHolder(
            binding,
            fragment,
            stateViewModel,
            onMonthClicked
        )
    }

    override fun onBindViewHolder(holder: AnnualPageViewHolder, position: Int) {
        // 1. Calculate the specific Year for this page
        // We use the reference date (2020) and add the offset
        val yearOffset = position - START_POSITION
        val pageYear = DashStateViewModel.REFERENCE_MONTH_DATE.year + yearOffset

        Logger.v(tag, "Binding position $position to year $pageYear")
        holder.bind(pageYear, historyRepo)
    }

    override fun onViewRecycled(holder: AnnualPageViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }
}