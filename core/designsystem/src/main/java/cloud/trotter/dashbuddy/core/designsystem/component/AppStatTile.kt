package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Mini stat cell — uppercase label (+ optional [leading] slot), a numeric value, optional sub.
 * Dashboard "Today" glance, analytics tiles, ratings counts.
 */
@Composable
fun AppStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = AppTheme.colors.text,
    leading: (@Composable () -> Unit)? = null,
) {
    AppCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leading != null) leading()
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.text3,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = value, style = AppTheme.num.xlNum, color = valueColor)
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text3)
        }
    }
}

@Preview
@Composable
private fun AppStatTilePreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        AppStatTile(
            label = "Net/hr",
            value = "$19.40",
            sub = "after real costs",
            valueColor = AppTheme.colors.good,
            modifier = Modifier.padding(16.dp),
        )
    }
}
