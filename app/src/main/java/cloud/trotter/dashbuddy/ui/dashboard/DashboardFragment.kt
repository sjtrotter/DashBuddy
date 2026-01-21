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
import cloud.trotter.dashbuddy.util.PermissionUtils
import cloud.trotter.dashbuddy.log.Logger as Log

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val tag = "DashboardFragment"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        setupListeners()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Check permissions every time we show the dashboard
        if (!PermissionUtils.hasAllEssentialPermissions(requireContext())) {
            Log.w(tag, "Permissions missing (likely crash reset). Redirecting to Setup.")
            findNavController().navigate(R.id.action_global_setup)
        }
    }

    private fun setupListeners() {
        binding.btnShowBubble.setOnClickListener {
            // Permission check before starting service
            if (PermissionUtils.hasAllEssentialPermissions(requireContext())) {
                val intent = Intent(requireContext(), BubbleService::class.java).apply {
                    putExtra(BubbleService.EXTRA_MESSAGE, "Welcome to DashBuddy!")
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            } else {
                findNavController().navigate(R.id.action_global_setup)
            }
        }

        binding.btnSettings.setOnClickListener {
            // Correct: Using Navigation Component
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}