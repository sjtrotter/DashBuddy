package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Pill segmented control — analytics tabs / period switches. Selected segment fills with the
 * brand accent. Pure data + an `onSelect` lambda.
 */
@Composable
fun AppSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSel) c.accent else Color.Transparent)
                    .clickable { onSelect(opt) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSel) c.accentText else c.text2,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AppSegmentedPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        AppSegmented(
            options = listOf("Money", "Patterns", "Decisions", "Time"),
            selected = "Money",
            onSelect = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
