package cloud.trotter.dashbuddy.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashboardBinding
import cloud.trotter.dashbuddy.ui.bubble.BubbleService

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        setupListeners()
        return binding.root
    }

    private fun setupListeners() {
        binding.btnShowBubble.setOnClickListener {
            val intent = Intent(requireContext(), BubbleService::class.java).apply {
                putExtra(BubbleService.EXTRA_MESSAGE, "Welcome to DashBuddy!")
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }

        binding.btnSettings.setOnClickListener {
            // HERE is where we launch your new Settings screen
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }
}