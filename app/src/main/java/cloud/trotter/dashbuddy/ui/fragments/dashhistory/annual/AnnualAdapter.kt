package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ListAdapter
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryAnnualContentBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage

class AnnualAdapter(
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val annualViewModel: AnnualViewModel, // Pass in the specialist ViewModel
    private val onMonthClicked: (Int) -> Unit
) : ListAdapter<HistoryPage.Annual, AnnualPageViewHolder>(AnnualPageDiffCallback()) {

    private val tag = "AnnualAdapter"

    companion object {
        const val START_POSITION = 10_000
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnnualPageViewHolder {
        Logger.d(tag, "onCreateViewHolder for AnnualPage")
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentDashHistoryAnnualContentBinding.inflate(inflater, parent, false)
        // Pass the ViewModel to the ViewHolder
        return AnnualPageViewHolder(
            binding,
            fragment,
            stateViewModel,
            annualViewModel,
            onMonthClicked
        )
    }

    override fun onBindViewHolder(holder: AnnualPageViewHolder, position: Int) {
        Logger.d(tag, "onBindViewHolder for position: $position")
        // The ViewHolder now handles everything with just the position
        holder.bind()
    }
}