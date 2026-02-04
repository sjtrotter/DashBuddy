package cloud.trotter.dashbuddy.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    val config by viewModel.evidenceConfig.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evidence Locker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {

            // Re-using the SwitchRow component you already have in StrategySettingsScreen.kt
            // You might want to move SwitchRow to a shared 'Components.kt' file.

            SwitchRow(
                label = "Master Record",
                subtitle = "Global kill-switch for all data recording",
                checked = config.masterEnabled,
                onCheckedChange = { viewModel.setEvidenceMaster(it) }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Granular Controls", modifier = Modifier.padding(16.dp))

            SwitchRow(
                label = "Save Offers",
                subtitle = "Keep full JSON/Screenshots of incoming offers",
                checked = config.saveOffers,
                onCheckedChange = {
                    viewModel.updateEvidenceConfig(
                        it,
                        config.saveDeliverySummaries,
                        config.saveDashSummaries
                    )
                }
            )

            SwitchRow(
                label = "Save Deliveries",
                subtitle = "Track completion stats",
                checked = config.saveDeliverySummaries,
                onCheckedChange = {
                    viewModel.updateEvidenceConfig(config.saveOffers, it, config.saveDashSummaries)
                }
            )

            SwitchRow(
                label = "Save Dash Summary",
                subtitle = "End of dash earnings report",
                checked = config.saveDashSummaries,
                onCheckedChange = {
                    viewModel.updateEvidenceConfig(
                        config.saveOffers,
                        config.saveDeliverySummaries,
                        it
                    )
                }
            )
        }
    }
}