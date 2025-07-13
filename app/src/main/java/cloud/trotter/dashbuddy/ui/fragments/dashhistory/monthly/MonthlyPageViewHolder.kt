package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryMonthlyContentBinding
import cloud.trotter.dashbuddy.databinding.ItemDashHistoryMonthlyDayBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.StatDisplayMode
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
import cloud.trotter.dashbuddy.util.UtilityFunctions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

class MonthlyPageViewHolder(
    private val binding: FragmentDashHistoryMonthlyContentBinding,
    private val fragment: Fragment,
    private val stateViewModel: DashStateViewModel,
    private val monthlyViewModel: MonthlyViewModel,
    private val onDayClicked: (Int) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    private val dayCellBindings: List<View>
    private val weekRowBindings: List<View>

    init {
        binding.summaryCard.chipStatMode.setOnClickListener {
            stateViewModel.toggleStatMode()
        }
        // Collect all the week rows and day cells into lists for easy access
        weekRowBindings = listOf(
            binding.weekRow1, binding.weekRow2, binding.weekRow3,
            binding.weekRow4, binding.weekRow5, binding.weekRow6
        )
        dayCellBindings = weekRowBindings.flatMap { (it as ViewGroup).touchables }
    }

    fun bind() {
        // Observe the combined data from our ViewModels
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            combine(
                monthlyViewModel.monthlyDisplay,
                stateViewModel.statDisplayMode
            ) { display, mode ->
                bindData(display, mode)
            }.collect()
        }
    }

    private fun bindData(display: MonthlyDisplay, mode: StatDisplayMode) {
        updateSummaryCard(display.stats, mode)
        updateCalendarGrid(display)
    }

    private fun updateSummaryCard(stats: SummaryStats, mode: StatDisplayMode) {
        val cardBinding = binding.summaryCard
        cardBinding.textDashesAndZones.text = "${stats.totalDashes} Dashes"
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

    private fun updateCalendarGrid(display: MonthlyDisplay) {
        val firstDayOfMonth = display.date.withDayOfMonth(1)
        val daysInMonth = display.date.lengthOfMonth()
        val startOffset = (firstDayOfMonth.dayOfWeek.value % 7) // Sunday = 0, Monday = 1...
        val today = LocalDate.now()

        val summaryMap = display.calendarDays.associateBy { it.dayOfMonth }

        // Hide unused week rows
        val totalCellsNeeded = startOffset + daysInMonth
        weekRowBindings.forEachIndexed { index, row ->
            row.visibility = if (index * 7 < totalCellsNeeded) View.VISIBLE else View.GONE
        }

        dayCellBindings.forEachIndexed { index, cellView ->
            val dayOfMonth = index - startOffset + 1
            val cellBinding = ItemDashHistoryMonthlyDayBinding.bind(cellView)

            if (index < startOffset || dayOfMonth > daysInMonth) {
                cellBinding.root.visibility = View.INVISIBLE
                cellBinding.root.isClickable = false
            } else {
                cellBinding.root.visibility = View.VISIBLE
                cellBinding.textDayNumber.text = dayOfMonth.toString()

                val daySummary = summaryMap[dayOfMonth]

                if (daySummary != null && daySummary.hasData) {
                    cellBinding.textDayEarnings.visibility = View.VISIBLE
                    cellBinding.iconDayStatus.visibility = View.GONE
                    cellBinding.textDayEarnings.text = "$${floor(daySummary.totalEarnings).toInt()}"
                } else {
                    cellBinding.textDayEarnings.visibility = View.GONE
                    cellBinding.iconDayStatus.visibility = View.VISIBLE
                    val cellDate = display.date.withDayOfMonth(dayOfMonth)
                    if (cellDate.isAfter(today)) {
                        cellBinding.iconDayStatus.setImageResource(R.drawable.ic_menu_toolbar_history)
                        cellBinding.iconDayStatus.imageTintList = ContextCompat.getColorStateList(
                            cellBinding.root.context,
                            com.google.android.material.R.color.material_on_surface_disabled
                        )
                    } else {
                        cellBinding.iconDayStatus.setImageResource(R.drawable.ic_cancel)
                        cellBinding.iconDayStatus.imageTintList = ContextCompat.getColorStateList(
                            cellBinding.root.context,
                            com.google.android.material.R.color.design_default_color_error
                        )
                    }
                }

                cellBinding.root.isClickable = true
                cellBinding.root.setOnClickListener { onDayClicked(dayOfMonth) }
            }
        }
    }
}