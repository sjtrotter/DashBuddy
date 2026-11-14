package cloud.trotter.log.gig.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cloud.trotter.log.gig.core.data.wipe.WipeProgress

/** The dialog bound to its ViewModel; Settings home hosts it under its Delete everything on this phone row. */
@Composable
fun WipePhoneHost(viewModel: WipePhoneViewModel) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    WipePhoneDialog(dialog, viewModel::confirm, viewModel::wipeNow, viewModel::dismiss)
}

/**
 * #1579: the dialogs of Delete everything on this phone, stateless. The confirmation says what goes and what stays;
 * while the census server is asked and while the phone is cleared the dialog has no buttons and Back does not close
 * it; an unconfirmed census request offers Delete now or Wait.
 */
@Composable
fun WipePhoneDialog(
    dialog: WipeDialog,
    onConfirm: () -> Unit,
    onWipeNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        WipeDialog.Closed -> Unit
        is WipeDialog.Confirm -> ConfirmDialog(dialog.census, onConfirm, onDismiss)
        is WipeDialog.Running -> when (val progress = dialog.progress) {
            WipeProgress.Idle -> Unit
            WipeProgress.AskingCensus -> BusyDialog(R.string.wipe_asking_title, R.string.wipe_asking_body, lead = null)
            is WipeProgress.Wiping -> BusyDialog(
                R.string.wipe_running_title,
                R.string.wipe_running_body,
                lead = R.string.wipe_census_confirmed.takeIf { progress.censusConfirmed },
            )
            is WipeProgress.CensusNotConfirmed -> NotConfirmedDialog(progress.reason, onWipeNow, onDismiss)
            WipeProgress.Failed -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.wipe_failed_title)) },
                text = { Text(stringResource(R.string.wipe_failed_body)) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wipe_ok)) } },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(census: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipe_confirm_title)) },
        text = {
            Paragraphs(
                listOfNotNull(
                    R.string.wipe_confirm_goes,
                    R.string.wipe_confirm_census.takeIf { census },
                    R.string.wipe_confirm_stays,
                    R.string.wipe_confirm_after,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.wipe_confirm_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wipe_cancel)) } },
    )
}

@Composable
private fun NotConfirmedDialog(reason: WipeProgress.Reason, onWipeNow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipe_census_title)) },
        text = {
            Paragraphs(
                listOfNotNull(
                    reason.text(),
                    R.string.wipe_census_now,
                    R.string.wipe_census_wait.takeIf { reason.canWait },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onWipeNow) {
                Text(stringResource(R.string.wipe_census_delete_now), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (reason.canWait) R.string.wipe_census_wait_button else R.string.wipe_cancel))
            }
        },
    )
}

/** No buttons, and neither Back nor a tap outside closes it: the step finishes on its own. */
@Composable
private fun BusyDialog(@StringRes title: Int, @StringRes body: Int, @StringRes lead: Int?) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lead != null) Text(stringResource(lead))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(body))
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun Paragraphs(lines: List<Int>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEach { Text(stringResource(it)) }
    }
}

@StringRes
private fun WipeProgress.Reason.text(): Int = when (this) {
    WipeProgress.Reason.NO_ANSWER -> R.string.wipe_census_no_answer
    WipeProgress.Reason.UNREACHABLE -> R.string.wipe_census_unreachable
    WipeProgress.Reason.NOT_YET -> R.string.wipe_census_not_yet
    WipeProgress.Reason.UNCONFIRMED -> R.string.wipe_census_unconfirmed
    WipeProgress.Reason.NOT_POSSIBLE -> R.string.wipe_census_not_possible
}
