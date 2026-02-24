package cloud.trotter.dashbuddy.ui.main.settings.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.state.model.OfferAction
import cloud.trotter.dashbuddy.state.model.OfferEvaluation

@Composable
fun FakeOfferCard(
    evaluation: OfferEvaluation,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    // 1. Logic: In Dark Mode, keep background dark (Surface). In Light Mode, use Pastels.
    val targetContainerColor = if (isDark) {
        // Dark Mode: Stay dark, maybe slightly tinted
        when (evaluation.action) {
            OfferAction.ACCEPT -> Color(0xFF1B5E20).copy(alpha = 0.3f) // Very dark green tint
            OfferAction.DECLINE -> Color(0xFFB71C1C).copy(alpha = 0.3f) // Very dark red tint
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    } else {
        // Light Mode: Use Pastels
        when (evaluation.action) {
            OfferAction.ACCEPT -> Color(0xFFE8F5E9)
            OfferAction.DECLINE -> Color(0xFFFFEBEE)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    }

    // 2. Logic: Text colors need to pop against the chosen background
    val targetContentColor = if (isDark) {
        Color.White // Always white in dark mode
    } else {
        Color.Black // Always black in light mode
    }

    // 3. Logic: Borders carry the heavy lifting in Dark Mode
    val borderColor = when (evaluation.action) {
        OfferAction.ACCEPT -> if (isDark) Color(0xFF4CAF50) else Color(0xFF2E7D32)
        OfferAction.DECLINE -> if (isDark) Color(0xFFEF5350) else Color(0xFFC62828)
        else -> Color.Gray
    }

    val animatedContainerColor by animateColorAsState(targetContainerColor, label = "container")
    val animatedContentColor by animateColorAsState(targetContentColor, label = "content")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = animatedContainerColor,
            contentColor = animatedContentColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = evaluation.message.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricBadge(Icons.Default.AttachMoney, "Pay", animatedContentColor)
                MetricBadge(Icons.Default.DirectionsCar, "Dist", animatedContentColor)
                MetricBadge(Icons.Default.Timer, "Time", animatedContentColor)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when (evaluation.action) {
                    OfferAction.ACCEPT -> "AUTO ACCEPT"
                    OfferAction.DECLINE -> "AUTO DECLINE"
                    else -> "MANUAL REVIEW"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = if (isDark) borderColor else Color.Black.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MetricBadge(icon: ImageVector, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}