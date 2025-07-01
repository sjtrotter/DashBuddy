package cloud.trotter.dashbuddy.ui.fragments

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.models.MonthHeaderItem
import cloud.trotter.dashbuddy.databinding.ItemMonthHeaderBinding
import androidx.core.graphics.withSave

class StickyMonthHeaderDecoration(
    private val adapter: DashHistoryAdapter,
    private val root: ViewGroup
) : RecyclerView.ItemDecoration() {

    private var stickyHeaderView: View? = null
    private var currentHeaderText: String? = null

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)

        val topChild = parent.getChildAt(0) ?: return
        val topChildPosition = parent.getChildAdapterPosition(topChild)
        if (topChildPosition == RecyclerView.NO_POSITION) {
            return
        }

        val headerText = getHeadertextForPosition(topChildPosition) ?: return
        if (currentHeaderText != headerText) {
            stickyHeaderView = createHeaderView(headerText, parent)
            currentHeaderText = headerText
        }

        val contactPoint = getHeaderView(topChildPosition + 1)?.top ?: 0
        val headerView = stickyHeaderView ?: return

        moveHeader(c, headerView, contactPoint)
    }

    private fun getHeaderView(position: Int): View? {
        if (position >= adapter.currentList.size || position < 0) return null
        val item = adapter.currentList[position]
        if (item !is MonthHeaderItem) return null

        // In a real scenario you would find the actual view holder,
        // but for positioning, just checking the type is often enough.
        // This logic simplifies finding the "next" header's top position.
        val layoutManager =
            (root.findViewById<RecyclerView>(R.id.dash_history_recycler_view)!!).layoutManager
        return layoutManager?.findViewByPosition(position)
    }


    private fun getHeadertextForPosition(position: Int): String? {
        var currentPos = position
        while (currentPos >= 0) {
            val item = adapter.currentList.getOrNull(currentPos)
            if (item is MonthHeaderItem) {
                return item.monthYear
            }
            currentPos--
        }
        return null
    }

    private fun createHeaderView(headerText: String, parent: RecyclerView): View {
        val binding =
            ItemMonthHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.monthHeaderText.text = headerText

        val view = binding.root
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec =
            View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED)

        val childWidth = ViewGroup.getChildMeasureSpec(
            widthSpec,
            parent.paddingLeft + parent.paddingRight,
            view.layoutParams.width
        )
        val childHeight = ViewGroup.getChildMeasureSpec(
            heightSpec,
            parent.paddingTop + parent.paddingBottom,
            view.layoutParams.height
        )

        view.measure(childWidth, childHeight)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun moveHeader(c: Canvas, headerView: View, contactPoint: Int) {
        c.withSave {
            if (contactPoint != 0 && contactPoint < headerView.height) {
                translate(0f, contactPoint.toFloat() - headerView.height)
            } else {
                translate(0f, 0f)
            }
            headerView.draw(this)
        }
    }
}