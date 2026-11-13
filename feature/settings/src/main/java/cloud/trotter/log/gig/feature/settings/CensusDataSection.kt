package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.format.formatMonthDayYear
import java.time.LocalDate

/**
 * #1622: the census screen's data rows: "What gig.log sent", and "Delete my census data" with its confirmation and the
 * stored outcome. Deleting is separate from the switch: it leaves the census on or off as it is.
 */
@Composable
internal fun CensusDataSection(
    deletion: CensusDeletion?,
    deleting: Boolean,
    onOpenSent: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.census_data_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.census_sent_title)) },
        supportingContent = { Text(stringResource(R.string.census_sent_entry_supporting)) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpenSent),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.census_delete_label)) },
        supportingContent = { Text(deletionStatus(deletion, deleting)) },
        trailingContent = if (deleting) {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else null,
        modifier = Modifier.clickable(enabled = !deleting) { confirm = true },
    )
    if (confirm) {
        CensusDeleteDialog(
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

/** What the last request came to, from the stored record; before any, what the row does. */
@Composable
private fun deletionStatus(deletion: CensusDeletion?, deleting: Boolean): String = when {
    deleting -> stringResource(R.string.census_delete_status_running)
    deletion == null -> stringResource(R.string.census_delete_supporting)
    else -> when (deletion.state) {
        CensusDeletion.State.PENDING -> stringResource(
            when (deletion.waiting) {
                CensusDeletion.Waiting.NOT_YET -> R.string.census_delete_status_not_yet
                CensusDeletion.Waiting.UNCONFIRMED -> R.string.census_delete_status_unconfirmed
                CensusDeletion.Waiting.UNREACHABLE, null -> R.string.census_delete_status_pending
            },
        )
        CensusDeletion.State.DELETED -> {
            val rows = deletion.rows ?: 0
            val day = deletion.deletedDay?.let { runCatching { formatMonthDayYear(LocalDate.parse(it)) }.getOrNull() }
            if (day == null) pluralStringResource(R.plurals.census_delete_status_done_no_day, rows, rows)
            else pluralStringResource(R.plurals.census_delete_status_done, rows, rows, day)
        }
        CensusDeletion.State.NOTHING_HELD -> stringResource(R.string.census_delete_status_nothing)
        CensusDeletion.State.NOT_POSSIBLE -> stringResource(R.string.census_delete_status_not_possible)
    }
}

/** The confirmation: what is deleted, what the server cannot tie to this phone, and the backups. */
@Composable
fun CensusDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.census_delete_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.census_delete_dialog_deleted))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.census_delete_dialog_survives))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.census_delete_dialog_backups))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.census_delete_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.census_delete_dialog_cancel)) }
        },
    )
}
