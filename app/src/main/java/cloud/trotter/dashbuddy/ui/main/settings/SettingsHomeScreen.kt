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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.ui.main.navigation.Screen

@Composable
fun SettingsHomeScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    // Relying strictly on the persisted DataStore state now
    val isDevUnlocked by viewModel.isDevModeUnlocked.collectAsState(initial = false)

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
                        "Settings",
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

            // Section 1: The Brain
            SettingsGroup(title = "Automation & Intelligence") {
                SettingsNavItem(
                    icon = Icons.Default.Tune,
                    title = "Strategy Config",
                    subtitle = "Rules, Pricing, and Simulation",
                    onClick = { onNavigate(Screen.StrategySettings.route) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Data
            SettingsGroup(title = "Data & Privacy") {
                SettingsNavItem(
                    icon = Icons.Default.Folder,
                    title = "Evidence Locker",
                    subtitle = "Manage screenshots and logs",
                    onClick = { onNavigate(Screen.EvidenceSettings.route) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: General
            SettingsGroup(title = "App System") {
                SettingsNavItem(
                    icon = Icons.Default.Settings,
                    title = "General",
                    subtitle = "Theme, Pro Mode, and Defaults",
                    onClick = { onNavigate(Screen.GeneralSettings.route) }
                )

                SettingsNavItem(
                    icon = Icons.Default.RocketLaunch,
                    title = "Re-run Setup Wizard",
                    subtitle = "Adjust vehicle and base targets",
                    onClick = { onNavigate(Screen.Wizard.route) }
                )

                SettingsNavItem(
                    icon = Icons.Default.Info,
                    title = "About",
                    subtitle = "Version, Support, and Developer info",
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
                    SettingsGroup(title = "Developer Options") {
                        SettingsNavItem(
                            icon = Icons.Default.BugReport,
                            title = "Debug Menu",
                            subtitle = "Log levels & Snapshot whitelist",
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