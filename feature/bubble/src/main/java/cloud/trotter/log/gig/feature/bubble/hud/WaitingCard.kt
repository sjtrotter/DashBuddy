package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun WaitingCard(model: StateCardModel.Waiting, earned: Double?) {
    StateLine(stringResource(R.string.hud_state_waiting), R.string.hud_state_since, model.since)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom) {
        // The clock is read only when there is a time to draw: an anchorless card stays static.
        val now = if (model.since != null || (earned != null && model.sessionStartedAt != null)) rememberNow().value else null
        StateFigure(StateHeadline.waiting(model.since, now ?: 0L))
        // #1443: Earned/hr is the hero's figure over the shift's online time.
        perHour(earned, if (now != null) model.sessionStartedAt?.let { now - it } else null)?.let {
            Column(horizontalAlignment = Alignment.End) {
                StateNumber(moneyOrEmpty(it), AppTheme.colors.text, size = 18)
                StateLabel(stringResource(R.string.hud_state_earned_hr))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateNumber(model.deliveries?.toString() ?: EMPTY_VALUE, AppTheme.colors.text, size = 16)
            StateLabel(pluralStringResource(R.plurals.hud_deliveries, model.deliveries ?: 0))
        }
        if (model.received == 0 && model.lastOffer == null) {
            StateLabel(stringResource(R.string.hud_state_no_offer))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StateNumber(if (model.accepted != null && model.received != null)
                    stringResource(R.string.hud_rest_accepted, model.accepted, model.received) else EMPTY_VALUE,
                    AppTheme.colors.text, size = 16)
                StateLabel(stringResource(R.string.hud_rest_accepted_label))
            }
        }
    }
    if (model.lastOffer != null) {
        HorizontalDivider(Modifier.padding(top = 12.dp), color = AppTheme.colors.line)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StateLabel(stringResource(R.string.hud_state_last_offer))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StateNumber(moneyOrEmpty(model.lastOffer.pay))
                StateLabel(stringResource(when (model.lastOffer.outcome) {
                    LastOfferOutcome.ACCEPTED -> R.string.hud_state_outcome_accepted
                    LastOfferOutcome.DECLINED -> R.string.hud_state_outcome_declined
                    LastOfferOutcome.EXPIRED -> R.string.hud_state_outcome_expired
                }))
            }
        }
    }
}
