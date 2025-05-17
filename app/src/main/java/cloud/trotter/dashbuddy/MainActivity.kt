package cloud.trotter.dashbuddy

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.bubble.Service as BubbleService

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
                // Permission is granted. You can now safely start your service
                // or expect notifications to work.
                (applicationContext as? DashBuddyApplication)?.startBubbleService()
            } else {
                Log.w("MainActivity", "POST_NOTIFICATIONS permission denied.")
                // Explain to the user why the feature is unavailable without the permission
                // or direct them to settings.
            }
        }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
                (applicationContext as? DashBuddyApplication)?.startBubbleService()
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Show an educational UI to the user explaining why you need the permission
                // Then, request the permission again. For simplicity here, just requesting:
                Log.d(
                    "MainActivity",
                    "Showing rationale and requesting POST_NOTIFICATIONS permission."
                )
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Directly ask for the permission
                Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Below API 33, the permission is granted by default at install time
            Log.d("MainActivity", "Below API 33, no runtime permission needed for notifications.")
            (applicationContext as? DashBuddyApplication)?.startBubbleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContentView(R.layout.activity_main)
    }
}