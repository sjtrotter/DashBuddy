package cloud.trotter.dashbuddy.ui.activities

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
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.ActivityMainBinding
import cloud.trotter.dashbuddy.pipeline.inputs.AccessibilityListener
import cloud.trotter.dashbuddy.services.bubble.BubbleService as BubbleService
import cloud.trotter.dashbuddy.log.Logger as Log

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tag = "MainActivity"

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Log.i(
                tag,
                if (isGranted) "POST_NOTIFICATIONS permission granted." else "POST_NOTIFICATIONS permission denied."
            )
            // onResume will handle the UI update.
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fineLocationGranted) {
                Log.i(tag, "ACCESS_FINE_LOCATION permission granted.")
            } else if (coarseLocationGranted) {
                Log.i(tag, "ACCESS_COARSE_LOCATION permission granted.")
            } else {
                Log.w(tag, "Location permission was denied.")
            }
            // onResume will handle the UI update.
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        Log.i(tag, "MainActivity created.")

        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        Log.d(tag, "Resuming MainActivity, checking all permission statuses.")
        checkAllPermissions()
    }

    private fun setupClickListeners() {
        binding.btnEnableAccessibility.setOnClickListener {
            Log.d(tag, "Accessibility button clicked. Opening settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnEnablePostNotifications.setOnClickListener {
            Log.d(tag, "Post Notifications button clicked.")
            requestPostNotificationsPermission()
        }

        binding.btnEnableLocation.setOnClickListener {
            Log.d(tag, "Location button clicked.")
            requestLocationPermissions()
        }

        binding.btnEnableNotificationListener.setOnClickListener {
            Log.d(tag, "Notification Listener button clicked. Opening settings.")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnShowBubble.setOnClickListener {
            Log.d(tag, "Show Bubble button clicked.")
            val intent = Intent(this, BubbleService::class.java).apply {
                putExtra(BubbleService.EXTRA_MESSAGE, "Welcome to DashBuddy!")
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun checkAllPermissions() {
        // Post Notifications Check
        val areNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        updateCardView(
            binding.cardPostNotification,
            binding.btnEnablePostNotifications,
            areNotificationsEnabled,
            "Post Notifications"
        )
        // Also update the visibility of the "Show Bubble" button
        binding.btnShowBubble.visibility = if (areNotificationsEnabled) View.VISIBLE else View.GONE


        // Accessibility Service Check
        val isAccessibilityEnabled =
            isAccessibilityServiceEnabled(this, AccessibilityListener::class.java)
        updateCardView(
            binding.cardAccessibility,
            binding.btnEnableAccessibility,
            isAccessibilityEnabled,
            "Accessibility"
        )

        // Location Check
        val isLocationEnabled = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        updateCardView(
            binding.cardLocation,
            binding.btnEnableLocation,
            isLocationEnabled,
            "Location"
        )

        // Notification Listener Service Check
        val isNotificationListenerEnabled = isNotificationServiceEnabled()
        updateCardView(
            binding.cardNotificationListener,
            binding.btnEnableNotificationListener,
            isNotificationListenerEnabled,
            "Notification Listener"
        )
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestLocationPermissions() {
        requestLocationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

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
            Log.d(tag, "$permissionName permission is GRANTED.")
        } else {
            card.setCardBackgroundColor(resolveColorAttr(com.google.android.material.R.attr.colorErrorContainer))
            button.text = getString(R.string.enable)
            button.isEnabled = true
            Log.d(tag, "$permissionName permission is DENIED.")
        }
    }

    @ColorInt
    private fun resolveColorAttr(@AttrRes colorAttr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(colorAttr, typedValue, true)
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
        val componentName =
            ComponentName(this, "cloud.trotter.dashbuddy.services.YourNotificationListenerService")
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }
}