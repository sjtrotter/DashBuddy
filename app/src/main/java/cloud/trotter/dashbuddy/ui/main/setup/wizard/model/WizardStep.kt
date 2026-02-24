package cloud.trotter.dashbuddy.ui.main.setup.wizard.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.trotter.dashbuddy.R

enum class WizardStep(
    val phase: WizardPhase,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector
) {
    // --- Phase 1: Economy ---
    VEHICLE(
        phase = WizardPhase.ECONOMY,
        titleRes = R.string.wizard_title_vehicle,
        descriptionRes = R.string.wizard_desc_vehicle,
        icon = Icons.Default.DirectionsCar
    ),
    GAS_PRICE(
        phase = WizardPhase.ECONOMY,
        titleRes = R.string.wizard_title_gas,
        descriptionRes = R.string.wizard_desc_gas,
        icon = Icons.Default.LocalGasStation
    ),

    // --- Phase 2: Strategy ---
    GOAL(
        phase = WizardPhase.STRATEGY,
        titleRes = R.string.wizard_title_goal,
        descriptionRes = R.string.wizard_desc_goal,
        icon = Icons.Default.TrackChanges
    ),
    SHOPPING(
        phase = WizardPhase.STRATEGY,
        titleRes = R.string.wizard_title_shopping,
        descriptionRes = R.string.wizard_desc_shopping,
        icon = Icons.Default.ShoppingCart
    ),
    MIN_PAYOUT(
        phase = WizardPhase.STRATEGY,
        titleRes = R.string.wizard_title_min_payout,
        descriptionRes = R.string.wizard_desc_min_payout,
        icon = Icons.Default.AttachMoney
    ),
    TARGET_HOURLY(
        phase = WizardPhase.STRATEGY,
        titleRes = R.string.wizard_title_hourly,
        descriptionRes = R.string.wizard_desc_hourly,
        icon = Icons.Default.Timer
    ),
    MAX_DISTANCE(
        phase = WizardPhase.STRATEGY,
        titleRes = R.string.wizard_title_distance,
        descriptionRes = R.string.wizard_desc_distance,
        icon = Icons.Default.DirectionsCar
    )
}