package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.settings.capabilityCopy

/**
 * Prompted per-capability automation consent (#843) — one page of the front door. Every
 * *undecided* automation is its own row — name, plain-language behavioral disclosure, source, and an
 * individual Allow / Don't-allow choice. There is NO "allow all" button (Play policy: each
 * automation individually). Answers write THROUGH the grant store the fail-closed engine gate reads
 * (#417); this page is an acquisition surface, never a second gate. "Not now" (the door's own
 * button) defers the whole door for this foreground; a "Don't allow" persists a durable denial.
 *
 * Stateless since #1151 review LL1/MM6: [FrontDoorHost] renders it inside the one [FrontDoorSheet].
 */
@Composable
fun ConsentPromptPage(
    rows: List<ConsentPromptRow>,
    onDecision: (key: String, allow: Boolean) -> Unit,
) {
    FrontDoorPage(
        title = stringResource(R.string.consent_prompt_heading),
        body = stringResource(R.string.consent_prompt_body),
    ) {
        rows.forEach { row ->
            HorizontalDivider()
            ConsentPromptRowView(row = row, onDecision = onDecision)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ConsentPromptRowView(
    row: ConsentPromptRow,
    onDecision: (key: String, allow: Boolean) -> Unit,
) {
    val platformName = row.platform
        .takeIf { it != Platform.Unknown }
        ?.displayName
        ?: stringResource(SettingsR.string.consent_platform_unknown)

    val copy = capabilityCopy(row.action, platformName)
    val sourceLabel = if (row.isBundled) {
        stringResource(R.string.consent_prompt_source_bundled_format, platformName)
    } else {
        stringResource(R.string.consent_prompt_source_downloaded_format, platformName)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (row.layoutCount > 1) {
            Text(
                text = stringResource(SettingsR.string.consent_layout_count_format, row.layoutCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = copy.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onDecision(row.key, false) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.consent_prompt_deny)) }
            Button(
                onClick = { onDecision(row.key, true) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.consent_prompt_allow)) }
        }
    }
}
