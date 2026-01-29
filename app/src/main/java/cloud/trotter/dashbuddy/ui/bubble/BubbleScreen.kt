package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DashBuddy HUD") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        // Content Area
        Box(modifier = Modifier.padding(innerPadding)) {
            Text(
                text = "Waiting for Dash events...",
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}