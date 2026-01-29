package cloud.trotter.dashbuddy.ui.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cloud.trotter.dashbuddy.ui.theme.DashBuddyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No more XML, no more Fragments, no more ViewBinding.
        setContent {
            DashBuddyTheme {
                BubbleScreen()
            }
        }
    }
}