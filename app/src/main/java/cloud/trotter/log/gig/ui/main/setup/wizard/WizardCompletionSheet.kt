package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveFailure

/** What the setup wizard's completion sheet reports (#1473): a finished save's outcome, or a skip to confirm. */
internal sealed interface CompletionOutcome {
    data object Saved : CompletionOutcome
    data class Failed(val reason: SaveFailure) : CompletionOutcome
    data object Skipped : CompletionOutcome
}

/**
 * The sheet after Save setup or Skip setup (#1473). It opens only on an outcome, so "saved" shows only once the write
 * has finished. Saved: Done finishes. Failed: Retry runs the save again and Back to setup returns to the last step.
 * Skipped: Done skips. [onDismiss] is the swipe or system Back; the host decides what it means for each outcome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WizardCompletionSheet(
    outcome: CompletionOutcome,
    sheetState: SheetState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onBackToSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (outcome == CompletionOutcome.Saved) {
                SavedMark()
                Spacer(modifier = Modifier.height(24.dp))
            }
            Text(
                stringResource(outcome.titleRes()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(outcome.bodyRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            val failed = outcome is CompletionOutcome.Failed
            Button(
                onClick = if (failed) onRetry else onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(if (failed) R.string.wizard_screen_retry else R.string.wizard_screen_lets_go),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (failed) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onBackToSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wizard_screen_back_to_setup))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SavedMark() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = stringResource(R.string.wizard_screen_content_desc_success),
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun CompletionOutcome.titleRes(): Int = when (this) {
    CompletionOutcome.Saved -> R.string.wizard_screen_saved_title
    CompletionOutcome.Skipped -> R.string.wizard_screen_skipped_title
    is CompletionOutcome.Failed -> when (reason) {
        SaveFailure.VEHICLE -> R.string.wizard_screen_vehicle_failed_title
        SaveFailure.SETTINGS -> R.string.wizard_screen_save_failed_title
    }
}

private fun CompletionOutcome.bodyRes(): Int = when (this) {
    CompletionOutcome.Saved -> R.string.wizard_screen_completed_desc
    CompletionOutcome.Skipped -> R.string.wizard_screen_skipped_desc
    is CompletionOutcome.Failed -> when (reason) {
        SaveFailure.VEHICLE -> R.string.wizard_screen_vehicle_failed_desc
        SaveFailure.SETTINGS -> R.string.wizard_screen_save_failed_desc
    }
}
