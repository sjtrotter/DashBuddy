package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Uppercase pill / badge — the `.db-chip` token. Phase chips, status badges, outcome chips,
 * analytics legend keys. Pure data + tokens.
 */
@Composable
fun AppChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.neutral,
    container: Color = AppTheme.colors.neutralBg,
    uppercase: Boolean = true,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        style = AppTheme.num.chip,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Preview
@Composable
private fun AppChipPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        AppChip(
            "Offer",
            color = AppTheme.colors.stOffer,
            container = AppTheme.colors.stOfferBg,
            modifier = Modifier.padding(16.dp),
        )
    }
}
