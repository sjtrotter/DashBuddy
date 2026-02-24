package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.ChoiceRow
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep

/**
 * A reusable card that displays 2 (or optionally 3) distinct choices to the user.
 */
@Composable
fun SelectionCard(
    step: WizardStep,

    option1Title: String,
    option1Desc: String,

    option2Title: String,
    option2Desc: String,

    option3Title: String? = null,
    option3Desc: String? = null,

    selectedIndex: Int, // 0 = Opt1, 1 = Opt2, 2 = Opt3
    onOptionSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step)

        Spacer(modifier = Modifier.height(32.dp))

        ChoiceRow(
            title = option1Title,
            description = option1Desc,
            isSelected = selectedIndex == 0,
            onClick = { onOptionSelected(0) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ChoiceRow(
            title = option2Title,
            description = option2Desc,
            isSelected = selectedIndex == 1,
            onClick = { onOptionSelected(1) }
        )

        // Render 3rd option only if provided
        if (option3Title != null && option3Desc != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ChoiceRow(
                title = option3Title,
                description = option3Desc,
                isSelected = selectedIndex == 2,
                onClick = { onOptionSelected(2) }
            )
        }
    }
}