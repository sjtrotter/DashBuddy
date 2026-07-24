package cloud.trotter.dashbuddy.feature.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.presentation
import cloud.trotter.dashbuddy.feature.bubble.formatters.color

// ---------------------------------------------------------------------------
// TopAppBar title — status badge (left side)
// ---------------------------------------------------------------------------

@Composable
fun StatusBadgeTitle(region: PlatformRegion?, flow: FlowRegion, platform: Platform?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Platform label — only shown when a platform is active.
        // Short name is the SSOT on the Platform enum (audit #9).
        platform?.let {
            Text(
                text = it.shortName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
            )
        }
        val (badgeText, badgeColor) = statusBadge(region, flow.flow)
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// TopAppBar actions — session earnings + miles (right side, active sessions only)
// ---------------------------------------------------------------------------

@Composable
fun SessionMetricsActions(
    region: PlatformRegion?,
    earnings: Double,
    miles: Double,
    lastSession: SessionRecord?
) {
    val isActive = region?.mode == Mode.Online || region?.mode == Mode.Paused

    val displayEarnings: Double
    val displayMiles: Double
    val dimmed: Boolean
    val captionRes: Int

    when {
        isActive -> {
            displayEarnings = earnings
            displayMiles = miles
            dimmed = false
            captionRes = R.string.bubble_status_this_session
        }
        region?.mode == Mode.Offline && lastSession != null -> {
            // Last-dash review: dimmed to signal it's history, not a live session (#693).
            displayEarnings = lastSession.reportedEarnings ?: 0.0
            displayMiles = lastSession.miles ?: 0.0
            dimmed = true
            captionRes = R.string.bubble_status_last_session
        }
        else -> return
    }

    val textColor = if (dimmed)
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    else
        MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.padding(end = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = stringResource(captionRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Formats.money(displayEarnings),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            )
            Text(
                text = "${Formats.decimal(displayMiles)} mi",
                style = MaterialTheme.typography.titleSmall,
                color = textColor
            )
        }
    }
}

@Composable
private fun statusBadge(region: PlatformRegion?, flow: Flow): Pair<String, Color> {
    val c = AppTheme.colors

    // Mode-driven badges — orthogonal to the flow phase (availability axis).
    if (region?.mode == Mode.Paused) return "PAUSED" to c.warn
    if (region == null || region.mode == Mode.Offline) {
        return when (flow) {
            Flow.SessionEnded -> "DONE" to c.neutral
            else -> "OFFLINE" to c.neutral
        }
    }

    // Flow-driven badges (online) — long-form label + color from the phase SSOT
    // (PhasePresentation in :domain; the chip in FlowCardItem shares the row).
    val p = flow.presentation
    return p.longLabel to p.longColor.color(c)
}
