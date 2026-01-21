package cloud.trotter.dashbuddy.ui.settings.components

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R

/**
 * Automagically applies rounded corners to groups of preferences.
 * Mimics the Android 14 / Pixel Settings style.
 */
class GroupedRectDecoration(private val pixelPadding: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        // Add vertical spacing between the visual groups
        outRect.top = pixelPadding / 2
        outRect.bottom = pixelPadding / 2
    }

    @SuppressLint("RestrictedApi")
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return

        parent.children.forEach { view ->
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return@forEach

            // Check neighbors to determine shape
            // (Simplified logic: In real usage, you check if the prev/next item is a PreferenceCategory)
            val isFirst = position == 0 || isCategory(adapter, position - 1)
            val isLast = position == adapter.itemCount - 1 || isCategory(adapter, position + 1)

            val bgRes = when {
                isFirst && isLast -> R.drawable.bg_surface_single
                isFirst -> R.drawable.bg_surface_top
                isLast -> R.drawable.bg_surface_bottom
                else -> R.drawable.bg_surface_middle
            }

            // We only apply background if it's NOT a category header
            if (!isCategory(adapter, position)) {
                view.background = ContextCompat.getDrawable(view.context, bgRes)

                // Pixel style: Indent the content slightly
                view.setPadding(32, view.paddingTop, 32, view.paddingBottom)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun isCategory(adapter: PreferenceGroupAdapter, position: Int): Boolean {
        if (position < 0 || position >= adapter.itemCount) return true // Boundary counts as break
        val item = adapter.getItem(position)
        return item is androidx.preference.PreferenceCategory
    }
}