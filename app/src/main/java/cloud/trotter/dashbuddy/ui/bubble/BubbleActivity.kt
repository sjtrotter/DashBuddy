package cloud.trotter.dashbuddy.ui.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag("Bubble").i("activity %s isFinishing=%s", "create", isFinishing)

        setContent {
            // Shared instance with BubbleScreen's default hiltViewModel() (both scope to this
            // Activity's ViewModelStore) — one collection point for the glance multiplier so the
            // HUD stays reactive to the Settings toggle without a restart (#318). The main app
            // window (MainActivity) never passes `glance`, so it stays at the 1.0 default.
            val viewModel: BubbleViewModel = hiltViewModel()
            val glance by viewModel.glanceMultiplier.collectAsStateWithLifecycle()
            DashBuddyTheme(glance = glance) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BubbleScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.tag("Bubble").i("activity %s isFinishing=%s", "start", isFinishing)
    }

    override fun onStop() {
        super.onStop()
        Timber.tag("Bubble").i("activity %s isFinishing=%s", "stop", isFinishing)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Bubble").i("activity %s isFinishing=%s", "destroy", isFinishing)
    }
}
