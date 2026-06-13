package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Card surface — the `.db-card` / `.db-card-active` tokens. `active` raises elevation tone
 * (surface-2 + stronger hairline) for the pinned/active HUD card. Content is a [ColumnScope].
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    contentPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (active) c.surface2 else c.surface,
        border = BorderStroke(1.dp, if (active) c.lineStrong else c.line),
    ) {
        Column(
            modifier = if (contentPadding) Modifier.padding(12.dp) else Modifier,
            content = content,
        )
    }
}

/**
 * Tinted info strip — the analytics `Callout`. Body text on a soft semantic background
 * (e.g. accentDim / goodBg / warnBg / badBg).
 */
@Composable
fun AppCallout(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = AppTheme.colors.accentDim,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.text2,
        )
    }
}

@Preview
@Composable
private fun AppCardPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp)) {
            AppCard(active = true) {
                Text("Active card", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.text)
            }
            Column(Modifier.padding(top = 12.dp)) {
                AppCallout("You kept $612 of $812 gross — a 75% true margin.")
            }
        }
    }
}
