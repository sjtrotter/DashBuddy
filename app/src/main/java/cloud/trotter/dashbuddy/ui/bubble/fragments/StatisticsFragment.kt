//package cloud.trotter.dashbuddy.ui.bubble.fragments
//
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.appcompat.app.AppCompatActivity
//import androidx.appcompat.content.res.AppCompatResources.getDrawable
//import androidx.fragment.app.Fragment
//import cloud.trotter.dashbuddy.DashBuddyApplication
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.databinding.FragmentStatisticsBinding
//import cloud.trotter.dashbuddy.ui.bubble.BubbleActivity
//
//class StatisticsFragment : Fragment() {
//
//    private var _binding: FragmentStatisticsBinding? = null
//    private val binding get() = _binding!!
//
//    override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View {
//        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        binding.statisticsTextView.text = "This is the Statistics tab."
//        setupMenu()
//    }
//
//    private fun setupMenu() {
//        (activity as? AppCompatActivity)?.supportActionBar?.title = "Tactics"
//
//        // Set the navigation icon for the toolbar
//        val toolbar =
//            (activity as? BubbleActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.bubble_toolbar)
//        toolbar?.navigationIcon =
//            getDrawable(DashBuddyApplication.context, R.drawable.ic_menu_toolbar_tactics)
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}