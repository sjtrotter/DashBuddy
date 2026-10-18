package cloud.trotter.dashbuddy.feature.bubble.hud

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.format.Formats

private const val GAS_STEP = 0.05f

@Composable
internal fun GasQuickEdit(
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
