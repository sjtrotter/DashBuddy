package cloud.trotter.dashbuddy.ui.main.setup.wizard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WizardBottomBar(
    showBack: Boolean,
    isLastStep: Boolean,
    onBackClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = if (showBack) Arrangement.SpaceBetween else Arrangement.End
    ) {
        if (showBack) {
            OutlinedButton(onClick = onBackClick) {
                Text("Back")
            }
        }

        Button(onClick = onNextClick) {
            Text(
                text = if (isLastStep) "Finish & Save" else "Next",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}