package cloud.trotter.dashbuddy.ui.fragments

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.data.models.DaySummaryItem
import cloud.trotter.dashbuddy.data.models.MonthHeaderItem
import cloud.trotter.dashbuddy.databinding.ItemDayGroupBinding
import cloud.trotter.dashbuddy.databinding.ItemMonthHeaderBinding
import androidx.core.graphics.withTranslation

class StickyHistoryHeaderDecoration(
    private val adapter: DashHistoryAdapter
) : RecyclerView.ItemDecoration() {

    private val monthHeaderViews = mutableMapOf<Int, View>()
    private val dayHeaderViews = mutableMapOf<Int, View>()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)

        val topChild = parent.getChildAt(0) ?: return
        val topChildPosition = parent.getChildAdapterPosition(topChild)
        if (topChildPosition == RecyclerView.NO_POSITION) return

        val currentMonthHeaderPos = findHeaderPositionFor(topChildPosition, isMonthHeader = true)
        var monthHeaderHeight = 0
        if (currentMonthHeaderPos != -1) {
            val monthHeaderView = getMonthHeaderView(currentMonthHeaderPos, parent)
            monthHeaderHeight = monthHeaderView.height
            val nextMonthHeaderPos =
                findHeaderPositionFor(topChildPosition + 1, isMonthHeader = true)
            val yOffset = getPushUpOffset(parent, monthHeaderView, nextMonthHeaderPos)
            drawHeader(c, monthHeaderView, yOffset)
        }

        val currentDayHeaderPos = findHeaderPositionFor(topChildPosition, isMonthHeader = false)
        if (currentDayHeaderPos != -1) {
            val dayHeaderView = getDayHeaderView(currentDayHeaderPos, parent)
            val nextDayHeaderPos =
                findHeaderPositionFor(topChildPosition + 1, isMonthHeader = false)
            val pushUpOffset = getPushUpOffset(parent, dayHeaderView, nextDayHeaderPos)

            // *** FIX: Ensure calculations result in a Float ***
            val finalY = monthHeaderHeight.toFloat() + pushUpOffset

            drawHeader(c, dayHeaderView, finalY)
        }
    }

    private fun getPushUpOffset(parent: RecyclerView, headerView: View, nextHeaderPos: Int): Float {
        if (nextHeaderPos != -1) {
            val nextHeaderView = parent.findViewHolderForAdapterPosition(nextHeaderPos)?.itemView
            if (nextHeaderView != null) {
                val offset = nextHeaderView.top - headerView.height
                if (offset < 0) return offset.toFloat()
            }
        }
        return 0f
    }

    private fun findHeaderPositionFor(adapterPosition: Int, isMonthHeader: Boolean): Int {
        for (i in adapterPosition downTo 0) {
            val item = adapter.currentList.getOrNull(i) ?: continue
            if (isMonthHeader && item is MonthHeaderItem) return i
            if (!isMonthHeader && item is DaySummaryItem) return i
        }
        return -1
    }

    private fun drawHeader(c: Canvas, headerView: View, yOffset: Float) {
        c.withTranslation(0f, yOffset) {
            headerView.draw(this)
        }
    }

    private fun getMonthHeaderView(position: Int, parent: RecyclerView): View {
        return monthHeaderViews.getOrPut(position) {
            val item = adapter.currentList[position] as MonthHeaderItem
            val binding =
                ItemMonthHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.monthHeaderText.text = item.monthYear
            measureAndLayout(binding.root, parent)
        }
    }

    private fun getDayHeaderView(position: Int, parent: RecyclerView): View {
        return dayHeaderViews.getOrPut(position) {
            val item = adapter.currentList[position] as DaySummaryItem
            val binding =
                ItemDayGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // *** FIX: Use the correct ID ***
            binding.dayOfMonthText.text = item.dayOfMonth
            binding.dayOfWeekText.text = item.dayOfWeek.uppercase()
            binding.dayTotalEarningsText.text = item.totalEarnings
            binding.dayStatsLine1Text.text = item.statsLine1
            binding.dayStatsLine2Text.text = item.statsLine2
            binding.dashesRecyclerView.visibility =
                View.GONE // We only want to draw the header part
            measureAndLayout(binding.root, parent)
        }
    }

    private fun measureAndLayout(view: View, parent: RecyclerView): View {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }
}