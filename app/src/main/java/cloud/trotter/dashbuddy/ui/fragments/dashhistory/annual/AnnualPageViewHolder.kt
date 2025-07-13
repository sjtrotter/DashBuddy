package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryAnnualContentBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.StatDisplayMode
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
import cloud.trotter.dashbuddy.util.UtilityFunctions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor

class AnnualPageViewHolder(
    private val binding: FragmentDashHistoryAnnualContentBinding,
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val annualViewModel: AnnualViewModel,
    private val onMonthClicked: (Int) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.summaryCard.chipStatMode.setOnClickListener {
            stateViewModel.toggleStatMode()
        }
    }

    /**
     * The bind function is now much simpler. It doesn't need the position.
     * It just starts the data observation process.
     */
    fun bind() {
        // Launch a coroutine that combines the two flows we need to draw the screen.
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            combine(
                annualViewModel.annualDisplay,
                stateViewModel.statDisplayMode
            ) { display, mode ->
                // When either the data or the display mode changes, re-bind the UI.
                bindData(display, mode)
            }.collect()
        }
    }

    private fun bindData(display: AnnualDisplay, mode: StatDisplayMode) {
        updateSummaryCard(display.stats, mode)
        // Pass the year from the display model to correctly check against the current date
        updateMonthCards(display.monthSummaries, display.year)
    }

    private fun updateSummaryCard(stats: SummaryStats, mode: StatDisplayMode) {
        val cardBinding = binding.summaryCard
        cardBinding.textDashesAndZones.text =
            "${stats.totalDashes} Dashes in ${stats.uniqueZoneCount} Zones"
        cardBinding.textTotalEarnings.text = UtilityFunctions.formatCurrency(stats.totalEarnings)

        when (mode) {
            StatDisplayMode.ACTIVE -> {
                cardBinding.chipStatMode.text = "ACTIVE"
                cardBinding.chipStatMode.setChipIconResource(R.drawable.ic_shopping_bag_speed)
                cardBinding.textHoursSummary.text = "${
                    String.Companion.format(
                        Locale.getDefault(),
                        "%.1f",
                        stats.activeHours
                    )
                } hrs • ${UtilityFunctions.formatCurrency(stats.activeDollarsPerHour)}/hr"
                cardBinding.textMilesSummary.text = "${
                    String.Companion.format(
                        Locale.getDefault(),
                        "%.1f",
                        stats.activeMiles
                    )
                } mi • ${UtilityFunctions.formatCurrency(stats.activeDollarsPerMile)}/mi"
            }

            StatDisplayMode.TOTAL -> {
                cardBinding.chipStatMode.text = "TOTAL"
                cardBinding.chipStatMode.setChipIconResource(R.drawable.ic_shopping_bag)
                cardBinding.textHoursSummary.text = "${
                    String.Companion.format(
                        Locale.getDefault(),
                        "%.1f",
                        stats.totalHours
                    )
                } hrs • ${UtilityFunctions.formatCurrency(stats.totalDollarsPerHour)}/hr"
                cardBinding.textMilesSummary.text = "${
                    String.Companion.format(
                        Locale.getDefault(),
                        "%.1f",
                        stats.totalMiles
                    )
                } mi • ${UtilityFunctions.formatCurrency(stats.totalDollarsPerMile)}/mi"
            }
        }
    }

    private fun updateMonthCards(
        monthSummaries: List<AnnualDisplay.MonthInYearSummary>,
        year: Int
    ) {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH) + 1 // Calendar months are 0-11

        val contentBindings = listOf(
            binding.monthContent1, binding.monthContent2, binding.monthContent3,
            binding.monthContent4, binding.monthContent5, binding.monthContent6,
            binding.monthContent7, binding.monthContent8, binding.monthContent9,
            binding.monthContent10, binding.monthContent11, binding.monthContent12
        )

        monthSummaries.forEachIndexed { index, summary ->
            val bindingForMonth = contentBindings[index]
            bindingForMonth.textMonthAbbreviation.text = summary.monthNameAbbreviation

            if (summary.hasData) {
                bindingForMonth.iconMonthStatus.visibility = View.GONE
                bindingForMonth.textMonthEarnings.visibility = View.VISIBLE
                bindingForMonth.textMonthEarnings.text = "$${floor(summary.totalEarnings).toInt()}"
            } else {
                // This month has no data, so we show an icon.
                bindingForMonth.iconMonthStatus.visibility = View.VISIBLE
                bindingForMonth.textMonthEarnings.visibility = View.GONE

                // Check if the month is in the future relative to today.
                if (year > currentYear || (year == currentYear && summary.month > currentMonth)) {
                    // It's a future month.
                    bindingForMonth.iconMonthStatus.setImageResource(R.drawable.ic_menu_toolbar_history)
                    val neutralColor = ContextCompat.getColor(
                        binding.root.context,
                        com.google.android.material.R.color.material_on_surface_disabled
                    )
                    ImageViewCompat.setImageTintList(
                        bindingForMonth.iconMonthStatus,
                        ColorStateList.valueOf(neutralColor)
                    )
                } else {
                    // It's a past month with no dashes.
                    bindingForMonth.iconMonthStatus.setImageResource(R.drawable.ic_cancel)
                    val errorColor = ContextCompat.getColor(
                        binding.root.context,
                        com.google.android.material.R.color.design_default_color_error
                    )
                    ImageViewCompat.setImageTintList(
                        bindingForMonth.iconMonthStatus,
                        ColorStateList.valueOf(errorColor)
                    )
                }
            }

            (bindingForMonth.root.parent as View).setOnClickListener { onMonthClicked(summary.month) }
        }
    }
}