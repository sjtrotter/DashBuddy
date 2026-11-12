package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.Milestone
import cloud.trotter.log.gig.domain.analytics.timeline.MilestoneKind
import cloud.trotter.log.gig.domain.format.formatClockTime

private const val SEPARATOR = " · "

/**
 * A job's story: the stores and day as the title, one line per recorded milestone with its time on the right
 * (a milestone not recorded is absent), and one "Fix this delivery" under each delivery, opening that delivery's
 * correction; under a delivery a receipt match still covers (#1599), "Undo match" ([undoMatchFor] names the match).
 * No estimates and no offered-against-paid difference. Back closes the sheet before the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftJobSheet(
    card: JobCard,
    day: String,
    onFix: (deliveryEventSequenceId: Long) -> Unit,
    onDismiss: () -> Unit,
    undoMatchFor: (deliveryEventSequenceId: Long) -> String? = { null },
    onUndoMatch: (matchCommandId: String) -> Unit = {},
) {
    val c = AppTheme.colors
    val manyStores = card.milestones.mapNotNull { it.store }.distinct().size > 1
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            Text(storesText(card.stores), style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(day, style = MaterialTheme.typography.bodyMedium, color = c.text2)
            Column(Modifier.padding(vertical = 12.dp)) {
                card.milestones.forEach { milestone ->
                    MilestoneRow(milestoneText(milestone, manyStores), formatClockTime(milestone.at))
                    val delivery = milestone.delivery
                    if (milestone.kind == MilestoneKind.DELIVERED && delivery != null) {
                        TextButton(onClick = { onFix(delivery.deliveryEventSequenceId) }) {
                            Text(stringResource(R.string.shift_page_fix_delivery))
                        }
                        undoMatchFor(delivery.deliveryEventSequenceId)?.let { command ->
                            TextButton(onClick = { onUndoMatch(command) }) {
                                Text(stringResource(R.string.shift_page_undo_match))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneRow(text: String, time: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text)
        Text(time, Modifier.padding(start = 12.dp), style = AppTheme.num.smNum, color = AppTheme.colors.text2)
    }
}

/** "Accepted · Offered $9.75 · 3.1 mi", "At store", "Delivered · Receipt · $11.25"; a store only when the job has several. */
@Composable
private fun milestoneText(milestone: Milestone, manyStores: Boolean): String {
    val store = milestone.store?.takeIf { manyStores && it.isNotBlank() }
    val parts: List<String?> = when (milestone.kind) {
        MilestoneKind.ACCEPTED_OFFERED -> listOf(
            stringResource(R.string.shift_page_milestone_accepted),
            milestone.offer?.let { offerText(it) } ?: milestone.addOn?.let { addOnText(it) },
        )
        MilestoneKind.AT_STORE -> listOf(stringResource(R.string.shift_page_milestone_at_store), store)
        MilestoneKind.PICKED_UP -> listOf(stringResource(R.string.shift_page_milestone_picked_up), store)
        MilestoneKind.AT_CUSTOMER -> listOf(stringResource(R.string.shift_page_milestone_at_customer))
        MilestoneKind.DELIVERED -> {
            val d = milestone.delivery
            listOf(
                stringResource(R.string.shift_page_milestone_delivered),
                d?.let { sourceText(it.source) },
                d?.let { line -> line.pay?.let { moneyText(it, line.estimated) } ?: EMPTY_VALUE },
            )
        }
    }
    return parts.filterNotNull().joinToString(SEPARATOR)
}
