package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewQueue
import cloud.trotter.log.gig.core.data.census.contributor.GrantRequestOutcome
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.census.contributor.ContributorEligibility
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.ContributorGrant
import cloud.trotter.log.gig.domain.census.contributor.ContributorInvitation
import cloud.trotter.log.gig.domain.census.contributor.GrantRefusal
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.domain.format.formatMonthDayYear
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1625: Settings > Data & privacy > Census > Screen reviews, and where the invitation notification opens. The second
 * disclosure (what reviewing means and what is sent) with Accept, Not now and No thanks; with a live grant, its end
 * day and Stop reviewing. Not now and No thanks close the screen.
 */
@Composable
fun ContributorScreen(
    onBack: () -> Unit,
    onOpenReview: (String) -> Unit = {},
    viewModel: ContributorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ContributorContent(
        state = state,
        onBack = onBack,
        onOpenReview = onOpenReview,
        onAccept = viewModel::accept,
        onNotNow = { viewModel.notNow(); onBack() },
        onNoThanks = { viewModel.noThanks(); onBack() },
        onStop = viewModel::stop,
    )
}

/** The disclosure's sections in order: heading, body. */
private val REVIEW_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.census_review_see_title to R.string.census_review_see_body,
    R.string.census_review_do_title to R.string.census_review_do_body,
    R.string.census_review_sent_title to R.string.census_review_sent_body,
    R.string.census_review_grant_title to R.string.census_review_grant_body,
    R.string.census_review_stop_title to R.string.census_review_stop_body,
)

/** The reviewer screen, stateless (Compose tests and screenshots render it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorContent(
    state: ContributorUiState?,
    onBack: () -> Unit,
    onAccept: () -> Unit,
    onNotNow: () -> Unit,
    onNoThanks: () -> Unit,
    onStop: () -> Unit,
    onOpenReview: (String) -> Unit = {},
) {
    // A grant stops working at the start of its expiry day (UTC): the screen says so at that instant, whatever the
    // data layer has caught up to (Reactive UI rule 2: the state holds the anchor, the screen derives the value).
    val now by rememberNow()
    val shown = state?.let { atNow(it, now) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.census_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
        bottomBar = { if (shown != null) ContributorButtons(shown, onAccept, onNotNow, onNoThanks, onStop) },
    ) { padding ->
        if (shown == null) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.census_review_heading),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            statusLines(shown).forEach { line ->
                Spacer(Modifier.height(8.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (shown.entry == ContributorEntry.ON) WaitingReviews(shown.waiting, now, onOpenReview)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.census_review_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.census_review_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            REVIEW_SECTIONS.forEach { (title, body) ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.census_disclosure_agreement), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * #1626: the screens waiting for review. A screen is ready the UTC day after the server stored it (its slot classes
 * come from the next day's release), derived here from the clock; at most [ContributorReviewQueue.MAX_PER_DAY] ready
 * screens are offered at once.
 */
@Composable
private fun WaitingReviews(waiting: List<WaitingReview>, nowMillis: Long, onOpen: (String) -> Unit) {
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val (ready, later) = waiting.partition { review ->
        review.uploadedDay?.let { runCatching { LocalDate.parse(it) < today }.getOrNull() } == true
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.census_review_waiting_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (waiting.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.census_review_waiting_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    ready.take(ContributorReviewQueue.MAX_PER_DAY).forEach { review ->
        ListItem(
            headlineContent = { Text(stringResource(R.string.census_review_waiting_row, reviewDay(review.day))) },
            supportingContent = { Text(stringResource(R.string.census_review_waiting_ready)) },
            modifier = Modifier.clickable { onOpen(review.id) },
        )
    }
    later.forEach { review ->
        ListItem(
            headlineContent = { Text(stringResource(R.string.census_review_waiting_row, reviewDay(review.day))) },
            supportingContent = {
                Text(
                    stringResource(
                        if (review.uploadedDay == null) R.string.census_review_waiting_upload else R.string.census_review_waiting_tomorrow,
                    ),
                )
            },
        )
    }
}

/** A skeleton's UTC day as the dasher reads dates; the raw day if it cannot be read. */
private fun reviewDay(day: String): String = runCatching { formatMonthDayYear(LocalDate.parse(day)) }.getOrDefault(day)

/**
 * With a live grant, Stop reviewing. Otherwise, while Accept is open: No thanks, Not now and Accept, or Accept alone
 * after a No thanks. While it is not open, No thanks stays, so the dasher can turn off invitations at any time.
 */
@Composable
private fun ContributorButtons(
    state: ContributorUiState,
    onAccept: () -> Unit,
    onNotNow: () -> Unit,
    onNoThanks: () -> Unit,
    onStop: () -> Unit,
) {
    val declined = state.answer == InvitationAnswer.NO_THANKS
    if (!state.canAccept && state.entry != ContributorEntry.ON && declined) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            when {
                state.entry == ContributorEntry.ON ->
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.census_review_stop)) }
                state.canAccept -> {
                    if (!declined) {
                        TextButton(onClick = onNoThanks, enabled = !state.requesting) {
                            Text(stringResource(R.string.census_review_no_thanks))
                        }
                        TextButton(onClick = onNotNow, enabled = !state.requesting) {
                            Text(stringResource(R.string.consent_front_door_not_now))
                        }
                    }
                    Button(onClick = onAccept, enabled = !state.requesting) {
                        Text(stringResource(R.string.census_review_accept))
                    }
                }
                else -> TextButton(onClick = onNoThanks) { Text(stringResource(R.string.census_review_no_thanks)) }
            }
        }
    }
}

