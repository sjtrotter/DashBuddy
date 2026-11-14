package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.census.contract.contributor.QuarantineNote

/** #1627: the Ask the developer sheet over the review screen. Send passes the chosen note (or none) and closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskDeveloperSheet(onSend: (QuarantineNote?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AskDeveloperContent(onSend = onSend, onCancel = onDismiss)
    }
}

/**
 * #1627: what the sheet says and offers: what goes and who can open it, an optional note from the census server's
 * closed list ([QuarantineNote]; "No note" by default), Send and Cancel. Stateless apart from the note choice.
 */
@Composable
fun AskDeveloperContent(onSend: (QuarantineNote?) -> Unit, onCancel: () -> Unit) {
    var note by rememberSaveable { mutableStateOf<QuarantineNote?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .testTag(TAG_ASK_SHEET),
    ) {
        Text(
            stringResource(R.string.census_review_ask_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.census_review_ask_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.census_review_ask_note), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            NoteOption(null, note) { note = it }
            QuarantineNote.entries.forEach { option -> NoteOption(option, note) { note = it } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(TAG_ASK_CANCEL)) {
                Text(stringResource(R.string.census_review_ask_cancel))
            }
            Button(onClick = { onSend(note) }, modifier = Modifier.testTag(TAG_ASK_SEND)) {
                Text(stringResource(R.string.census_review_ask_send))
            }
        }
    }
}

@Composable
private fun NoteOption(option: QuarantineNote?, chosen: QuarantineNote?, onChoose: (QuarantineNote?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = option == chosen, role = Role.RadioButton, onClick = { onChoose(option) })
            .padding(vertical = 4.dp)
            .testTag(noteTag(option)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option == chosen, onClick = null)
        Text(stringResource(noteLabel(option)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun noteLabel(note: QuarantineNote?): Int = when (note) {
    null -> R.string.census_review_ask_note_none
    QuarantineNote.MIGHT_CONTAIN_CUSTOMER -> R.string.census_review_ask_note_customer
    QuarantineNote.MIGHT_CONTAIN_MY_INFO -> R.string.census_review_ask_note_mine
    QuarantineNote.UNSURE_WHAT_SCREEN -> R.string.census_review_ask_note_unsure
}

const val TAG_ASK_SHEET: String = "ask_developer_sheet"
const val TAG_ASK_SEND: String = "ask_developer_send"
const val TAG_ASK_CANCEL: String = "ask_developer_cancel"
const val TAG_ASK_BUTTON: String = "ask_developer_button"
fun noteTag(note: QuarantineNote?): String = "ask_developer_note:${note?.wire ?: "none"}"
