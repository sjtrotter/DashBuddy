package cloud.trotter.dashbuddy.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentSetupBinding
import cloud.trotter.dashbuddy.pipeline.inputs.AccessibilityListener
import cloud.trotter.dashbuddy.pipeline.inputs.NotificationListener
import cloud.trotter.dashbuddy.log.Logger as Log

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val tag = "SetupFragment"

    // Permission Launchers
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Log.i(
                tag,
                if (isGranted) "POST_NOTIFICATIONS granted." else "POST_NOTIFICATIONS denied."
            )
            checkPermissionsAndNavigate() // Re-check immediately
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            checkPermissionsAndNavigate() // Re-check immediately
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

    /**
     * Checks all permissions.
     * If ALL are granted, navigates to the Dashboard.
     * If ANY are missing, stays here and updates the cards.
     */
    private fun checkPermissionsAndNavigate() {
        val allGranted = updateUiState()
        if (allGranted) {
            Log.i(tag, "All permissions granted. Navigating to Dashboard.")
            findNavController().navigate(R.id.action_setup_to_dashboard)
        }
    }

    private fun updateUiState(): Boolean {
        var allGood = true

        // 1. Post Notifications
        val areNotificationsEnabled =
            NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        updateCardView(
            binding.cardPostNotification,
            binding.btnEnablePostNotifications,
            areNotificationsEnabled,
            "Post Notifications"
        )
        if (!areNotificationsEnabled) allGood = false

        // 2. Accessibility
        val isAccessibilityEnabled =
            isAccessibilityServiceEnabled(requireContext(), AccessibilityListener::class.java)
        updateCardView(
            binding.cardAccessibility,
            binding.btnEnableAccessibility,
            isAccessibilityEnabled,
            "Accessibility"
        )
        if (!isAccessibilityEnabled) allGood = false

        // 3. Location
        val isLocationEnabled = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        updateCardView(
            binding.cardLocation,
            binding.btnEnableLocation,
            isLocationEnabled,
            "Location"
        )
        if (!isLocationEnabled) allGood = false

        // 4. Notification Listener
        val isNotificationListenerEnabled = isNotificationServiceEnabled()
        updateCardView(
            binding.cardNotificationListener,
            binding.btnEnableNotificationListener,
            isNotificationListenerEnabled,
            "Notification Listener"
        )
        if (!isNotificationListenerEnabled) allGood = false

        return allGood
    }

    private fun setupClickListeners() {
        binding.btnEnablePostNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // For older Androids, send them to app settings
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

    // --- Helper Methods ---

    private fun updateCardView(
        card: com.google.android.material.card.MaterialCardView,
        button: com.google.android.material.button.MaterialButton,
        isEnabled: Boolean,
        permissionName: String
    ) {
        if (isEnabled) {
            card.setCardBackgroundColor(resolveColorAttr(com.google.android.material.R.attr.colorSurfaceContainer))
            button.text = getString(R.string.enabled)
            button.isEnabled = false
        } else {
            card.setCardBackgroundColor(resolveColorAttr(com.google.android.material.R.attr.colorErrorContainer))
            button.text = getString(R.string.enable)
            button.isEnabled = true
        }
    }

    @ColorInt
    private fun resolveColorAttr(@AttrRes colorAttr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(colorAttr, typedValue, true)
        return typedValue.data
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponentName = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponentName != null && enabledComponentName == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val componentName = ComponentName(requireContext(), NotificationListener::class.java)
        val flat = Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(componentName.flattenToString()) == true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}