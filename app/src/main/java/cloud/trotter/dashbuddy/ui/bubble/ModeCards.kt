package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration

@Composable
internal fun ModeIdle(lastSessionSummary: SessionSummary?) {
    if (lastSessionSummary != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.bubble_mode_idle_last_session_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = Formats.money(lastSessionSummary.earnings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            ModeRow(
                label = stringResource(R.string.bubble_mode_idle_miles_label),
                value = "${Formats.decimal(lastSessionSummary.miles)} mi",
            )
            ModeRow(
                label = stringResource(R.string.bubble_mode_idle_duration_label),
                value = formatDuration(lastSessionSummary.endedAt - lastSessionSummary.startedAt),
            )
        }
    } else {
        ModeRow(
            label = stringResource(R.string.bubble_mode_idle_status_label),
            value = stringResource(R.string.bubble_mode_idle_offline_value),
        )
    }
}
