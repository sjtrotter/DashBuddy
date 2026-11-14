package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.ContributorSlots
import cloud.trotter.census.contract.contributor.QuarantineNote
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.contributor.AskOutcome
import cloud.trotter.log.gig.core.data.census.contributor.AskRefusal
import cloud.trotter.log.gig.core.data.census.contributor.ClassesState
import cloud.trotter.log.gig.core.data.census.contributor.ReviewRow
import cloud.trotter.log.gig.core.data.census.contributor.SendOutcome
import cloud.trotter.log.gig.core.data.census.contributor.SlotValue

/**
 * #1626: one screen under review, opened from Census > Screen reviews (and its notification after a shift). The rows
 * are the outbound skeleton, in the census server's slot order: each one says what leaves the phone. Customer and
 * personal data the phone can detect is covered before this screen exists; a constant slot shows its word, which the
 * dasher may confirm as the app's own wording; any row may get a label. Send, Delete, and (#1627) Ask the developer,
 * which opens [AskDeveloperSheet] and sends the same outline to the developer's quarantine.
 */
@Composable
fun ContributorReviewScreen(onBack: () -> Unit, viewModel: ContributorReviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ContributorReviewContent(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggleConfirmed,
        onRole = viewModel::setRole,
        onSend = viewModel::send,
        onDelete = viewModel::delete,
        onAskDeveloper = viewModel::ask,
    )
}

/**
 * Stateless apart from whether the Ask the developer sheet is open (Compose tests and screenshots render it).
 * [onAskDeveloper] null shows no Ask the developer button. The button and its sheet show only while the review is
 * ready, which needs a standing grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorReviewContent(
    state: ReviewUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onRole: (String, ContributorRole?) -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    onAskDeveloper: ((QuarantineNote?) -> Unit)? = null,
) {
    val reviewing = !state.shiftLive &&
        (state.phase == ReviewPhase.READY || state.phase == ReviewPhase.SENDING || state.phase == ReviewPhase.ASKING)
    var asking by rememberSaveable { mutableStateOf(false) }
    if (asking && onAskDeveloper != null && reviewing && state.canAsk) {
        AskDeveloperSheet(onSend = { note -> asking = false; onAskDeveloper(note) }, onDismiss = { asking = false })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.census_review_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
            )
        },
        bottomBar = {
            if (reviewing) ReviewButtons(state, onSend, onDelete, onAskDeveloper?.let { { asking = true } })
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val message = phaseMessage(state)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp).testTag(TAG_MESSAGE))
                return@Box
            }
            val review = state.review ?: return@Box
            LazyColumn(Modifier.fillMaxSize().testTag(TAG_ROWS)) {
                item { ReviewHeader(state) }
                items(review.rows, key = { it.slot }) { row ->
                    ReviewRowItem(
                        row = row,
                        confirmed = row.slot in state.confirmed,
                        role = state.roles[row.slot],
                        enabled = state.phase == ReviewPhase.READY,
                        onToggle = { onToggle(row.slot) },
                        onRole = { onRole(row.slot, it) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun phaseMessage(state: ReviewUiState): String? = when {
    state.phase == ReviewPhase.SENT -> stringResource(R.string.census_review_screen_sent)
    state.phase == ReviewPhase.ASKED -> when (val asked = state.asked) {
        is AskOutcome.Sent -> stringResource(R.string.census_review_screen_asked, endDay(asked.expiresDay))
        is AskOutcome.Unconfirmed -> stringResource(R.string.census_review_screen_asked_unconfirmed, endDay(asked.expiresDay))
        AskOutcome.ReceiptUnreadable -> stringResource(R.string.census_review_screen_asked_unread)
        AskOutcome.MaybeSent, is AskOutcome.NotSent, null -> stringResource(R.string.census_review_screen_asked_unknown)
    }
    state.phase == ReviewPhase.DELETED -> stringResource(R.string.census_review_screen_deleted)
    state.phase == ReviewPhase.NOT_ALLOWED -> stringResource(R.string.census_review_screen_not_allowed)
    state.phase == ReviewPhase.NOT_FOUND -> stringResource(R.string.census_review_screen_gone)
    state.phase == ReviewPhase.NOT_READY -> stringResource(R.string.census_review_screen_tomorrow)
    state.shiftLive -> stringResource(R.string.census_review_screen_shift)
    state.phase == ReviewPhase.LOADING -> stringResource(R.string.census_review_screen_loading)
    else -> null
}

@Composable
private fun ReviewHeader(state: ReviewUiState) {
    val review = state.review ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.census_review_screen_intro), style = MaterialTheme.typography.bodyMedium)
        val notes = buildList {
            if (!review.wordsKept) add(R.string.census_review_screen_words_lost)
            when (review.classes) {
                ClassesState.RELEASE_UNAVAILABLE -> add(R.string.census_review_screen_release_unavailable)
                ClassesState.UNREACHABLE -> add(R.string.census_review_screen_unreachable)
                ClassesState.LOADED -> Unit
            }
            when (state.failure) {
                SendOutcome.LATER -> add(R.string.census_review_screen_later)
                SendOutcome.UNREACHABLE -> add(R.string.census_review_screen_send_unreachable)
                else -> Unit
            }
            askFailureNote(state.askFailure)?.let(::add)
        }
        notes.forEach {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    confirmed: Boolean,
    role: ContributorRole?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRole: (ContributorRole?) -> Unit,
) {
    val greyed = row.word == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(rowTag(row.slot))) {
        Text(
            primaryText(row),
            style = MaterialTheme.typography.bodyLarge,
            color = if (greyed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            whereText(row),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.confirmable) {
                Row(
                    Modifier
                        .toggleable(value = confirmed, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                        .testTag(confirmTag(row.slot)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = null, enabled = enabled)
                    Text(stringResource(R.string.census_review_screen_confirm), style = MaterialTheme.typography.bodyMedium)
                }
            }
            RoleChooser(row.slot, role, enabled, onRole)
        }
    }
}

@Composable
private fun RoleChooser(slot: String, role: ContributorRole?, enabled: Boolean, onRole: (ContributorRole?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(roleTag(slot))) {
            Text(
                role?.let { stringResource(R.string.census_review_screen_role_set, stringResource(roleLabel(it))) }
                    ?: stringResource(R.string.census_review_screen_role_add),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ContributorRole.entries.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(roleLabel(option))) }, onClick = { open = false; onRole(option) })
            }
            if (role != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.census_review_screen_role_none)) },
                    onClick = { open = false; onRole(null) },
                )
            }
        }
    }
}

@Composable
private fun ReviewButtons(state: ReviewUiState, onSend: () -> Unit, onDelete: () -> Unit, onAskDeveloper: (() -> Unit)?) {
    val idle = state.phase == ReviewPhase.READY
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDelete, enabled = idle) { Text(stringResource(R.string.census_review_screen_delete)) }
            if (onAskDeveloper != null) {
                TextButton(onClick = onAskDeveloper, enabled = state.canAsk, modifier = Modifier.testTag(TAG_ASK_BUTTON)) {
                    Text(
                        stringResource(
                            if (state.phase == ReviewPhase.ASKING) R.string.census_review_screen_sending else R.string.census_review_screen_ask,
                        ),
                    )
                }
            }
            Button(onClick = onSend, enabled = state.canSend) {
                Text(
                    stringResource(
                        if (state.phase == ReviewPhase.SENDING) R.string.census_review_screen_sending else R.string.census_review_screen_send,
                    ),
                )
            }
        }
    }
}

/** #1627: the line an Ask the developer that sent nothing leaves on the screen. */
private fun askFailureNote(reason: AskRefusal?): Int? = when (reason) {
    AskRefusal.LATER -> R.string.census_review_ask_later
    AskRefusal.UNREACHABLE -> R.string.census_review_ask_unreachable
    AskRefusal.REFUSED -> R.string.census_review_ask_refused
    AskRefusal.UNCONFIGURED -> R.string.census_review_ask_unconfigured
    AskRefusal.NOT_ALLOWED, AskRefusal.NOT_FOUND, AskRefusal.INSTALL_REVOKED, null -> null
}

