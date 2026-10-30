package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun TaskCard(model: StateCardModel.TaskCard) {
    val now by rememberNow()
    val leg = model.leg
    val parked = leg.kind == LegKind.PICKUP_ARRIVED || leg.kind == LegKind.DROPOFF_ARRIVED
    val pickup = leg.kind == LegKind.PICKUP_NAV || leg.kind == LegKind.PICKUP_ARRIVED
    val late = StateHeadline.lateMinutes(leg.deadlineAt, now)
    val headline = if (parked) StateHeadline.parked(leg.anchorAt, leg.deadlineAt, now)
        else StateHeadline.moving(leg.anchorAt, leg.deadlineAt, now)
    val label = when {
        !parked && leg.deadlineAt == null -> R.string.hud_state_en_route
        !parked -> if (late != null) R.string.hud_state_min_en_route else R.string.hud_state_min_left
        pickup -> if (late != null) R.string.hud_state_min_at_store else R.string.hud_state_at_store
        else -> if (late != null) R.string.hud_state_min_at_door else R.string.hud_state_at_door
    }
    StateLine(model.facts.store, R.string.hud_state_due.takeIf { leg.deadlineAt != null }, leg.deadlineAt)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom) {
        StateFigure(headline, label, big = parked)
        if (LocalHudTier.current < FontScaleTier.NO_JOB_COLUMN) JobColumn(model.facts)
    }
    TimeTrack(TrackMapper.map(leg, now), late,
        if (pickup) AppTheme.colors.stPickup else AppTheme.colors.stDropoff)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateLabel(stringResource(when {
                parked -> R.string.hud_state_arrived
                pickup -> R.string.hud_state_accepted_at
                else -> R.string.hud_state_picked_up
            }))
            StateNumber(rememberTimeFormatter()(leg.anchorAt))
        }
        late?.let { LateFoot(it) }
    }
}
