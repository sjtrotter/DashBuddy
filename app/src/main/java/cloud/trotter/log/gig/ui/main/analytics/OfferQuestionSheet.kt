package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.timeline.OpenOffer
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime

private const val SEPARATOR = " · "

/** The sheet's "Add a missed delivery", whose label the page's own correction button shares. */
internal const val OFFER_SHEET_ADD_TAG = "offer_sheet_add_missed"

/**
 * "What happened to this offer?": an accepted offer with no delivery, its store, time and offered pay, then the two
 * answers. "It was unassigned" records the dasher's statement; "Add a missed delivery" opens the add dialog
 * prefilled from the offer. Close, or Back, leaves the offer open. Data in, lambdas out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfferQuestionSheet(
    offer: OpenOffer,
    onUnassigned: () -> Unit,
    onAddMissed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.shift_offer_sheet_title), style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(
                listOf(
                    formatClockTime(offer.decidedAt),
                    storesText(offer.stores),
                    stringResource(R.string.shift_page_offered_format, offer.pay?.let { Formats.money(it) } ?: EMPTY_VALUE),
                ).joinToString(SEPARATOR),
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text2,
            )
            TextButton(onClick = onUnassigned) { Text(stringResource(R.string.shift_offer_sheet_unassigned)) }
            TextButton(onClick = onAddMissed, modifier = Modifier.testTag(OFFER_SHEET_ADD_TAG)) {
                Text(stringResource(R.string.session_detail_add_missed_delivery))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.shift_offer_sheet_close))
            }
        }
    }
}
