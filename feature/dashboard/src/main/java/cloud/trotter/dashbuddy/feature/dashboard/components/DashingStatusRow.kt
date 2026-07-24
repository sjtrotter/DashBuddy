package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Slim online pointer (#657): while a dash is active the review surface just points
 * back to the bubble (the live glance the bubble owns) — it does not mirror it. Tapping
 * re-shows the bubble via the existing Show-Bubble action.
 */
@Composable
fun DashingStatusRow(onTap: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onTap)) {
        Text(
            text = "🟢 Session active — tap for the bubble",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
    }
}
