package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.theme.LocalGlance
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChip

@Composable
fun HudScreen(
    state: HudUiState,
    ribbon: RibbonModel?,
    onDockClick: (DockKind) -> Unit,
    modifier: Modifier = Modifier,
    onSelectPlatform: (Platform) -> Unit = {},
    onSetGasPrice: (Float) -> Unit = {},
    onRefreshGasPrice: () -> Unit = {},
    onResumeAutoGasPrice: () -> Unit = {},
    onGoalCelebrated: (String) -> Unit = {},
    onGoalClick: (() -> Unit)? = null,
    sheetOpen: Boolean = false,
    onSheetDismiss: () -> Unit = {},
    sheet: @Composable ColumnScope.() -> Unit = {},
    undo: (@Composable () -> Unit)? = null,
    restingFooter: @Composable () -> Unit = {},
    stateCard: @Composable () -> Unit = {},
    offer: @Composable () -> Unit = {},
    log: @Composable () -> Unit = {},
    undoVisible: @Composable () -> Boolean = { undo != null },
    sheetCompact: Boolean = false,
    onSheetOutsideDismiss: () -> Unit = onSheetDismiss,
    vehicleMenuOpen: Boolean = false,
    onVehicleChipClick: () -> Unit = {},
    onDismissVehicleMenu: () -> Unit = {},
    onSelectVehicle: (String) -> Unit = {},
    onAddVehicle: () -> Unit = {},
) {
    val tier = FontScaleTier.of(LocalDensity.current.fontScale, LocalGlance.current)
    CompositionLocalProvider(LocalHudTier provides tier) {
        Box(modifier.fillMaxSize().background(AppTheme.colors.bg)) {
            PhaseStrip(state.stripColor, state.mode == HudMode.RESTING || state.paused)
            Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp, bottom = 12.dp)) {
                val showingLog = state.logOpen || state.mode == HudMode.LOG
                val dismissHeader = sheetOpen && sheetCompact && !showingLog && state.mode != HudMode.OFFER
                val goalClick = onGoalClick?.let { route ->
                    { onSheetOutsideDismiss(); route() }
                }
                // The hero has just one control: its pill consumes the tap; the rest dismisses.
                Column(if (dismissHeader) Modifier.pointerInput(onSheetOutsideDismiss) {
                    detectTapGestures { onSheetOutsideDismiss() }
                } else Modifier) {
                    Box {
                        Column {
                            HudHead(state.platform, state.switchable, state.paused, onSelectPlatform) {
                                state.vehicleChip?.let {
                                    VehicleChip(it, vehicleMenuOpen, onVehicleChipClick, onDismissVehicleMenu,
                                        onSelectVehicle, onAddVehicle)
                                }
                            }
                            if (state.mode != HudMode.OFFER) TopRow(state.top)
                        }
                        // In front of the header controls, so an outside tap cannot switch platforms.
                        if (dismissHeader) Box(Modifier.matchParentSize().testTag("hud.sheet.outside.header")
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = onSheetOutsideDismiss))
                    }
                    if (state.mode != HudMode.OFFER) {
                        if (showingLog) CompactHeroRow(state.hero, goalClick)
                        else HeroRow(state.hero, onGoalCelebrated, goalClick)
                    }
                }
                if (state.mode == HudMode.OFFER) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("hud.slot.offer")) { offer() }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.body")) {
                        Column(Modifier.fillMaxSize()) {
                            when {
                                showingLog -> Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.log")) { log() }
                                state.mode == HudMode.RESTING -> {
                                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { RestingCard(state.resting) }
                                    GasRow(state.gas, onSetGasPrice, onRefreshGasPrice, onResumeAutoGasPrice)
                                    restingFooter()
                                }
                                else -> Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.state")) {
                                    Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = 196.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(AppTheme.colors.surface)) { stateCard() }
                                }
                            }
                            if (!showingLog && state.mode != HudMode.RESTING && ribbon != null) DashRibbon(ribbon)
                            if (!showingLog) UndoSlot(state.mode, undo, undoVisible)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (!showingLog) HudSheetHost(sheetOpen, onSheetDismiss, Modifier.fillMaxSize(),
                            compact = sheetCompact, undoBandVisible = undoVisible(), onOutsideDismiss = onSheetOutsideDismiss, sheet = sheet)
                    }
                    Box(Modifier.testTag("hud.dock")) { Dock(state.dock, onDockClick) }
                }
            }
        }
    }
}
