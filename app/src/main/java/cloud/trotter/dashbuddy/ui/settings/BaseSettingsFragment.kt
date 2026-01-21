package cloud.trotter.dashbuddy.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.settings.components.GroupedRectDecoration
import com.google.android.material.appbar.MaterialToolbar

/**
 * The Foundation.
 * 1. Handles the "Large Header" expansion.
 * 2. Applies the "Rounded Group" backgrounds automatically.
 */
abstract class BaseSettingsFragment : PreferenceFragmentCompat() {

    // Child classes must provide these
    abstract fun getTitle(): String
    abstract fun getDescription(): String // The "Verbose" text you wanted

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 1. Generate the standard preference list
        val prefView = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup

        // 2. Wrap it in our custom "Pixel Style" layout container
//        val context = requireContext()
        val root =
            inflater.inflate(R.layout.layout_settings_base, container, false) as CoordinatorLayout

        // 3. Inject the preference list into the scrolling container
        val contentFrame = root.findViewById<ViewGroup>(R.id.settings_content_frame)
        contentFrame.addView(prefView)

        // 4. Configure the Large Header
        root.findViewById<MaterialToolbar>(R.id.toolbar).title = getTitle()
        root.findViewById<TextView>(R.id.header_description).text = getDescription()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 5. Apply the "Grouped" styling decoration
        val list = listView // The RecyclerView from PreferenceFragment
        list.addItemDecoration(GroupedRectDecoration(pixelPadding = 16))

        // Remove default dividers (our background shapes handle separation)
        setDivider(null)
    }
}