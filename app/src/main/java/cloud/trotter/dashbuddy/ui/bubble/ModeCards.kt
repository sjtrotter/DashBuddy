package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.state.Platform

/** $0.05 per tap on the gas quick-edit stepper. */
private const val GAS_STEP = 0.05f

/**
 * The bubble's post-dash idle card (#693): a frozen summary of the last dash straight off the
 * analytics read-model ([SessionRecord]), plus between-dash "just-in-time" actions (gas quick-edit,
 * vehicle deep-link) shown while offline. Only the "ended Xm ago" caption ticks (Reactive-UI rule 2:
 * anchor `endedAt`, derive live) — the summary numbers are immutable facts of a completed session.
 *
 * @param focusedPlatform the platform the HUD is currently showing; the session's own platform chip
 *   is rendered only when it differs (a cross-platform last dash, P8).
 */
@Composable
internal fun ModeIdle(
    session: SessionRecord?,
    focusedPlatform: Platform?,
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    isGasPriceRefreshing: Boolean,
    showGasPriceRefreshError: Boolean,
    onSetGasPrice: (Float) -> Unit,
    onRefreshGasPrice: () -> Unit,
    onResumeAutoGasPrice: () -> Unit,
    onOpenVehicleSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session != null) {
            LastSessionSummary(session = session, focusedPlatform = focusedPlatform)
        } else {
            Text(
                text = stringResource(R.string.bubble_mode_idle_no_session),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        JustInTimeActions(
            gasPrice = gasPrice,
            isGasPriceAuto = isGasPriceAuto,
            isGasPriceRefreshing = isGasPriceRefreshing,
            showGasPriceRefreshError = showGasPriceRefreshError,
            onSetGasPrice = onSetGasPrice,
            onRefreshGasPrice = onRefreshGasPrice,
            onResumeAutoGasPrice = onResumeAutoGasPrice,
            onOpenVehicleSettings = onOpenVehicleSettings,
        )
    }
}

@Composable
private fun LastSessionSummary(session: SessionRecord, focusedPlatform: Platform?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.bubble_mode_idle_last_session_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            // Platform chip only when the last dash was on a platform other than the one in focus (P8).
            if (session.platform != Platform.Unknown && session.platform != focusedPlatform) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = session.platform.shortName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }

        Text(
            text = Formats.money(session.reportedEarnings ?: 0.0),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        session.miles?.let { miles ->
            ModeRow(
                label = stringResource(R.string.bubble_mode_idle_miles_label),
                value = "${Formats.decimal(miles)} mi",
            )
        }

        session.endedAt?.let { endedAt ->
            ModeRow(
                label = stringResource(R.string.bubble_mode_idle_duration_label),
                value = formatDuration(endedAt - session.startedAt),
            )
        }

        if (session.offersReceived > 0) {
            val pct = session.offersAccepted * 100 / session.offersReceived
            ModeRow(
                label = stringResource(R.string.bubble_mode_idle_acceptance_label),
                value = "$pct%",
            )
        }

        ModeRow(
            label = stringResource(R.string.bubble_mode_idle_deliveries_label),
            value = session.deliveries.toString(),
        )

        // The one live value: how long ago the dash ended (anchor endedAt + 1-Hz ticker).
        session.endedAt?.let { endedAt ->
            val now by rememberNow()
            Text(
                text = stringResource(
                    R.string.bubble_mode_idle_ended_ago,
                    formatDuration((now - endedAt).coerceAtLeast(0L)),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * #728: the old single-Row layout crammed both just-in-time actions together at tiny (28dp)
 * touch targets — "looks bad and is a nightmare to try to operate" per field feedback. This is
 * the bubble idle card, a glance surface operated around driving (Reactive UI rule 5, the
 * strictest operability bar in the app), so each action gets its own full-width [AppCard] with
 * generous internal padding instead of sharing a cramped Row.
 */
@Composable
private fun JustInTimeActions(
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    isGasPriceRefreshing: Boolean,
    showGasPriceRefreshError: Boolean,
    onSetGasPrice: (Float) -> Unit,
    onRefreshGasPrice: () -> Unit,
    onResumeAutoGasPrice: () -> Unit,
    onOpenVehicleSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            GasQuickEdit(
                gasPrice = gasPrice,
                isGasPriceAuto = isGasPriceAuto,
                isRefreshing = isGasPriceRefreshing,
                showRefreshError = showGasPriceRefreshError,
                onSetGasPrice = onSetGasPrice,
                onRefreshGasPrice = onRefreshGasPrice,
                onResumeAutoGasPrice = onResumeAutoGasPrice,
            )
        }
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onOpenVehicleSettings),
        ) {
            VehicleAction()
        }
    }
}