/** What the row shows: the word on a constant slot, otherwise what is sent in place of the text. */
@Composable
private fun primaryText(row: ReviewRow): String {
    row.word?.let { return stringResource(R.string.census_review_screen_word, it) }
    return when (val value = row.value) {
        SlotValue.Covered -> stringResource(R.string.census_review_screen_covered)
        is SlotValue.Format -> stringResource(R.string.census_review_screen_format, value.template)
        is SlotValue.Kind -> stringResource(
            when (value.kind) {
                KindClassifier.DIGITS -> R.string.census_review_screen_number
                KindClassifier.WORDS_OVER -> R.string.census_review_screen_long
                else -> R.string.census_review_screen_mixed
            },
        )
        SlotValue.Hashed -> stringResource(
            when (row.slotClass) {
                SlotClass.CONSTANT -> R.string.census_review_screen_constant_no_word
                SlotClass.VARYING -> R.string.census_review_screen_varying
                SlotClass.REGIONAL -> R.string.census_review_screen_regional
                SlotClass.WITHHELD, SlotClass.SHAPE -> R.string.census_review_screen_covered
                SlotClass.UNKNOWN -> R.string.census_review_screen_unknown
            },
        )
    }
}

/** Where the slot sits: the view's class name and which of its texts. */
@Composable
private fun whereText(row: ReviewRow): String {
    val field = stringResource(keyLabel(row.key))
    val view = row.className?.substringAfterLast('.')
    return if (view == null) field else stringResource(R.string.census_review_screen_where, view, field)
}

private fun keyLabel(key: String): Int = when (key) {
    ContributorSlots.WINDOW_TITLE -> R.string.census_review_key_window_title
    "text" -> R.string.census_review_key_text
    "desc" -> R.string.census_review_key_desc
    "hint" -> R.string.census_review_key_hint
    "state" -> R.string.census_review_key_state
    "pane" -> R.string.census_review_key_pane
    "tooltip" -> R.string.census_review_key_tooltip
    "error" -> R.string.census_review_key_error
    "clickLabel" -> R.string.census_review_key_click_label
    else -> R.string.census_review_key_other
}

internal fun roleLabel(role: ContributorRole): Int = when (role) {
    ContributorRole.TOTAL_PAY -> R.string.census_review_role_total_pay
    ContributorRole.TIP -> R.string.census_review_role_tip
    ContributorRole.BASE_PAY -> R.string.census_review_role_base_pay
    ContributorRole.DEADLINE -> R.string.census_review_role_deadline
    ContributorRole.DISTANCE -> R.string.census_review_role_distance
    ContributorRole.ITEM_COUNT -> R.string.census_review_role_item_count
    ContributorRole.CUSTOMER_INFO -> R.string.census_review_role_customer_info
    ContributorRole.STORE_NAME -> R.string.census_review_role_store_name
    ContributorRole.OTHER -> R.string.census_review_role_other
}

internal const val TAG_ROWS = "review_rows"
internal const val TAG_MESSAGE = "review_message"
fun rowTag(slot: String): String = "review_row:$slot"
fun confirmTag(slot: String): String = "review_confirm:$slot"
fun roleTag(slot: String): String = "review_role:$slot"
