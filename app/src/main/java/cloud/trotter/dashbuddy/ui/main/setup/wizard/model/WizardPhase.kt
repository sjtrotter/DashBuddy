package cloud.trotter.dashbuddy.ui.main.setup.wizard.model

import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R

enum class WizardPhase(@param:StringRes val titleRes: Int) {
    ECONOMY(R.string.wizard_phase_economy),
    STRATEGY(R.string.wizard_phase_strategy)
}