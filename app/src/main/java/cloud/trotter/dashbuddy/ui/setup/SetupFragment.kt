package cloud.trotter.dashbuddy.ui.setup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentSetupBinding
import cloud.trotter.dashbuddy.util.PermissionUtils
import cloud.trotter.dashbuddy.log.Logger as Log
import com.google.android.material.R as MaterialR

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val tag = "SetupFragment"

    // --- Launchers ---
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            checkPermissionsAndNavigate()
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            checkPermissionsAndNavigate()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        setupClickListeners()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndNavigate()
    }

    private fun checkPermissionsAndNavigate() {
        val allGranted = updateUiState()
        if (allGranted) {
            Log.i(tag, "All permissions granted. Navigating to Dashboard.")
            findNavController().navigate(R.id.action_setup_to_dashboard)
        }
    }

    private fun updateUiState(): Boolean {
        var allGood = true
        val context = requireContext()

        // 1. Post Notifications
        val notifGranted = PermissionUtils.hasPostNotificationsPermission(context)
        updateCardView(
            binding.cardPostNotification,
            binding.btnEnablePostNotifications,
            notifGranted
        )
        if (!notifGranted) allGood = false

        // 2. Accessibility
        val accGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
        updateCardView(binding.cardAccessibility, binding.btnEnableAccessibility, accGranted)
        if (!accGranted) allGood = false

        // 3. Location
        val locGranted = PermissionUtils.hasLocationPermission(context)
        updateCardView(binding.cardLocation, binding.btnEnableLocation, locGranted)
        if (!locGranted) allGood = false

        // 4. Notification Listener
        val listenerGranted = PermissionUtils.isNotificationListenerEnabled(context)
        updateCardView(
            binding.cardNotificationListener,
            binding.btnEnableNotificationListener,
            listenerGranted
        )
        if (!listenerGranted) allGood = false

        return allGood
    }

    private fun updateCardView(
        card: com.google.android.material.card.MaterialCardView,
        button: com.google.android.material.button.MaterialButton,
        isEnabled: Boolean
    ) {
        if (isEnabled) {
            // FIX: Use MaterialR alias here
            card.setCardBackgroundColor(resolveColorAttr(MaterialR.attr.colorSurfaceContainer))
            button.text = getString(R.string.enabled)
            button.isEnabled = false
        } else {
            // FIX: Use MaterialR alias here
            card.setCardBackgroundColor(resolveColorAttr(MaterialR.attr.colorErrorContainer))
            button.text = getString(R.string.enable)
            button.isEnabled = true
        }
    }

    private fun setupClickListeners() {
        binding.btnEnablePostNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                startActivity(intent)
            }
        }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnEnableLocation.setOnClickListener {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        binding.btnEnableNotificationListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    @ColorInt
    private fun resolveColorAttr(@AttrRes colorAttr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(colorAttr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}