/**
 * Mode-adaptive gas quick-edit (#722 revision) — ONE control set per mode, because each action's
 * meaning flips with mode (a bare refresh in manual would silently re-enable auto). Anything that
 * changes mode is a LABELED gesture, never a bare icon:
 * - AUTO: price (tap = "take manual control", the #721 auto-flip, now an intentional gesture) +
 *   AUTO caption + a refresh icon (single meaning: fetch now, stay auto). No stepper.
 * - MANUAL: stepper + MANUAL caption + a labeled "Resume auto" chip (re-enable auto + fetch, one
 *   atomic write — never a bare refresh icon, which would be a hidden mode change).
 */
@Composable
private fun GasQuickEdit(
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    isRefreshing: Boolean,
    showRefreshError: Boolean,
    onSetGasPrice: (Float) -> Unit,
    onRefreshGasPrice: () -> Unit,
    onResumeAutoGasPrice: () -> Unit,
) {
    // The store IS the source of truth — the displayed value is the flow, never a local shadow copy.
    val current = gasPrice ?: UserEconomy.DEFAULT_GAS_PRICE_PER_GALLON.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.LocalGasStation,
            contentDescription = stringResource(R.string.bubble_mode_idle_gas_content_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        if (isGasPriceAuto) {
            GasPriceAutoDisplay(price = current, onTakeManualControl = { onSetGasPrice(current) })
            GasRefreshButton(isRefreshing = isRefreshing, onClick = onRefreshGasPrice)
        } else {
            // 48dp touch target (#728) — was 28dp, too small to reliably tap while driving.
            IconButton(
                onClick = { onSetGasPrice((current - GAS_STEP).coerceAtLeast(0f)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.bubble_mode_idle_gas_decrease),
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = Formats.money(current.toDouble()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.bubble_mode_idle_gas_manual),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            IconButton(
                onClick = { onSetGasPrice(current + GAS_STEP) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.bubble_mode_idle_gas_increase),
                    modifier = Modifier.size(16.dp),
                )
            }
            ResumeAutoChip(isRefreshing = isRefreshing, onClick = onResumeAutoGasPrice)
        }
        if (showRefreshError) {
            Text(
                text = stringResource(R.string.bubble_mode_idle_gas_refresh_error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** AUTO-mode price display — tapping it is the explicit "take manual control" gesture (#722). */
@Composable
private fun GasPriceAutoDisplay(price: Float, onTakeManualControl: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        // 48dp touch target (#728) — was a bare 6/2dp pad, too small to reliably tap.
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onTakeManualControl)
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = Formats.money(price.toDouble()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.bubble_mode_idle_gas_take_control),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = stringResource(R.string.bubble_mode_idle_gas_auto),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** AUTO-mode refresh icon: single meaning — fetch today's EIA price now, stay auto (#722). */
@Composable
private fun GasRefreshButton(isRefreshing: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = !isRefreshing,
        modifier = Modifier.size(48.dp),
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.bubble_mode_idle_gas_refresh),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * MANUAL-mode "Resume auto" chip (#722) — a LABELED action (never a bare icon) so re-enabling
 * auto is never a hidden side effect of what looks like a refresh.
 */
@Composable
private fun ResumeAutoChip(isRefreshing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !isRefreshing,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        // 48dp touch target (#728) — was a bare 8/5dp pad, too small to reliably tap; the Box
        // forces the surface to at least 48dp and centers the (shorter) content within it.
        Box(
            modifier = Modifier.heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.bubble_mode_idle_gas_resume_auto),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * Vehicle deep-link content (#728) — the tap target is now the whole [AppCard] the caller wraps
 * this in (see [JustInTimeActions]), so this composable renders content only, no Surface/onClick
 * of its own.
 */
@Composable
private fun VehicleAction() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.bubble_mode_idle_vehicle_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
