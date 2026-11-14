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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.data.wipe.WipeFlow
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.State

/** The dialog bound to its ViewModel; Settings home hosts it under its Delete everything on this phone row. */
@Composable
fun WipePhoneHost(viewModel: WipePhoneViewModel) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    WipePhoneDialog(
        dialog,
        WipeActions(viewModel::confirm, viewModel::cancel, viewModel::deleteNow, viewModel::waitForServer),
    )
}

/** The dasher's choices, one per [WipeFlow.Event] they send. */
data class WipeActions(
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onDeleteNow: () -> Unit,
    val onWait: () -> Unit,
) {
    companion object {
        val NONE = WipeActions({}, {}, {}, {})
    }
}

/**
 * #1579: the dialogs of Delete everything on this phone, stateless. The confirmation says what goes and what stays,
 * and whenever the census server could be asked, what it keeps. While a census upload finishes, or while the phone's
 * census ID is read, it offers only Cancel; while the census server is asked and while the phone is cleared it has no
 * buttons and Back does not close it; an unconfirmed census request offers Delete now or Wait.
 */
@Composable
fun WipePhoneDialog(dialog: WipeDialog, actions: WipeActions) {
    when (val state = dialog.state) {
        null, State.Left, State.Releasing -> Unit
        State.Confirming -> ConfirmDialog(dialog.censusDisclosed, actions)
        State.WaitingForLock -> CancellableBusy(R.string.wipe_waiting_title, R.string.wipe_waiting_body, actions.onCancel)
        State.ReadingEnrollment ->
            CancellableBusy(R.string.wipe_confirm_title, R.string.wipe_reading_body, actions.onCancel)
        State.Asking -> BusyDialog(R.string.wipe_asking_title, R.string.wipe_asking_body, lead = null)
        is State.Choosing -> NotConfirmedDialog(state.reason, actions)
        is State.Committing -> BusyDialog(R.string.wipe_running_title, R.string.wipe_running_body, lead = null)
        is State.Wiping -> BusyDialog(
            R.string.wipe_running_title,
            R.string.wipe_running_body,
            lead = R.string.wipe_census_confirmed.takeIf { state.censusConfirmed },
        )
        State.Done -> BusyDialog(R.string.wipe_running_title, R.string.wipe_running_body, lead = null)
        is State.Failed -> AlertDialog(
            // While the request to delete can't be cleared, only the button (a retry) closes it.
            onDismissRequest = { if (!state.commitmentStuck) actions.onCancel() },
            title = { Text(stringResource(R.string.wipe_failed_title)) },
            text = {
                Paragraphs(
                    listOfNotNull(
                        when (state.failure) {
                            WipeFlow.Failure.COMMIT -> R.string.wipe_failed_commit_body
                            WipeFlow.Failure.CLEAR -> R.string.wipe_failed_body
                        },
                        R.string.wipe_failed_drop.takeIf { state.commitmentStuck },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onCancel) {
                    Text(stringResource(if (state.commitmentStuck) R.string.wipe_try_again else R.string.wipe_ok))
                }
            },
        )
    }
}

@Composable
private fun ConfirmDialog(census: Boolean, actions: WipeActions) {
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text(stringResource(R.string.wipe_confirm_title)) },
        text = {
            Paragraphs(
                listOfNotNull(
                    R.string.wipe_confirm_goes,
                    R.string.wipe_confirm_census.takeIf { census },
                    // The server's exceptions, as the census screen's own Delete my census data states them (#1622).
                    R.string.census_delete_dialog_survives.takeIf { census },
                    R.string.census_delete_dialog_backups.takeIf { census },
                    R.string.wipe_confirm_stays,
                    R.string.wipe_confirm_after,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm) {
                Text(stringResource(R.string.wipe_confirm_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.wipe_cancel)) } },
    )
}

@Composable
private fun NotConfirmedDialog(reason: WipeFlow.Reason, actions: WipeActions) {
    val leave = if (reason.canWait) actions.onWait else actions.onCancel
    AlertDialog(
        onDismissRequest = leave,
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
            TextButton(onClick = actions.onDeleteNow) {
                Text(stringResource(R.string.wipe_census_delete_now), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = leave) {
                Text(stringResource(if (reason.canWait) R.string.wipe_census_wait_button else R.string.wipe_cancel))
            }
        },
    )
}

/** A step that finishes on its own but can be cancelled; Back and a tap outside do not cancel it. */
@Composable
private fun CancellableBusy(@StringRes title: Int, @StringRes body: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(title)) },
        text = { Busy(body) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.wipe_cancel)) } },
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
                Busy(body)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun Busy(@StringRes body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(body))
    }
}

@Composable
private fun Paragraphs(lines: List<Int>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEach { Text(stringResource(it)) }
    }
}

@StringRes
private fun WipeFlow.Reason.text(): Int = when (this) {
    WipeFlow.Reason.NO_ANSWER -> R.string.wipe_census_no_answer
    WipeFlow.Reason.UNREACHABLE -> R.string.wipe_census_unreachable
    WipeFlow.Reason.NOT_YET -> R.string.wipe_census_not_yet
    WipeFlow.Reason.UNCONFIRMED -> R.string.wipe_census_unconfirmed
    WipeFlow.Reason.NOT_POSSIBLE -> R.string.wipe_census_not_possible
}
