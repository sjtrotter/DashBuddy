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
 * Prompted per-capability automation consent (#843). A modal sheet at the app's
 * front door listing every *undecided* automation as its own row — name,
 * plain-language behavioral disclosure, source, and an individual Allow /
 * Don't-allow choice. There is NO "allow all" button (Play policy: each
 * automation individually). "Not now" defers the whole sheet — undecided stays
 * undecided, re-prompt on the next real return to the foreground; a "Don't
 * allow" persists a durable denial that never re-prompts. Answers write THROUGH
 * the grant store the fail-closed engine gate reads (#417); this sheet is an
 * acquisition surface, never a second gate.
 *
 * Stateless since #1151 review LL1/LL3: it is rendered by [FrontDoorHost] (the
 * Dashboard's one front door, which also owns the deferral) on the shared
 * [FrontDoorSheet].
 */
@Composable
fun ConsentPromptSheet(
    rows: List<ConsentPromptRow>,
    onDecision: (key: String, allow: Boolean) -> Unit,
    onDefer: () -> Unit,
) {
    FrontDoorSheet(
        title = stringResource(R.string.consent_prompt_heading),
        body = stringResource(R.string.consent_prompt_body),
        notNowLabel = stringResource(R.string.consent_prompt_not_now),
        onDefer = onDefer,
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
