package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * Slider with a min / value / max readout below (the live value in the brand accent, numeric).
 * Economy costs, strategy targets, wizard steps. [format] turns the raw float into display text.
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    format: (Float) -> String = { it.toString() },
) {
    val c = AppTheme.colors
    Column(modifier) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.surface3,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(valueRange.start), style = MaterialTheme.typography.bodySmall, color = c.text3)
            Text(format(value), style = AppTheme.num.smNum, color = c.accent)
            Text(format(valueRange.endInclusive), style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
    }
}

@Preview
@Composable
private fun AppSliderPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        var v by remember { mutableFloatStateOf(22f) }
        AppSlider(
            value = v,
            onValueChange = { v = it },
            valueRange = 10f..40f,
            modifier = Modifier.padding(16.dp),
            format = { "$" + it.toInt() },
        )
    }
}
