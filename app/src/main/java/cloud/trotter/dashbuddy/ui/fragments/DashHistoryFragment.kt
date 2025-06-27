package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryBinding

class DashHistoryFragment : Fragment() {

    private var _binding: FragmentDashHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.offerListTextView.text = "This is the Offer List tab."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
