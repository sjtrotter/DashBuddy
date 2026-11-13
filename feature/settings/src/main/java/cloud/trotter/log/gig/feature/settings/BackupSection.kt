package cloud.trotter.log.gig.feature.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.data.backup.RestoreOutcome
import cloud.trotter.log.gig.core.data.backup.RestoreRefusal
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatMonthDayYear
import cloud.trotter.log.gig.feature.settings.BackupViewModel.BackupState
import cloud.trotter.log.gig.feature.settings.BackupViewModel.RestoreState
import java.time.Instant
import java.time.ZoneId

/** The full backup section of the Export data screen (#1615): back up to a file, restore from one. */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestore.collectAsStateWithLifecycle()
    val clock = LocalNowProvider.current
    val context = LocalContext.current

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) viewModel.backUp(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restore(uri)
    }

    val ready = backup != BackupState.InProgress && (restore == RestoreState.Idle || restore is RestoreState.Refused)

    LaunchedEffect(restore) {
        if (restore == RestoreState.Restarting) restartApp(context)
    }

    Column {
        Text(stringResource(R.string.backup_heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastRestore?.let { outcome ->
            Spacer(Modifier.height(8.dp))
            RestoreOutcomeLine(outcome)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { createFile.launch(BackupViewModel.suggestedFileName(clock.millis())) },
                enabled = ready,
            ) { Text(stringResource(R.string.backup_button)) }
            OutlinedButton(
                onClick = { openFile.launch(arrayOf(ZIP_MIME, "application/octet-stream")) },
                enabled = ready,
            ) { Text(stringResource(R.string.restore_button)) }
        }
        Spacer(Modifier.height(16.dp))
        when (val s = backup) {
            BackupState.InProgress -> CircularProgressIndicator()
            is BackupState.Done -> Text(
                stringResource(R.string.backup_done_format, s.eventCount),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
            is BackupState.Failed -> Text(
                stringResource(R.string.backup_failed_format, s.errorClass),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            BackupState.Idle -> Unit
        }
        when (val s = restore) {
            RestoreState.Checking, RestoreState.Restarting -> CircularProgressIndicator()
            is RestoreState.Refused -> Text(
                stringResource(refusalText(s.reason)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is RestoreState.Confirm -> ConfirmRestoreDialog(
                made = backupDate(s.createdAtEpochMs),
                appVersion = s.appVersion,
                onConfirm = viewModel::confirmRestore,
                onCancel = viewModel::cancelRestore,
            )
            RestoreState.Idle -> Unit
        }
    }
}

@Composable
private fun RestoreOutcomeLine(outcome: RestoreOutcome) {
    val text = when (outcome) {
        is RestoreOutcome.Restored -> outcome.createdAtEpochMs
            ?.let { stringResource(R.string.restore_result_ok_format, backupDate(it)) }
            ?: stringResource(R.string.restore_result_ok)
        RestoreOutcome.Failed -> stringResource(R.string.restore_result_failed)
    }
    Text(
        text,
        color = if (outcome is RestoreOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ConfirmRestoreDialog(made: String, appVersion: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = { Text(stringResource(R.string.restore_confirm_body_format, made, appVersion)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm_replace)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.restore_confirm_cancel)) } },
    )
}

private fun refusalText(reason: RestoreRefusal): Int = when (reason) {
    RestoreRefusal.NOT_A_BACKUP -> R.string.restore_refused_not_a_backup
    RestoreRefusal.NEWER_APP -> R.string.restore_refused_newer_app
    RestoreRefusal.NO_SPACE -> R.string.restore_refused_no_space
    RestoreRefusal.READ_FAILED -> R.string.restore_refused_read_failed
}

/** "Nov 12, 2026 5:42 PM": when the backup was made, in the device zone. */
private fun backupDate(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    return "${formatMonthDayYear(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())} ${formatClockTime(epochMs, zone)}"
}

/** Restarts the process so `RestoreSwap` runs before anything opens the files. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    launch?.component?.let { context.startActivity(Intent.makeRestartActivityTask(it)) }
    Runtime.getRuntime().exit(0)
}

private const val ZIP_MIME = "application/zip"
