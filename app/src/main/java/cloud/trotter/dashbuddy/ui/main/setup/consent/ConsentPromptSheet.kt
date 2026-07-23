package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.main.settings.capabilityCopy

/**
 * Prompted per-capability automation consent (#843). A modal sheet at the app's
 * front door listing every *undecided* automation as its own row — name,
 * plain-language behavioral disclosure, source, and an individual Allow /
 * Don't-allow choice. There is NO "allow all" button (Play policy: each
 * automation individually). "Not now" defers the whole sheet — undecided stays
 * undecided, re-prompt next foreground; a "Don't allow" persists a durable
 * denial that never re-prompts. Answers write THROUGH the grant store the
 * fail-closed engine gate reads (#417); this sheet is an acquisition surface,
 * never a second gate.
 *
 * Rendered by the dashboard (the start destination) once essential permissions
 * are satisfied — the same app-foreground rhythm the permission sheet uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentPromptSheet(
    viewModel: ConsentPromptViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // "Not now" defers for THIS foreground only; ON_RESUME re-arms so the sheet
    // returns on the next app-foreground while anything stays undecided.
    // rememberSaveable, not remember: a rotation / process-death / nav-return
    // within the same foreground must NOT re-show the modal the user just
    // deferred — that is the exact fatigue the deferral exists to prevent (F1).
    var deferred by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deferred = false }

    // Trigger predicate: any undecided capability AND the user hasn't deferred.
    if (uiState.rows.isEmpty() || deferred) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        // Scrim/back = "Not now" (defer), consistent with the per-row button.
        onDismissRequest = { deferred = true },
        sheetState = sheetState,
        modifier = Modifier.padding(bottom = bottomPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.consent_prompt_heading),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.consent_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            uiState.rows.forEach { row ->
                HorizontalDivider()
                ConsentPromptRowView(
                    row = row,
                    onDecision = viewModel::onDecision,
                )
            }
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { deferred = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.consent_prompt_not_now)) }
            Spacer(Modifier.height(24.dp))
        }
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
        ?: stringResource(R.string.consent_platform_unknown)

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
