package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.DashTheme

/**
 * Expandable row with a `$/mi`-style [trailing] summary — Personal Economy cost accordions.
 * Caller owns the [expanded] state and the [onToggle] lambda (hoisted, pure).
 */
@Composable
fun DashAccordion(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DashTheme.colors
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = c.surface,
        border = BorderStroke(1.dp, if (expanded) c.lineStrong else c.line),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.text)
                if (trailing != null) Text(trailing, style = DashTheme.num.smNum, color = c.text2)
                Chevron(expanded, c.text3)
            }
            if (expanded) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean, tint: Color) {
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Canvas(Modifier.size(14.dp).rotate(rot)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        drawLine(tint, Offset(w * 0.25f, h * 0.4f), Offset(w * 0.5f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.75f, h * 0.4f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Preview
@Composable
private fun DashAccordionPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        var open by remember { mutableStateOf(true) }
        DashAccordion(
            title = "Tires",
            expanded = open,
            onToggle = { open = !open },
            trailing = "$0.016/mi",
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Set cost ÷ lifetime miles.", style = MaterialTheme.typography.bodySmall, color = DashTheme.colors.text3)
        }
    }
}
