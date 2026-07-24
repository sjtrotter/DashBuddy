package cloud.trotter.dashbuddy.feature.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.feature.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    val config by viewModel.evidenceConfig.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.evidence_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {

            // Re-using the SwitchRow component you already have in StrategySettingsScreen.kt
            // You might want to move SwitchRow to a shared 'Components.kt' file.

            SwitchRow(
                label = stringResource(R.string.evidence_settings_master_label),
                subtitle = stringResource(R.string.evidence_settings_master_subtitle),
                checked = config.masterEnabled,
                onCheckedChange = { viewModel.setEvidenceMaster(it) }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.evidence_settings_section_granular), modifier = Modifier.padding(16.dp))

            SwitchRow(
                label = stringResource(R.string.evidence_settings_offers_label),
                subtitle = stringResource(R.string.evidence_settings_offers_subtitle),
                checked = config.saveOffers,
                onCheckedChange = {
                    viewModel.updateEvidenceConfig(
                        it,
                        config.saveDeliverySummaries,
                        config.saveSessionSummaries
                    )
                }
            )

            SwitchRow(
                label = stringResource(R.string.evidence_settings_deliveries_label),
                subtitle = stringResource(R.string.evidence_settings_deliveries_subtitle),
                checked = config.saveDeliverySummaries,
                onCheckedChange = {
                    viewModel.updateEvidenceConfig(config.saveOffers, it, config.saveSessionSummaries)
                }
            )

            SwitchRow(
                label = stringResource(R.string.evidence_settings_session_label),
                subtitle = stringResource(R.string.evidence_settings_session_subtitle),
                checked = config.saveSessionSummaries,
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