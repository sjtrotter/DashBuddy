package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
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
    onSetGasPrice: (Float) -> Unit,
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
            onSetGasPrice = onSetGasPrice,
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

@Composable
private fun JustInTimeActions(
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    onSetGasPrice: (Float) -> Unit,
    onOpenVehicleSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GasQuickEdit(
            gasPrice = gasPrice,
            isGasPriceAuto = isGasPriceAuto,
            onSetGasPrice = onSetGasPrice,
        )
        Spacer(modifier = Modifier.width(4.dp))
        VehicleAction(onClick = onOpenVehicleSettings)
    }
}

@Composable
private fun GasQuickEdit(
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    onSetGasPrice: (Float) -> Unit,
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
        IconButton(
            onClick = { onSetGasPrice((current - GAS_STEP).coerceAtLeast(0f)) },
            modifier = Modifier.size(28.dp),
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
                text = stringResource(
                    if (isGasPriceAuto) R.string.bubble_mode_idle_gas_auto
                    else R.string.bubble_mode_idle_gas_manual
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        IconButton(
            onClick = { onSetGasPrice(current + GAS_STEP) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.bubble_mode_idle_gas_increase),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun VehicleAction(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