/** What the screen says about the dasher's reviews, most recent fact first. */
@Composable
private fun statusLines(state: ContributorUiState): List<String> {
    val lines = mutableListOf<String>()
    when {
        state.entry == ContributorEntry.ON -> lines += stringResource(R.string.census_review_status_on, endDay(state.expiresDay))
        state.requesting -> lines += stringResource(R.string.census_review_status_requesting)
        else -> {
            outcomeLine(state.outcome)?.let { lines += stringResource(it) }
            recordLine(state)?.let { lines += it }
            if (!state.canAccept && state.answer != InvitationAnswer.NO_THANKS) {
                lines += if (state.shiftLive) {
                    stringResource(R.string.census_review_status_shift)
                } else {
                    stringResource(
                        R.string.census_review_status_not_open,
                        ContributorEligibility.MIN_ENROLLED_UTC_DAYS,
                        ContributorEligibility.MIN_ENDED_SHIFTS,
                        ContributorEligibility.MIN_ACTIVE_DAYS,
                        ContributorEligibility.ACTIVE_WINDOW_DAYS,
                    )
                }
            }
        }
    }
    return lines.distinct()
}

/** [state] at [nowMillis]: a grant past the start of its expiry day reads as ended, before the record says so. */
internal fun atNow(state: ContributorUiState, nowMillis: Long): ContributorUiState {
    val endsAt = ContributorGrant.expiryMillis(state.expiresDay)
    if (state.entry != ContributorEntry.ON || (endsAt != null && nowMillis < endsAt)) return state
    return state.copy(entry = ContributorEntry.OFF, canAccept = false, expiresDay = null, ended = ContributorEnd.EXPIRED)
}

/** The outcomes the record does not keep: the server unreachable, a deletion waiting, no census ID yet. */
private fun outcomeLine(outcome: GrantRequestOutcome?): Int? = when (outcome) {
    GrantRequestOutcome.UNREACHABLE -> R.string.census_review_status_unreachable
    GrantRequestOutcome.DELETION_PENDING -> R.string.census_review_status_deletion_pending
    GrantRequestOutcome.NO_CENSUS_ID -> R.string.census_review_status_no_id
    else -> null
}

@Composable
private fun recordLine(state: ContributorUiState): String? = when {
    state.refusal == GrantRefusal.GRANT_REVOKED -> stringResource(R.string.census_review_status_revoked)
    state.refusal == GrantRefusal.INSTALL_TOO_NEW -> stringResource(R.string.census_review_status_too_new)
    state.refusal == GrantRefusal.NOT_ELIGIBLE -> stringResource(R.string.census_review_status_not_eligible)
    state.answer == InvitationAnswer.NO_THANKS -> stringResource(R.string.census_review_status_off)
    state.answer == InvitationAnswer.NOT_NOW -> pluralStringResource(
        R.plurals.census_review_status_not_now, ContributorInvitation.NOT_NOW_DAYS, ContributorInvitation.NOT_NOW_DAYS,
    )
    else -> when (state.ended) {
        ContributorEnd.EXPIRED -> stringResource(R.string.census_review_status_expired)
        ContributorEnd.DATA_DELETED -> stringResource(R.string.census_review_status_deleted)
        ContributorEnd.CENSUS_OFF -> stringResource(R.string.census_review_status_census_off)
        ContributorEnd.INSTALL_REVOKED -> stringResource(R.string.census_review_status_install_revoked)
        ContributorEnd.ID_CHANGED -> stringResource(R.string.census_review_status_id_changed)
        ContributorEnd.SERVER_NONE -> stringResource(R.string.census_review_status_server_none)
        ContributorEnd.REVOKED -> stringResource(R.string.census_review_status_revoked)
        ContributorEnd.STOPPED -> stringResource(R.string.census_review_status_off)
        null -> null
    }
}

/** The grant's end day as the dasher reads dates; the raw day if it cannot be read. */
internal fun endDay(expiresDay: String?): String =
    expiresDay?.let { runCatching { formatMonthDayYear(LocalDate.parse(it)) }.getOrDefault(it) }.orEmpty()
