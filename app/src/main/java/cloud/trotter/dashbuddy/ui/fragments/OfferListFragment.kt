package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
//import androidx.compose.ui.layout.layout
//import androidx.compose.ui.semantics.text
import androidx.fragment.app.Fragment
import cloud.trotter.dashbuddy.R

class OfferListFragment : Fragment() {

    private var _binding: View? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        _binding = inflater.inflate(R.layout.fragment_offer_list, container, false)
        return binding
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Now the view is created, so we can find the TextView
        val offerListTextView = binding.findViewById<TextView>(R.id.offer_list_text_view)
        offerListTextView.text = "This is the Offer List tab."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}