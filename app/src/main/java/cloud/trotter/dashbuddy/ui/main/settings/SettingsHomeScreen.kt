package cloud.trotter.dashbuddy.ui.main.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.domain.format.Formats
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsHomeScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    // Relying strictly on the persisted DataStore state now
    val isDevUnlocked by viewModel.isDevModeUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val userEconomy by viewModel.userEconomy.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_home_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Section 0: Monitoring
            SettingsGroup(title = stringResource(R.string.settings_home_group_monitoring)) {
                SettingsNavItem(
                    icon = Icons.Default.Apps,
                    title = stringResource(R.string.settings_home_item_gig_apps_title),
                    subtitle = stringResource(R.string.settings_home_item_gig_apps_subtitle),
                    onClick = { onNavigate(Screen.PlatformSettings.route) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: The Brain
            SettingsGroup(title = stringResource(R.string.settings_home_group_automation)) {
                SettingsNavItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.settings_home_item_strategy_title),
                    subtitle = stringResource(R.string.settings_home_item_strategy_subtitle),
                    onClick = { onNavigate(Screen.StrategySettings.route) }
                )
                val eco = userEconomy
                val ecoSubtitle = if (eco != null) {
                    val costPerMi = eco.operatingCostPerMile
                    val defaultsCount = cloud.trotter.dashbuddy.domain.evaluation.EconomyField.entries.size -
                        eco.userSetFields.size
                    stringResource(R.string.settings_home_economy_true_cost_format, Formats.money(costPerMi)) +
                        if (defaultsCount > 0) stringResource(R.string.settings_home_economy_defaults_suffix_format, defaultsCount) else ""
                } else {
                    stringResource(R.string.settings_home_economy_fallback_subtitle)
                }
                SettingsNavItem(
                    icon = Icons.Default.AttachMoney,
                    title = stringResource(R.string.settings_home_item_economy_title),
                    subtitle = ecoSubtitle,
                    onClick = { onNavigate(Screen.EconomySettings.route) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Data
            SettingsGroup(title = stringResource(R.string.settings_home_group_data_privacy)) {
                SettingsNavItem(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.settings_home_item_evidence_title),
                    subtitle = stringResource(R.string.settings_home_item_evidence_subtitle),
                    onClick = { onNavigate(Screen.EvidenceSettings.route) }
                )
                SettingsNavItem(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.settings_home_item_export_title),
                    subtitle = stringResource(R.string.settings_home_item_export_subtitle),
                    onClick = { onNavigate(Screen.DataExport.route) }
                )
                SettingsNavItem(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.settings_home_item_consent_title),
                    subtitle = stringResource(R.string.settings_home_item_consent_subtitle),
                    onClick = { onNavigate(Screen.ConsentSettings.route) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: General
            SettingsGroup(title = stringResource(R.string.settings_home_group_app_system)) {
                SettingsNavItem(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.settings_home_item_general_title),
                    subtitle = stringResource(R.string.settings_home_item_general_subtitle),
                    onClick = { onNavigate(Screen.GeneralSettings.route) }
                )

                SettingsNavItem(
                    icon = Icons.Default.RocketLaunch,
                    title = stringResource(R.string.settings_home_item_rerun_wizard_title),
                    subtitle = stringResource(R.string.settings_home_item_rerun_wizard_subtitle),
                    onClick = { onNavigate(Screen.Wizard.route) }
                )

                SettingsNavItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_home_item_about_title),
                    subtitle = stringResource(R.string.settings_home_item_about_subtitle),
                    onClick = { onNavigate(Screen.AboutSettings.route) }
                )
            }

            // Section 4: Developer (Conditional)
            AnimatedVisibility(
                visible = isDevUnlocked,
                enter = expandVertically() + fadeIn()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsGroup(title = stringResource(R.string.main_activity_developer_options_title)) {
                        SettingsNavItem(
                            icon = Icons.Default.BugReport,
                            title = stringResource(R.string.settings_home_item_debug_menu_title),
                            subtitle = stringResource(R.string.settings_home_item_debug_menu_subtitle),
                            onClick = { onNavigate(Screen.DeveloperSettings.route) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}